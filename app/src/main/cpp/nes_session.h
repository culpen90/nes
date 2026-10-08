#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

// One emulation instance; callers must serialize all calls on one session thread.
namespace nes {
std::string load(const std::string& romPath, const std::string& systemDir,
                 const std::string& saveDir);
void unload();
void reset();
double fps();
int sampleRate();
int width();
int height();
void runFrame(uint16_t buttons);
bool copyVideo(int32_t* destination, size_t capacity);
size_t drainAudio(int16_t* destination, size_t capacity);
std::vector<uint8_t> saveState();
bool loadState(const uint8_t* data, size_t size);
std::vector<uint8_t> saveRam();
void loadRam(const uint8_t* data, size_t size);
}
