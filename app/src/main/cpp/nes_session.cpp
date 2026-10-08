// SPDX-License-Identifier: GPL-2.0-or-later
#include "nes_session.h"

#include <libretro.h>
#include <algorithm>
#include <array>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>
#include <map>
#include <sys/stat.h>

extern "C" {
#include <md5.h>
}

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {
constexpr size_t kMaxRomSize = 32 * 1024 * 1024;
constexpr size_t kMaxStateSize = 4 * 1024 * 1024;
constexpr size_t kStateHeaderSize = 64;
constexpr uint32_t kCoreStateVersion = 9900;
constexpr char kStateMagic[] = "CNESST01";
constexpr char kCoreIdentity[] = "fceumm-7a542dab";
constexpr size_t kAudioSamples = 16384; // Interleaved stereo; about 170 ms.
constexpr unsigned kMaxDimension = 1024;

bool initialized = false;
bool loaded = false;
uint16_t inputButtons = 0;
std::string systemDirectory;
std::string saveDirectory;
std::string romFilename;
std::string lastCoreError;
std::array<uint8_t, 16> romDigest{};
std::map<std::string, std::string> options;
retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
retro_system_av_info avInfo{};
unsigned videoWidth = 256;
unsigned videoHeight = 240;
std::vector<int32_t> video(256 * 240, static_cast<int32_t>(0xff000000));
std::array<int16_t, kAudioSamples> audio{};
size_t audioRead = 0;
size_t audioSize = 0;

void clearAudio() {
    audioRead = 0;
    audioSize = 0;
}

uint32_t read32(const uint8_t* bytes) {
    return static_cast<uint32_t>(bytes[0]) | (static_cast<uint32_t>(bytes[1]) << 8) |
           (static_cast<uint32_t>(bytes[2]) << 16) | (static_cast<uint32_t>(bytes[3]) << 24);
}
void write32(uint8_t* bytes, uint32_t value) {
    for (unsigned i = 0; i < 4; ++i) bytes[i] = static_cast<uint8_t>(value >> (i * 8));
}
std::array<uint8_t, 16> digest(const uint8_t* bytes, size_t size) {
    md5_context context{};
    std::array<uint8_t, 16> result{};
    md5_starts(&context);
    md5_update(&context, bytes, static_cast<uint32_t>(size));
    md5_finish(&context, result.data());
    return result;
}

bool validCoreState(const uint8_t* data, size_t size) {
    if (size < 16 || std::memcmp(data, "FCS\xff", 4) != 0 ||
        read32(data + 8) != kCoreStateVersion || read32(data + 4) != size - 16) return false;
    unsigned seen = 0;
    size_t offset = 16;
    while (offset < size) {
        if (size - offset < 5) return false;
        const uint8_t type = data[offset];
        const size_t chunkSize = read32(data + offset + 1);
        offset += 5;
        if (chunkSize > size - offset) return false;
        unsigned bit = 0;
        if (type >= 1 && type <= 5) bit = 1u << (type - 1);
        if (type == 0x10) bit = 1u << 5;
        if (bit) {
            if (seen & bit) return false;
            seen |= bit;
            size_t field = offset;
            const size_t end = offset + chunkSize;
            while (field < end) {
                if (end - field < 8) return false;
                const size_t fieldSize = read32(data + field + 4);
                field += 8;
                if (fieldSize > end - field) return false;
                field += fieldSize;
            }
        }
        offset += chunkSize;
    }
    return seen == 63 && offset == size;
}

void logger(retro_log_level level, const char* format, ...) {
    if (!format) return;
    char message[2048];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    if (level == RETRO_LOG_ERROR) lastCoreError = message;
#ifdef __ANDROID__
    const int priority = level == RETRO_LOG_ERROR ? ANDROID_LOG_ERROR :
                         level == RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_write(priority, "NesCore", message);
#else
    if (level >= RETRO_LOG_WARN) std::fprintf(stderr, "%s", message);
#endif
}

void captureDefault(const char* key, const char* value) {
    if (key && value) options.emplace(key, value);
}

bool environment(unsigned command, void* data) {
    switch (command) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (!data) return false;
            *static_cast<const char**>(data) = command == RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY
                ? systemDirectory.c_str() : saveDirectory.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            if (!data) return false;
            *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            if (!data) return false;
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (!data) return false;
            static_cast<retro_log_callback*>(data)->log = logger;
            return true;
        case RETRO_ENVIRONMENT_GET_TARGET_SAMPLE_RATE:
            if (!data) return false;
            *static_cast<unsigned*>(data) = 48000;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (!data) return false;
            *static_cast<unsigned*>(data) = 2;
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL: {
            if (!data) return false;
            const auto* definitions = command == RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2
                ? static_cast<retro_core_options_v2*>(data)->definitions
                : static_cast<retro_core_options_v2_intl*>(data)->us->definitions;
            if (!definitions) return false;
            for (const auto* option = definitions; option->key; ++option)
                captureDefault(option->key, option->default_value);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS: {
            if (!data) return false;
            for (auto* option = static_cast<retro_core_option_definition*>(data); option->key; ++option)
                captureDefault(option->key, option->default_value);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES: {
            if (!data) return false;
            for (auto* option = static_cast<retro_variable*>(data); option->key; ++option) {
                if (!option->value) continue;
                const char* separator = std::strstr(option->value, "; ");
                if (!separator) continue;
                std::string value(separator + 2);
                value.resize(value.find('|') == std::string::npos ? value.size() : value.find('|'));
                captureDefault(option->key, value.c_str());
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            if (!data) return false;
            auto* variable = static_cast<retro_variable*>(data);
            variable->value = nullptr;
            if (!variable->key) return false;
            const auto found = options.find(variable->key);
            if (found == options.end()) return false;
            variable->value = found->second.c_str();
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            if (!data) return false;
            *static_cast<bool*>(data) = false;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            if (!data) return false;
            switch (*static_cast<retro_pixel_format*>(data)) {
                case RETRO_PIXEL_FORMAT_RGB565:
                case RETRO_PIXEL_FORMAT_XRGB8888:
                case RETRO_PIXEL_FORMAT_0RGB1555:
                    pixelFormat = *static_cast<retro_pixel_format*>(data);
                    return true;
                default: return false;
            }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            if (!data) return false;
            avInfo = *static_cast<retro_system_av_info*>(data);
            return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            if (!data) return false;
            avInfo.geometry = *static_cast<retro_game_geometry*>(data);
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (!data) return false;
            *static_cast<int*>(data) = 3;
            return true;
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
            if (!data) return false;
            *static_cast<unsigned*>(data) = 0;
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            if (!data) return false;
            const auto* message = static_cast<retro_message*>(data);
            if (message->msg) logger(RETRO_LOG_INFO, "%s", message->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
            return true;
        default:
            // In particular, no hardware rendering or external framebuffer.
            return false;
    }
}

void videoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID) return; // Duplicate frame.
    if (!width || !height || width > kMaxDimension || height > kMaxDimension) return;
    const size_t bytesPerPixel = pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888 ? 4 : 2;
    if (pitch < static_cast<size_t>(width) * bytesPerPixel) return;
    if (width != videoWidth || height != videoHeight) {
        video.assign(static_cast<size_t>(width) * height, static_cast<int32_t>(0xff000000));
        videoWidth = width;
        videoHeight = height;
    }
    const auto* bytes = static_cast<const uint8_t*>(data);
    for (unsigned y = 0; y < height; ++y) {
        const uint8_t* row = bytes + y * pitch;
        for (unsigned x = 0; x < width; ++x) {
            uint32_t argb;
            if (pixelFormat == RETRO_PIXEL_FORMAT_XRGB8888) {
                uint32_t source;
                std::memcpy(&source, row + x * 4, sizeof(source));
                argb = source | 0xff000000;
            } else {
                uint16_t source;
                std::memcpy(&source, row + x * 2, sizeof(source));
                const bool rgb565 = pixelFormat == RETRO_PIXEL_FORMAT_RGB565;
                unsigned r = (source >> (rgb565 ? 11 : 10)) & 31;
                unsigned g = (source >> 5) & (rgb565 ? 63 : 31);
                unsigned b = source & 31;
                r = (r << 3) | (r >> 2);
                g = rgb565 ? (g << 2) | (g >> 4) : (g << 3) | (g >> 2);
                b = (b << 3) | (b >> 2);
                argb = 0xff000000 | (r << 16) | (g << 8) | b;
            }
            video[static_cast<size_t>(y) * width + x] = static_cast<int32_t>(argb);
        }
    }
}

size_t audioBatch(const int16_t* data, size_t frames) {
    if (!data || frames > std::numeric_limits<size_t>::max() / 2) return 0;
    // Drop oldest complete stereo frames on overflow instead of accumulating lag.
    size_t count = frames * 2;
    if (count > kAudioSamples) {
        data += count - kAudioSamples;
        count = kAudioSamples;
    }
    if (audioSize + count > kAudioSamples) {
        const size_t discard = audioSize + count - kAudioSamples;
        audioRead = (audioRead + discard) % kAudioSamples;
        audioSize -= discard;
    }
    size_t write = (audioRead + audioSize) % kAudioSamples;
    for (size_t i = 0; i < count; ++i) audio[(write + i) % kAudioSamples] = data[i];
    audioSize += count;
    return frames;
}

void audioSample(int16_t left, int16_t right) {
    const int16_t stereo[] = {left, right};
    audioBatch(stereo, 1);
}
void inputPoll() {}
int16_t inputState(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port != 0 || (device & RETRO_DEVICE_MASK) != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(inputButtons);
    return id < 16 && (inputButtons & (1u << id)) ? 1 : 0;
}

std::string checkRom(const std::string& path, std::array<uint8_t, 16>& romId) {
    if (path.empty() || path.size() >= 1024) return "The ROM path is invalid.";
    struct stat info{};
    if (stat(path.c_str(), &info) != 0 || !S_ISREG(info.st_mode)) return "The ROM file could not be opened.";
    if (info.st_size < 16) return "This file is too small to be a NES ROM.";
    if (static_cast<uint64_t>(info.st_size) > kMaxRomSize) return "ROM files must be 32 MB or smaller.";
    std::ifstream file(path, std::ios::binary);
    uint8_t header[16]{};
    if (!file.read(reinterpret_cast<char*>(header), sizeof(header))) return "The ROM file could not be read.";
    if (std::memcmp(header, "NES\x1a", 4) == 0) {
        uint64_t prgSize = static_cast<uint64_t>(header[4]) * 16384;
        uint64_t chrSize = static_cast<uint64_t>(header[5]) * 8192;
        if ((header[7] & 0x0c) == 0x08) {
            const auto nes2Size = [](uint8_t low, unsigned high, uint64_t unit) {
                if (high != 15) return ((static_cast<uint64_t>(high) << 8) | low) * unit;
                const unsigned exponent = low >> 2;
                // Larger exponents cannot fit within the frontend's ROM limit.
                return exponent > 25 ? static_cast<uint64_t>(kMaxRomSize + 1)
                                     : (uint64_t{1} << exponent) * ((low & 3) * 2 + 1);
            };
            prgSize = nes2Size(header[4], header[9] & 15, 16384);
            chrSize = nes2Size(header[5], header[9] >> 4, 8192);
        }
        if (!prgSize) return "The NES ROM has no program data.";
        const uint64_t declared = 16 + ((header[6] & 4) ? 512 : 0) + prgSize + chrSize;
        if (declared > kMaxRomSize) return "The NES ROM header declares more than 32 MB of data.";
        if (static_cast<uint64_t>(info.st_size) < declared) return "The NES ROM is incomplete.";
    } else if (std::memcmp(header, "UNIF", 4) == 0) {
        if (info.st_size < 32) return "The UNIF ROM is incomplete.";
    } else if (std::memcmp(header, "FDS\x1a", 4) == 0) {
        if (!header[4] || info.st_size < 16 + static_cast<int64_t>(header[4]) * 65500)
            return "The FDS disk image is incomplete.";
    } else if (header[0] == 1 && std::memcmp(header + 1, "*NINTENDO-HVC*", 14) == 0) {
        if (info.st_size < 65500 || info.st_size % 65500 != 0) return "The FDS disk image is incomplete.";
    } else {
        return "This is not a supported NES ROM. Choose a .nes, .unf, .unif, or .fds file.";
    }
    // Identity and corruption detection only; this is not an authentication hash.
    file.clear();
    file.seekg(0);
    md5_context context{};
    md5_starts(&context);
    std::array<uint8_t, 8192> block{};
    while (file) {
        file.read(reinterpret_cast<char*>(block.data()), block.size());
        const auto count = file.gcount();
        if (count > 0) md5_update(&context, block.data(), static_cast<uint32_t>(count));
    }
    if (!file.eof()) return "The ROM file could not be read.";
    md5_finish(&context, romId.data());
    return {};
}
}

namespace nes {
std::string load(const std::string& romPath, const std::string& systemDir,
                 const std::string& saveDir) {
    std::array<uint8_t, 16> newRomDigest{};
    const std::string validationError = checkRom(romPath, newRomDigest);
    if (!validationError.empty()) return validationError;
    unload();
    systemDirectory = systemDir;
    saveDirectory = saveDir;
    romFilename = romPath;
    romDigest = newRomDigest;
    lastCoreError.clear();
    options.clear();
    options["fceumm_overscan_h_left"] = "0";
    options["fceumm_overscan_h_right"] = "0";
    options["fceumm_overscan_v_top"] = "0";
    options["fceumm_overscan_v_bottom"] = "0";
    options["fceumm_sndrate_hint"] = "48KHz";
    options["fceumm_sndquality"] = "High";
    options["fceumm_sndstereodelay"] = "disabled";
    pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
    avInfo = {};
    videoWidth = 256;
    videoHeight = 240;
    video.assign(videoWidth * videoHeight, static_cast<int32_t>(0xff000000));
    inputButtons = 0;
    clearAudio();
    retro_set_environment(environment);
    retro_set_video_refresh(videoRefresh);
    retro_set_audio_sample(audioSample);
    retro_set_audio_sample_batch(audioBatch);
    retro_set_input_poll(inputPoll);
    retro_set_input_state(inputState);
    retro_init();
    initialized = true;
    retro_game_info game{};
    game.path = romFilename.c_str();
    if (!retro_load_game(&game)) {
        const std::string error = lastCoreError.empty() ? "FCEUmm could not load this ROM." : lastCoreError;
        unload();
        return error;
    }
    loaded = true;
    retro_set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    retro_set_controller_port_device(1, RETRO_DEVICE_JOYPAD);
    retro_get_system_av_info(&avInfo);
    return {};
}

void unload() {
    if (loaded) retro_unload_game();
    if (initialized) retro_deinit();
    loaded = false;
    initialized = false;
    inputButtons = 0;
    clearAudio();
}
void reset() {
    if (loaded) retro_reset();
    inputButtons = 0;
    clearAudio();
}
double fps() { return loaded && avInfo.timing.fps > 0 ? avInfo.timing.fps : 60.0988; }
int sampleRate() { return loaded && avInfo.timing.sample_rate > 0 ? static_cast<int>(avInfo.timing.sample_rate) : 48000; }
int width() { return static_cast<int>(videoWidth); }
int height() { return static_cast<int>(videoHeight); }
void runFrame(uint16_t buttons) {
    if (!loaded) return;
    inputButtons = buttons;
    retro_run();
}
bool copyVideo(int32_t* destination, size_t capacity) {
    if (!destination || capacity < video.size()) return false;
    std::copy(video.begin(), video.end(), destination);
    return true;
}
size_t drainAudio(int16_t* destination, size_t capacity) {
    if (!destination) return 0;
    const size_t count = std::min(audioSize, capacity - capacity % 2);
    for (size_t i = 0; i < count; ++i) destination[i] = audio[(audioRead + i) % kAudioSamples];
    audioRead = (audioRead + count) % kAudioSamples;
    audioSize -= count;
    return count;
}
std::vector<uint8_t> saveState() {
    if (!loaded) return {};
    const size_t size = retro_serialize_size();
    if (!size || size > kMaxStateSize - kStateHeaderSize) return {};
    std::vector<uint8_t> result(size + kStateHeaderSize, 0);
    uint8_t* payload = result.data() + kStateHeaderSize;
    if (!retro_serialize(payload, size) || !validCoreState(payload, size)) return {};
    std::memcpy(result.data(), kStateMagic, 8);
    write32(result.data() + 8, static_cast<uint32_t>(size));
    write32(result.data() + 12, kCoreStateVersion);
    std::copy(romDigest.begin(), romDigest.end(), result.begin() + 16);
    const auto payloadDigest = digest(payload, size);
    std::copy(payloadDigest.begin(), payloadDigest.end(), result.begin() + 32);
    std::memcpy(result.data() + 48, kCoreIdentity, sizeof(kCoreIdentity));
    return result;
}
bool loadState(const uint8_t* data, size_t size) {
    if (!loaded || !data || size < kStateHeaderSize + 16 || size > kMaxStateSize) return false;
    if (std::memcmp(data, kStateMagic, 8) != 0 || read32(data + 8) != size - kStateHeaderSize ||
        read32(data + 12) != kCoreStateVersion ||
        std::memcmp(data + 48, kCoreIdentity, sizeof(kCoreIdentity)) != 0 ||
        std::memcmp(data + 16, romDigest.data(), romDigest.size()) != 0) return false;
    const uint8_t* payload = data + kStateHeaderSize;
    const size_t payloadSize = size - kStateHeaderSize;
    const auto payloadDigest = digest(payload, payloadSize);
    if (std::memcmp(data + 32, payloadDigest.data(), payloadDigest.size()) != 0 ||
        !validCoreState(payload, payloadSize)) return false;
    // Core deserialization can modify earlier fields before a later error.
    // Keep a full checkpoint so a failed attempt is an atomic operation.
    const size_t checkpointSize = retro_serialize_size();
    if (!checkpointSize || checkpointSize > kMaxStateSize) return false;
    std::vector<uint8_t> checkpoint(checkpointSize);
    if (!retro_serialize(checkpoint.data(), checkpoint.size())) return false;
    const bool success = retro_unserialize(payload, payloadSize);
    if (!success) retro_unserialize(checkpoint.data(), checkpoint.size());
    if (success) { inputButtons = 0; clearAudio(); }
    return success;
}
std::vector<uint8_t> saveRam() {
    if (!loaded) return {};
    const size_t size = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    const auto* memory = static_cast<const uint8_t*>(retro_get_memory_data(RETRO_MEMORY_SAVE_RAM));
    if (!memory || !size || size > kMaxRomSize) return {};
    return {memory, memory + size};
}
void loadRam(const uint8_t* data, size_t size) {
    if (!loaded || !data || !size) return;
    const size_t expected = retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void* memory = retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    // Loading partial or wrong-game saves must never overwrite unrelated RAM.
    if (memory && size == expected) std::memcpy(memory, data, size);
}
}
