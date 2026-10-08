// SPDX-License-Identifier: GPL-2.0-or-later
#include "nes_session.h"
#include <libretro.h>
#include <algorithm>
#include <cstdio>
#include <fstream>
#include <vector>

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "Usage: nescore_smoke ROM.nes [frame.ppm]\n");
        return 2;
    }
    std::string error = nes::load(argv[1], "/tmp", "/tmp");
    if (!error.empty()) { std::fprintf(stderr, "%s\n", error.c_str()); return 1; }
    std::vector<int32_t> frame(nes::width() * nes::height());
    std::vector<int16_t> audio(4096);
    size_t audioSamples = 0;
    bool audible = false;
    for (int i = 0; i < 180; ++i) {
        nes::runFrame(0);
        const size_t count = nes::drainAudio(audio.data(), audio.size());
        audioSamples += count;
        audible |= std::any_of(audio.begin(), audio.begin() + count, [](int16_t sample) { return sample != 0; });
    }
    if (nes::width() != 256 || nes::height() != 240 || nes::sampleRate() != 48000 || !audioSamples) {
        std::fprintf(stderr, "Invalid video or audio output.\n"); return 1;
    }
    auto state = nes::saveState();
    if (state.empty()) { std::fprintf(stderr, "Saving state failed.\n"); return 1; }
    for (int i = 0; i < 15; ++i) nes::runFrame(1u << RETRO_DEVICE_ID_JOYPAD_RIGHT);
    nes::copyVideo(frame.data(), frame.size());
    const auto expected = frame;
    if (!nes::loadState(state.data(), state.size())) {
        std::fprintf(stderr, "Loading state failed.\n"); return 1;
    }
    for (int i = 0; i < 15; ++i) nes::runFrame(1u << RETRO_DEVICE_ID_JOYPAD_RIGHT);
    nes::copyVideo(frame.data(), frame.size());
    if (frame != expected) { std::fprintf(stderr, "State replay produced a different frame.\n"); return 1; }
    uint64_t hash = 1469598103934665603ull;
    for (int32_t pixel : frame) { hash ^= static_cast<uint32_t>(pixel); hash *= 1099511628211ull; }
    if (argc > 2) {
        std::ofstream image(argv[2], std::ios::binary);
        image << "P6\n" << nes::width() << " " << nes::height() << "\n255\n";
        for (int32_t pixel : frame) {
            const char rgb[] = {static_cast<char>(pixel >> 16), static_cast<char>(pixel >> 8), static_cast<char>(pixel)};
            image.write(rgb, sizeof(rgb));
        }
    }
    std::printf("PASS 256x240 @ %.5f fps, 48000 Hz, %zu audio samples (%s), state %zu bytes, frame %016llx\n",
                nes::fps(), audioSamples, audible ? "audible" : "silent ROM", state.size(), static_cast<unsigned long long>(hash));
    nes::reset();
    nes::unload();
    // Verify the core can release and load the same ROM in one process.
    error = nes::load(argv[1], "/tmp", "/tmp");
    if (!error.empty()) { std::fprintf(stderr, "Reload failed: %s\n", error.c_str()); return 1; }
    nes::runFrame(0);
    nes::unload();
    return 0;
}
