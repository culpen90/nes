# Third-party source

`fceumm/` is a source snapshot of [libretro/libretro-fceumm](https://github.com/libretro/libretro-fceumm)
at commit `7a542dab1e87679921962a9f056186eca425c0c2`, retrieved on 2026-10-08.
It includes the upstream libretro-common source used by the core. No nested Git
repository or prebuilt binary is included, and the upstream source is unmodified.

FCEUmm is licensed under GNU GPL version 2 or later; see `fceumm/Copying` and the
copyright notices in its source files. Bundled support code retains its own
license notices. This application links FCEUmm into `libnescore.so`; its complete
corresponding source and build instructions are provided in this repository.

The app's CMake source list mirrors `fceumm/Makefile.common`, with optional NTSC
and HD texture filters omitted. These filters are disabled in this frontend.
