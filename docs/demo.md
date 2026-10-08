# Star Garden

The included `demo.nes` is an original NES homebrew game made for this app. Its
6502 program, pixel graphics, letterforms, and sounds were authored here; it
contains no commercial game content. The game and its generator are dedicated
to the public domain under CC0 1.0.

Move the mint explorer around the garden and collect gold stars. Each collected
star adds one to the score, plays a chirp, and moves the star to its next spot.
The score wraps after 99; there is no time limit.

| Control | Action |
| --- | --- |
| D-pad | Move in four directions |
| A, held | Move three times faster |
| B | Play a chirp, useful for checking audio |
| Start | Reset the demo and score |

Regenerate the cartridge with `python3 tools/generate_demo.py`. This needs only
Python's standard library. The generator includes a small label-resolving 6502
assembler, hand-drawn CHR tiles, the background nametable, and the complete game
logic. The resulting 24,592-byte iNES cartridge uses mapper 0 (NROM-128), a 16 KiB
mirrored PRG bank, and an 8 KiB CHR-ROM bank.

For emulator verification, load the demo, wait at least ten frames, move right
and up to collect the first star at (192,72), and check that the score becomes
01 and the next star appears at (48,160). Press B to check the sound, then Start
to verify the reset. The explorer begins at (120,112), moves one pixel per frame
(three with A), and is clamped inside the garden.

For a headless native smoke test, CPU RAM offsets 0/1 are explorer X/Y, 2/3 are
star X/Y, 7/8 are score ones/tens, and 9 is the current star index. These addresses
can verify real emulation, controller input, collection, and reset independently
of a rendered screenshot. The generator checks ROM size, NROM layout, branch
reach, duplicate labels, and the reset vector while assembling.

The game-specific headless verification is `tests/demo_smoke.py`. Pass a built
FCEUmm libretro shared library; an optional `--snapshot /tmp/star-garden.ppm`
writes the rendered startup screen. It checks movement, boost, collection,
score, audio output, all four movement limits, reset, and saved-state restoration
through the real emulator. It also verifies that the bundled cartridge exactly
matches the generator's output. For example, on macOS after building the app's
host native target in `/tmp/nes-native-host`:

```sh
clang -dynamiclib -Wl,-all_load /tmp/nes-native-host/libfceumm.a -lz -lm -o /tmp/nes-demo-test.dylib
python3 tests/demo_smoke.py /tmp/nes-demo-test.dylib --snapshot /tmp/star-garden.ppm
```

This verification passed with 256 by 240 video frames on the bundled core.
