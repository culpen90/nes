// SPDX-License-Identifier: GPL-2.0-or-later
#include "nes_session.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>

extern "C" {
#include <md5.h>
}

namespace {
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
void writeFile(const std::string& path, const std::vector<uint8_t>& bytes) {
    std::ofstream file(path, std::ios::binary);
    file.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
    require(file.good(), "Writing the temporary test ROM failed.");
}
std::vector<uint8_t> testCartridge(bool battery) {
    std::vector<uint8_t> rom(16 + 16384 + 8192, 0);
    const uint8_t header[] = {'N', 'E', 'S', 0x1a, 1, 1, static_cast<uint8_t>(battery ? 2 : 0)};
    std::copy(std::begin(header), std::end(header), rom.begin());
    // A real 6502 cartridge writes both ends of its battery SRAM and starts
    // an APU pulse. Executing these instructions exercises the mapper/APU.
    const uint8_t program[] = {
        0x78, 0xd8,                   // SEI; CLD
        0xa9, 0x5a, 0x8d, 0x00, 0x60, // LDA #$5a; STA $6000
        0xa9, 0xa5, 0x8d, 0xff, 0x7f, // LDA #$a5; STA $7fff
        0xa9, 0x01, 0x8d, 0x15, 0x40, // Enable pulse channel 1
        0xa9, 0xbf, 0x8d, 0x00, 0x40, // Constant volume, length halt
        0xa9, 0xfd, 0x8d, 0x02, 0x40, // Frequency low
        0xa9, 0x08, 0x8d, 0x03, 0x40, // Frequency high, length reload
        0x4c, 0x20, 0x80              // JMP $8020
    };
    std::copy(std::begin(program), std::end(program), rom.begin() + 16);
    rom[16 + 0x40] = 0x40; // RTI
    const uint8_t vectors[] = {0x40, 0x80, 0x00, 0x80, 0x40, 0x80};
    std::copy(std::begin(vectors), std::end(vectors), rom.begin() + 16 + 16384 - 6);
    return rom;
}
void updatePayloadChecksum(std::vector<uint8_t>& state) {
    md5_context context{};
    md5_starts(&context);
    md5_update(&context, state.data() + 64, state.size() - 64);
    md5_finish(&context, state.data() + 32);
}
void rejectState(const std::vector<uint8_t>& malformed, const std::vector<uint8_t>& expected) {
    require(!nes::loadState(malformed.data(), malformed.size()), "A malformed state was accepted.");
    require(nes::saveState() == expected, "A rejected state changed emulation memory.");
}
}

int main() {
    const auto token = std::chrono::steady_clock::now().time_since_epoch().count();
    const auto directory = std::filesystem::temp_directory_path() /
                           ("nescore-regression-" + std::to_string(token));
    if (!std::filesystem::create_directory(directory)) return 1;
    const std::string dir = directory.string();
    try {
        const std::string batteryPath = dir + "/battery.nes";
        const std::string plainPath = dir + "/plain.nes";
        auto batteryRom = testCartridge(true);
        writeFile(batteryPath, batteryRom);
        writeFile(plainPath, testCartridge(false));
        require(nes::load(batteryPath, dir, dir).empty(), "The battery cartridge did not load.");
        std::array<int16_t, 16384> pcm{};
        require(nes::drainAudio(pcm.data(), pcm.size()) == 0, "A new ROM emitted stale audio.");
        for (int i = 0; i < 6; ++i) nes::runFrame(0);
        auto ram = nes::saveRam();
        require(ram.size() == 8192, "The battery mapper did not expose 8 KiB SRAM.");
        require(ram.front() == 0x5a && ram.back() == 0xa5, "6502 writes did not reach battery SRAM.");
        ram[128] = 0x19;
        ram[4096] = 0x37;
        nes::loadRam(ram.data(), ram.size());
        require(nes::saveRam() == ram, "SRAM replacement failed.");
        nes::loadRam(ram.data(), ram.size() - 1);
        require(nes::saveRam() == ram, "Partial SRAM was applied.");
        const size_t count = nes::drainAudio(pcm.data(), pcm.size());
        require(count > 0 && count % 2 == 0, "The APU did not produce stereo PCM.");
        require(std::any_of(pcm.begin(), pcm.begin() + count, [](int16_t sample) { return sample != 0; }),
                "The APU pulse did not produce audible samples.");
        require(nes::drainAudio(pcm.data(), pcm.size()) == 0, "The drained audio queue was not empty.");

        nes::unload();
        require(nes::drainAudio(pcm.data(), pcm.size()) == 0, "Unloading left old audio queued.");
        require(nes::load(batteryPath, dir, dir).empty(), "Reloading the battery cartridge failed.");
        nes::loadRam(ram.data(), ram.size());
        for (int i = 0; i < 3; ++i) nes::runFrame(0);
        require(nes::saveRam() == ram, "Battery SRAM did not survive unload/reload.");
        auto state = nes::saveState();
        require(!state.empty(), "The test cartridge could not save state.");
        require(nes::loadState(state.data(), state.size()), "The matching state was rejected.");
        require(nes::drainAudio(pcm.data(), pcm.size()) == 0, "Loading state left stale audio queued.");
        state = nes::saveState();
        auto bad = state;
        bad.back() ^= 1;
        rejectState(bad, state); // Payload corruption
        bad = state;
        bad[12] ^= 1;
        rejectState(bad, state); // Frontend version mismatch
        bad = state;
        bad[48] ^= 1;
        rejectState(bad, state); // Different core build
        bad = state;
        bad.resize(bad.size() - 1);
        rejectState(bad, state); // Truncated payload
        bad.assign(state.begin() + 64, state.end());
        rejectState(bad, state); // Raw external FCEUmm state
        bad = state;
        bad[64 + 8] ^= 1;
        updatePayloadChecksum(bad);
        rejectState(bad, state); // Core payload version mismatch
        bad = state;
        std::fill(bad.begin() + 64 + 4, bad.begin() + 64 + 8, 0);
        updatePayloadChecksum(bad);
        rejectState(bad, state); // Empty declared chunks
        bad = state;
        std::fill(bad.begin() + 64 + 17, bad.begin() + 64 + 21, 0xff);
        updatePayloadChecksum(bad);
        rejectState(bad, state); // Oversized first chunk
        bad = state;
        std::fill(bad.begin() + 64 + 25, bad.begin() + 64 + 29, 0xff);
        updatePayloadChecksum(bad);
        rejectState(bad, state); // Oversized field within chunk
        bad = state;
        bad[64 + 16] = 0x11;
        updatePayloadChecksum(bad);
        rejectState(bad, state); // Missing required CPU chunk

        auto otherRom = batteryRom;
        otherRom.back() = 1; // Different CHR ROM identity.
        const std::string otherPath = dir + "/other.nes";
        writeFile(otherPath, otherRom);
        require(nes::load(otherPath, dir, dir).empty(), "The second cartridge did not load.");
        const auto otherState = nes::saveState();
        rejectState(state, otherState); // Same mapper/size, different ROM

        const std::string invalidPath = dir + "/invalid.nes";
        const auto rejectRom = [&](const std::vector<uint8_t>& bytes) {
            writeFile(invalidPath, bytes);
            const auto checkpoint = nes::saveState();
            require(!nes::load(invalidPath, dir, dir).empty(), "An incomplete or malformed ROM was accepted.");
            require(nes::saveState() == checkpoint, "Rejected ROM validation replaced the current session.");
        };
        rejectRom(std::vector<uint8_t>(8, 0));
        rejectRom(std::vector<uint8_t>(32, 0));
        auto incomplete = batteryRom;
        incomplete.resize(incomplete.size() - 1);
        rejectRom(incomplete);
        auto zeroPrg = batteryRom;
        zeroPrg[4] = 0;
        rejectRom(zeroPrg);
        auto missingTrainer = batteryRom;
        missingTrainer[6] |= 4;
        rejectRom(missingTrainer);
        auto nes2 = batteryRom;
        nes2[7] = 8;
        nes2.resize(nes2.size() - 1);
        rejectRom(nes2);
        auto hugeNes2 = batteryRom;
        hugeNes2[7] = 8;
        hugeNes2[4] = 0xfc;
        hugeNes2[9] = 0x0f;
        rejectRom(hugeNes2);

        auto exponentialNes2 = testCartridge(false);
        exponentialNes2[7] = 8;
        exponentialNes2[4] = 14 << 2; // 2^14 * 1 bytes of PRG.
        exponentialNes2[9] = 0x0f;
        writeFile(invalidPath, exponentialNes2);
        require(nes::load(invalidPath, dir, dir).empty(), "A valid NES2 exponent ROM was rejected.");
        nes::runFrame(0);
        require(nes::saveRam().empty(), "A non-battery cartridge exposed persistent SRAM.");
        nes::reset();
        require(nes::drainAudio(pcm.data(), pcm.size()) == 0, "Reset left stale audio queued.");
        nes::unload();
        std::filesystem::remove_all(dir);
        std::cout << "PASS battery SRAM 8 KiB, ROM validation, wrong/corrupt/truncated states, atomic rejection, audio clearing\n";
        return 0;
    } catch (const std::exception& error) {
        nes::unload();
        std::cerr << "FAIL " << error.what() << "\nArtifacts: " << dir << '\n';
        return 1;
    }
}
