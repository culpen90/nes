#!/usr/bin/env python3
"""Exercise the original demo through a real libretro NES core.

Usage: python3 tests/demo_smoke.py /path/to/fceumm_libretro.dylib
Optional: --snapshot /tmp/star-garden.ppm
Only the Python standard library and a built libretro core are required.
"""

import argparse
import ctypes as C
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from generate_demo import cartridge


class GameInfo(C.Structure):
    _fields_ = [("path",C.c_char_p), ("data",C.c_void_p),
                ("size",C.c_size_t), ("meta",C.c_char_p)]


ENV = C.CFUNCTYPE(C.c_bool,C.c_uint,C.c_void_p)
VIDEO = C.CFUNCTYPE(None,C.c_void_p,C.c_uint,C.c_uint,C.c_size_t)
AUDIO = C.CFUNCTYPE(None,C.c_int16,C.c_int16)
BATCH = C.CFUNCTYPE(C.c_size_t,C.POINTER(C.c_int16),C.c_size_t)
POLL = C.CFUNCTYPE(None)
INPUT = C.CFUNCTYPE(C.c_int16,C.c_uint,C.c_uint,C.c_uint,C.c_uint)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("core", type=Path)
    parser.add_argument("--snapshot",type=Path)
    args = parser.parse_args()
    core = C.CDLL(str(args.core.resolve()))
    rom_path = ROOT / "app/src/main/assets/demo.nes"
    rom = rom_path.read_bytes()
    assert rom == cartridge(), "Bundled demo differs from generated source"
    rom_buffer = C.create_string_buffer(rom)
    directory = str(ROOT).encode()
    state = {"buttons":0,"format":0,"frame":None,"audio":[]}

    @ENV
    def environment(command,data):
        command &= 0xFFFF  # Strip the libretro experimental bit.
        if command == 10:
            state["format"] = C.cast(data,C.POINTER(C.c_uint))[0]
            return state["format"] in (0,1,2)
        if command in (9,31):
            C.cast(data,C.POINTER(C.c_char_p))[0] = directory
            return True
        if command in (2,3):
            C.cast(data,C.POINTER(C.c_bool))[0] = command == 3
            return True
        if command == 47:
            C.cast(data,C.POINTER(C.c_uint))[0] = 3
            return True
        return False

    @VIDEO
    def video(data,width,height,pitch):
        if data:
            state["frame"] = (width,height,pitch,C.string_at(data,pitch*height))

    @AUDIO
    def audio(left,right):
        state["audio"].extend((left,right))

    @BATCH
    def batch(data,frames):
        state["audio"].extend(data[:frames*2])
        return frames

    @POLL
    def poll():
        pass

    @INPUT
    def input_state(port,device,index,button):
        if port != 0 or device != 1:
            return 0
        if button == 256:
            return state["buttons"]
        return 1 if state["buttons"] & (1 << button) else 0

    for name,callback,kind in [
        ("retro_set_environment",environment,ENV),
        ("retro_set_video_refresh",video,VIDEO),
        ("retro_set_audio_sample",audio,AUDIO),
        ("retro_set_audio_sample_batch",batch,BATCH),
        ("retro_set_input_poll",poll,POLL),
        ("retro_set_input_state",input_state,INPUT),
    ]:
        function = getattr(core,name)
        function.argtypes = [kind]
        function(callback)
    core.retro_load_game.argtypes = [C.POINTER(GameInfo)]
    core.retro_load_game.restype = C.c_bool
    core.retro_get_memory_data.argtypes = [C.c_uint]
    core.retro_get_memory_data.restype = C.c_void_p
    core.retro_get_memory_size.argtypes = [C.c_uint]
    core.retro_get_memory_size.restype = C.c_size_t
    core.retro_serialize_size.restype = C.c_size_t
    for name in ("retro_serialize","retro_unserialize"):
        function = getattr(core,name)
        function.argtypes = [C.c_void_p,C.c_size_t]
        function.restype = C.c_bool
    core.retro_init()
    info = GameInfo(str(rom_path).encode(),C.cast(rom_buffer,C.c_void_p),len(rom),None)
    assert core.retro_load_game(C.byref(info)), "Core rejected the original demo"
    try:
        assert core.retro_get_memory_size(2) >= 2048
        ram = (C.c_uint8*2048).from_address(core.retro_get_memory_data(2))
        def step(frames,buttons=0):
            state["buttons"] = buttons
            for _ in range(frames):
                core.retro_run()
        # libretro joypad IDs: B=0, Start=3, Up=4, Down=5, Left=6, Right=7, A=8.
        B,START,UP,DOWN,LEFT,RIGHT,A = [1<<bit for bit in (0,3,4,5,6,7,8)]
        step(15)
        assert list(ram[:4]) == [120,112,192,72], f"Startup RAM: {list(ram[:12])}"
        assert state["frame"] is not None, "Core did not render a video frame"
        width,height,pitch,pixels = state["frame"]
        bytes_per_pixel = 4 if state["format"] == 1 else 2
        unique = {pixels[i:i+bytes_per_pixel] for i in range(0,len(pixels),bytes_per_pixel)}
        assert width == 256 and 224 <= height <= 240 and len(unique) >= 6
        if args.snapshot:
            rgb = bytearray()
            for y in range(height):
                for x in range(width):
                    start = y*pitch + x*bytes_per_pixel
                    value = int.from_bytes(pixels[start:start+bytes_per_pixel],sys.byteorder)
                    if state["format"] == 1:
                        rgb.extend(((value>>16)&255,(value>>8)&255,value&255))
                    elif state["format"] == 2:
                        rgb.extend(((value>>11)*255//31,((value>>5)&63)*255//63,(value&31)*255//31))
                    else:
                        rgb.extend((((value>>10)&31)*255//31,((value>>5)&31)*255//31,(value&31)*255//31))
            args.snapshot.write_bytes(f"P6\n{width} {height}\n255\n".encode()+rgb)
        step(10,RIGHT)
        assert ram[0] == 130, f"D-pad did not move one pixel/frame: {ram[0]}"
        step(10,RIGHT|A)
        assert ram[0] == 160, f"A boost did not move three pixels/frame: {ram[0]}"
        step(11,RIGHT|A)
        step(14,UP|A)
        assert ram[7] == 1 and ram[9] == 1 and list(ram[2:4]) == [48,160], f"Collection failed: {list(ram[:12])}"
        state["audio"].clear()
        step(12,B)
        assert state["audio"] and max(state["audio"])-min(state["audio"]) > 64, "Chirp produced no audible signal"
        step(1,START); step(1)
        assert list(ram[:4]) == [120,112,192,72] and ram[7] == ram[8] == ram[9] == 0
        step(200,UP|A); assert ram[1] == 56
        step(200,LEFT|A); assert ram[0] == 24
        step(200,DOWN|A); assert ram[1] == 176
        step(200,RIGHT|A); assert ram[0] == 216
        # Saving after reset and restoring after movement verifies actual game state.
        step(1,START); step(1)
        saved = C.create_string_buffer(core.retro_serialize_size())
        assert core.retro_serialize(saved,len(saved))
        before = bytes(ram[:12])
        step(12,LEFT)
        assert ram[0] == 108
        assert core.retro_unserialize(saved,len(saved))
        assert bytes(ram[:12]) == before
        step(12,LEFT)
        assert ram[0] == 108
        print(f"PASS: {width}x{height} video, D-pad, A boost, collection/score, audio chirp, four boundaries, Start reset, save/restore.")
    finally:
        core.retro_unload_game()
        core.retro_deinit()


if __name__ == "__main__":
    main()
