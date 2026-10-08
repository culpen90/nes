#!/usr/bin/env python3
"""Assemble Star Garden, an original, freely distributable NES demo cartridge.

Requires only Python 3. The deliberately small assembler implements exactly the
6502 instructions this game uses. No downloaded code, fonts, graphics or ROMs.
Run from any directory: python3 tools/generate_demo.py
"""

from pathlib import Path
import hashlib


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/assets/demo.nes"

# The keys are mnemonic/addressing-mode pairs. zp = zero page; ax = absolute,X.
OPS = {
    "SEI": 0x78, "CLD": 0xD8, "TXS": 0x9A, "TXA": 0x8A,
    "TYA": 0x98, "TAX": 0xAA, "TAY": 0xA8, "PHA": 0x48,
    "PLA": 0x68, "INX": 0xE8, "DEX": 0xCA, "RTS": 0x60,
    "RTI": 0x40, "CLC": 0x18, "SEC": 0x38, "LSR": 0x4A,
    "LDA.i": 0xA9, "LDA.z": 0xA5, "LDA.a": 0xAD, "LDA.ax": 0xBD,
    "LDX.i": 0xA2, "LDX.z": 0xA6, "STA.z": 0x85, "STA.a": 0x8D,
    "STA.ax": 0x9D, "INC.z": 0xE6, "DEC.z": 0xC6, "ROL.z": 0x26,
    "BIT.a": 0x2C, "AND.i": 0x29, "CMP.i": 0xC9, "CMP.z": 0xC5,
    "ADC.i": 0x69, "ADC.z": 0x65, "SBC.i": 0xE9, "SBC.z": 0xE5,
    "JSR.a": 0x20, "JMP.a": 0x4C,
    "BNE.r": 0xD0, "BEQ.r": 0xF0, "BPL.r": 0x10,
    "BCC.r": 0x90, "BCS.r": 0xB0,
}


class Assembler:
    """One-pass emission plus resolved absolute and relative label fixups."""

    def __init__(self, origin=0x8000):
        self.origin = origin
        self.code = bytearray()
        self.labels = {}
        self.fixups = []

    def label(self, name):
        assert name not in self.labels, f"Duplicate label: {name}"
        self.labels[name] = self.origin + len(self.code)

    def op(self, instruction, operand=None):
        self.code.append(OPS[instruction])
        if operand is None:
            return
        mode = instruction.split(".")[1]
        size = 2 if mode in ("a", "ax") else 1
        offset = len(self.code)
        if isinstance(operand, str):
            self.fixups.append((offset, operand, mode))
            self.code.extend(bytes(size))
        else:
            self.code.extend(int(operand).to_bytes(size, "little"))

    def data(self, values):
        self.code.extend(values)

    def resolve(self):
        for offset, expression, mode in self.fixups:
            name, _, delta = expression.partition("+")
            value = self.labels[name] + (int(delta) if delta else 0)
            if mode == "r":
                displacement = value - (self.origin + offset + 1)
                assert -128 <= displacement <= 127, f"Branch too far: {name}"
                self.code[offset] = displacement & 0xFF
            else:
                self.code[offset:offset + 2] = value.to_bytes(2, "little")
        return self.code


# Original 5 by 7 pixel letterforms, centered inside 8 by 8 NES tiles.
FONT = {
    "A": [14,17,17,31,17,17,17], "B": [30,17,17,30,17,17,30],
    "C": [14,17,16,16,16,17,14], "D": [30,17,17,17,17,17,30],
    "E": [31,16,16,30,16,16,31], "F": [31,16,16,30,16,16,16],
    "G": [14,17,16,23,17,17,15], "H": [17,17,17,31,17,17,17],
    "I": [14,4,4,4,4,4,14], "J": [7,2,2,2,18,18,12],
    "K": [17,18,20,24,20,18,17], "L": [16,16,16,16,16,16,31],
    "M": [17,27,21,21,17,17,17], "N": [17,25,21,19,17,17,17],
    "O": [14,17,17,17,17,17,14], "P": [30,17,17,30,16,16,16],
    "Q": [14,17,17,17,21,18,13], "R": [30,17,17,30,20,18,17],
    "S": [15,16,16,14,1,1,30], "T": [31,4,4,4,4,4,4],
    "U": [17,17,17,17,17,17,14], "V": [17,17,17,17,17,10,4],
    "W": [17,17,17,21,21,27,17], "X": [17,17,10,4,10,17,17],
    "Y": [17,17,10,4,4,4,4], "Z": [31,1,2,4,8,16,31],
    "0": [14,17,19,21,25,17,14], "1": [4,12,4,4,4,4,14],
    "2": [14,17,1,2,4,8,31], "3": [30,1,1,14,1,1,30],
    "4": [2,6,10,18,31,2,2], "5": [31,16,16,30,1,1,30],
    "6": [14,16,16,30,17,17,14], "7": [31,1,2,4,8,8,8],
    "8": [14,17,17,14,17,17,14], "9": [14,17,17,15,1,1,14],
}


def tile(rows):
    """Convert eight rows of color indices (0..3) into NES planar graphics."""
    assert len(rows) == 8 and all(len(row) == 8 for row in rows)
    low, high = [], []
    for row in rows:
        low.append(sum((int(c) & 1) << (7-x) for x,c in enumerate(row)))
        high.append(sum((int(c) >> 1) << (7-x) for x,c in enumerate(row)))
    return bytes(low + high)


def graphics():
    chr_data = bytearray(8192)
    def put(index, rows):
        chr_data[index*16:(index+1)*16] = tile(rows)
    for ch, rows in FONT.items():
        pixels = ["0" + "".join("3" if row & (1 << (4-x)) else "0" for x in range(5)) + "00" for row in rows]
        put(ord(ch), pixels + ["00000000"])
    put(1, ["00000000","22222222","21111112","21000012","21000012","21111112","22222222","00000000"])
    put(2, ["00000000","00000000","00010000","00000000","00000000","00000000","00000000","00000000"])
    put(3, ["00000000","00000000","00002000","00202000","00022000","00020000","00020000","00000000"])
    put(4, ["00000000","00300000","03030000","00300000","00020000","00222000","00020000","00000000"])
    # A mint explorer with a white helmet, blue visor and chunky boots.
    put(128, ["00000333","00033222","00332222","03323333","03223333","03223333","03222222","00322222"])
    put(129, ["33300000","22233000","22223300","33332330","33332230","33332230","22222230","22222300"])
    put(130, ["00033333","00322222","03223333","03333333","00333333","00033300","00333300","00333300"])
    put(131, ["33333000","22222300","33332230","33333330","33333300","00333000","00333300","00333300"])
    put(132, ["00020000","00020000","02222200","00232000","02232200","02000200","00000000","00000000"])
    return chr_data


def background():
    nt = bytearray(1024)
    def text(row, col, message):
        for x, ch in enumerate(message):
            nt[row*32 + col+x] = ord(ch) if ch != " " else 0
    text(2, 10, "STAR GARDEN")
    text(4, 12, "SCORE 00")
    for y in range(6, 25):
        for x in range(2, 30):
            nt[y*32+x] = 1 if y in (6,24) or x in (2,29) else (2 if (x*7+y*3)%13 == 0 else 0)
    for x,y in [(5,9),(7,18),(25,9),(23,19),(14,14),(20,16)]:
        nt[y*32+x] = 3
    for x,y in [(6,10),(24,10),(8,19),(22,20)]:
        nt[y*32+x] = 4
    text(26, 3, "DPAD MOVE  A BOOST  B CHIRP")
    text(28, 4, "COLLECT STARS  START RESET")
    return nt


def cartridge():
    a = Assembler()
    op, label = a.op, a.label
    # Stable RAM layout also enables meaningful native-core smoke tests.
    PX,PY,SX,SY,BUTTONS,PREVIOUS,FRAME,ONES,TENS,STAR,SPEED,SOUND = range(12)
    def lda(value): op("LDA.i", value)
    def sta(address): op("STA.a", address)
    def store(value, address): lda(value); sta(address)
    def set_z(value, address): lda(value); op("STA.z", address)

    label("reset")
    op("SEI"); op("CLD"); op("LDX.i", 0xFF); op("TXS")
    store(0x40, 0x4017)
    lda(0)
    for address in (0x2000,0x2001,0x4010,0x4015): sta(address)
    label("first_vblank"); op("BIT.a",0x2002); op("BPL.r","first_vblank")
    op("LDX.i",0); lda(0)
    label("clear_ram")
    for address in (0,0x100,0x300,0x400,0x500,0x600,0x700): op("STA.ax",address)
    lda(0xFF); op("STA.ax",0x200); lda(0); op("INX"); op("BNE.r","clear_ram")
    label("second_vblank"); op("BIT.a",0x2002); op("BPL.r","second_vblank")
    store(0x3F,0x2006); store(0,0x2006); op("LDX.i",0)
    label("palette_loop"); op("LDA.ax","palette"); sta(0x2007); op("INX")
    op("TXA"); op("CMP.i",32); op("BNE.r","palette_loop")
    store(0x20,0x2006); store(0,0x2006)
    for page in range(4):
        op("LDX.i",0); label(f"background_{page}")
        op("LDA.ax",f"background+{page*256}"); sta(0x2007)
        op("INX"); op("BNE.r",f"background_{page}")
    op("JSR.a","new_game"); op("JSR.a","sprites")
    store(0,0x2003); store(2,0x4014)
    store(0,0x2005); store(0,0x2005)
    store(0x80,0x2000); store(0x1E,0x2001)
    label("idle"); op("JMP.a","idle")

    label("new_game")
    set_z(120,PX); set_z(112,PY)
    lda(0)
    for address in (ONES,TENS,STAR,SOUND): op("STA.z",address)
    sta(0x4015); op("JSR.a","next_star"); op("RTS")
    label("next_star")
    op("LDX.z",STAR); op("LDA.ax","star_x"); op("STA.z",SX)
    op("LDA.ax","star_y"); op("STA.z",SY); op("RTS")

    label("controller")
    store(1,0x4016); store(0,0x4016); op("STA.z",BUTTONS); op("LDX.i",8)
    label("read_button"); op("LDA.a",0x4016); op("LSR"); op("ROL.z",BUTTONS)
    op("DEX"); op("BNE.r","read_button"); op("RTS")

    label("game")
    # Edge-trigger Start; keeping it held never blocks movement.
    op("LDA.z",BUTTONS); op("AND.i",0x10); op("BEQ.r","check_chirp")
    op("LDA.z",PREVIOUS); op("AND.i",0x10); op("BNE.r","check_chirp")
    op("JSR.a","new_game")
    label("check_chirp")
    op("LDA.z",BUTTONS); op("AND.i",0x40); op("BEQ.r","speed")
    op("LDA.z",PREVIOUS); op("AND.i",0x40); op("BNE.r","speed")
    op("JSR.a","chirp")
    label("speed")
    set_z(1,SPEED); op("LDA.z",BUTTONS); op("AND.i",0x80); op("BEQ.r","move_up")
    set_z(3,SPEED)
    for name,mask,position,bound,subtract in [
        ("up",8,PY,56,True), ("down",4,PY,176,False),
        ("left",2,PX,24,True), ("right",1,PX,216,False),
    ]:
        label(f"move_{name}")
        op("LDA.z",BUTTONS); op("AND.i",mask); op("BEQ.r",f"after_{name}")
        op("LDA.z",position); op("SEC" if subtract else "CLC")
        op("SBC.z" if subtract else "ADC.z",SPEED)
        op("CMP.i",bound if subtract else bound+1)
        op("BCS.r" if subtract else "BCC.r",f"store_{name}")
        lda(bound); label(f"store_{name}"); op("STA.z",position)
        label(f"after_{name}")
    # Axis-aligned overlap between the 16 by 16 explorer and 8 by 8 star.
    for position,target,width in [(PX,SX,15),(SX,PX,7),(PY,SY,15),(SY,PY,7)]:
        op("LDA.z",position); op("CLC"); op("ADC.i",width)
        op("CMP.z",target); op("BCC.r","game_done")
    op("INC.z",ONES); op("LDA.z",ONES); op("CMP.i",10); op("BCC.r","advance_star")
    set_z(0,ONES); op("INC.z",TENS); op("LDA.z",TENS); op("CMP.i",10)
    op("BCC.r","advance_star"); set_z(0,TENS)
    label("advance_star")
    op("INC.z",STAR); op("LDA.z",STAR); op("AND.i",7); op("STA.z",STAR)
    op("JSR.a","next_star"); op("JSR.a","chirp")
    label("game_done"); op("LDA.z",BUTTONS); op("STA.z",PREVIOUS); op("RTS")

    label("chirp")
    store(1,0x4015); store(0x9B,0x4000); store(0x08,0x4001)
    store(0x7F,0x4002); store(0,0x4003); set_z(8,SOUND); op("RTS")

    label("sprites")
    # OAM Y is one scanline before the pixel's actual display position.
    for offset,tile_id,dx,dy in [(0,128,0,0),(4,129,8,0),(8,130,0,8),(12,131,8,8)]:
        op("LDA.z",PY); op("CLC"); op("ADC.i",dy-1 if dy else 255); sta(0x200+offset)
        store(tile_id,0x201+offset); store(0,0x202+offset)
        op("LDA.z",PX); op("CLC"); op("ADC.i",dx); sta(0x203+offset)
    op("LDA.z",SY); op("SEC"); op("SBC.i",1); sta(0x210)
    store(132,0x211); store(1,0x212); op("LDA.z",SX); sta(0x213); op("RTS")

    label("nmi")
    op("PHA"); op("TXA"); op("PHA"); op("TYA"); op("PHA")
    op("INC.z",FRAME); op("JSR.a","controller"); op("JSR.a","game")
    op("LDA.z",SOUND); op("BEQ.r","no_sound")
    op("DEC.z",SOUND); op("BNE.r","no_sound"); store(0,0x4015)
    label("no_sound"); op("JSR.a","sprites")
    store(0,0x2003); store(2,0x4014)
    op("BIT.a",0x2002); store(0x20,0x2006); store(0x92,0x2006)
    op("LDA.z",TENS); op("CLC"); op("ADC.i",ord("0")); sta(0x2007)
    op("LDA.z",ONES); op("CLC"); op("ADC.i",ord("0")); sta(0x2007)
    store(0,0x2005); store(0,0x2005)
    op("PLA"); op("TAY"); op("PLA"); op("TAX"); op("PLA"); op("RTI")
    label("irq"); op("RTI")
    label("star_x"); a.data([192,48,192,48,120,80,160,120])
    label("star_y"); a.data([72,160,160,72,56,112,112,168])
    label("palette")
    a.data([0x0F,0x12,0x2A,0x30]*4 + [0x0F,0x12,0x2A,0x30, 0x0F,0x17,0x28,0x38] + [0x0F,0x12,0x2A,0x30]*2)
    label("background"); a.data(background())
    code = a.resolve()
    assert len(code) < 16378, "PRG must fit NROM-128 before interrupt vectors"
    prg = code + bytes([0xEA])*(16378-len(code))
    for vector in ("nmi","reset","irq"):
        prg += a.labels[vector].to_bytes(2,"little")
    header = b"NES\x1A" + bytes([1,1,0,0]) + bytes(8)
    rom = header + prg + graphics()
    assert len(rom) == 24592
    assert int.from_bytes(rom[16+16380:16+16382],"little") == 0x8000
    return rom


if __name__ == "__main__":
    rom = cartridge()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(rom)
    print(f"Wrote {OUTPUT} ({len(rom)} bytes)")
    print(f"SHA-256: {hashlib.sha256(rom).hexdigest()}")
