# Native frontend

The `nescore` Android library links the pinned FCEUmm source in
`third_party/fceumm`. The CMake source list mirrors upstream
`Makefile.common`, without optional NTSC or HD texture filters. JNI binds
the static methods in `com.culpen.nes.NativeNes`. All calls must run on the
same serialized emulation thread.

Video is an opaque ARGB integer array, 256 × 240 without overscan cropping.
The core negotiates RGB565; the frontend also handles XRGB8888 and 0RGB1555.
Audio is 48,000 Hz interleaved signed 16-bit stereo. `drainAudio` returns the
number of shorts copied, so divide by two for stereo frames. The bounded
PCM queue drops oldest complete stereo frames on overflow and clears on
ROM load/unload, reset, and successful state restoration.

State files use the app-specific `CNESST01` envelope: raw payload size,
core-state version, ROM MD5 identity, payload MD5 checksum, and pinned core
identity precede the libretro state. The hashes detect accidental corruption
and wrong cartridges; they are not authentication. Foreign, truncated,
wrong-build, or corrupt states are rejected before core deserialization.
Raw chunk sizes are checked, and a checkpoint restores the current state
if core deserialization fails. Raw RetroArch/FCEUmm state imports are not
supported. Battery RAM uses the raw bytes exposed by libretro
`RETRO_MEMORY_SAVE_RAM`, and incomplete buffers are ignored.

The pinned upstream core does not serialize its high-quality output
resampler's fractional `mrindex` or `WaveHi` FIR history. Save restoration
replays game/video state, but output PCM can differ by a stereo sample in
frame length and briefly in filter history. The application does not promise
byte-identical audio replay after restoration. See upstream `filter.c`
(`NeoFilterSound`) and `sound.c` (`FCEUSND_STATEINFO`).

The app exposes one player's gamepad and imports `.nes` cartridges, directly
or inside ZIPs. ROMs are limited to 32 MiB. The native core can recognize
UNIF and FDS images, but the app does not expose FDS BIOS setup, light guns,
multiplayer input, cheats, or HD texture packs. Compatibility is determined
by FCEUmm's supported mappers; the included original cartridge is the
functional validation fixture.

Host verification on a Mac with CMake and Ninja:

```sh
cmake -S app/src/main/cpp -B /tmp/nes-native-host -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build /tmp/nes-native-host -j 8
ctest --test-dir /tmp/nes-native-host --output-on-failure
/tmp/nes-native-host/nescore_smoke app/src/main/assets/demo.nes /tmp/nes-demo.ppm
```

The smoke runner checks video, audible PCM, exact video replay, and reload.
The regression runner creates actual 6502 iNES cartridges to verify 8 KiB
battery SRAM save/reload, APU output, malformed iNES/NES2 rejection,
NES2 exponent support, state validation and atomic rejection, and audio
queue clearing. Android builds use NDK `28.2.13676358` with 16 KiB ELF page
alignment and are built by the project's Gradle configuration.
