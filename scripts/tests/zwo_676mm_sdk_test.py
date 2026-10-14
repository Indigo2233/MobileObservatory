"""Exercise the frozen ARM64 SDK's real ASIOpenCamera -> constructor -> USB open.

Requires pyelftools and unicorn 2.1.3. OS, allocation, settings and USB access are
stubbed; this checks device selection, not hardware initialization or imaging.
Run with --baseline before the fix to reproduce the 676MM PID mismatch; pass
--helper with the built libusb_helper_jni.so to exercise the production installer.
"""

import argparse
import hashlib
import io
from pathlib import Path
import struct
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "build/zwo-test-deps"))
from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (
    UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2,
    UC_ARM64_REG_X3, UC_ARM64_REG_SP, UC_ARM64_REG_LR,
    UC_ARM64_REG_PC, UC_ARM64_REG_TPIDR_EL0,
)

SDK = ROOT / "app/src/main/jniLibs/arm64-v8a/libASICamera2.so"
SDK_SHA256 = "ef327f12863e56f48d7226a211ff9fd90a8b3a16ef71e26b0b2776c13478c988"
MM_CTOR = "_ZN17CCameraS676MM_DDRC1Ev"
USB_OPEN = "_Z37libusb_open_device_with_vid_pid_indexP14libusb_contexttth"
CTORS = {
    MM_CTOR,
    "_ZN17CCameraS676MC_DDRC1Ev",
    "_ZN17CCameraS715MC_DDRC1Ev",
    "_ZN18CCameraS220MM_MiniC1Ev",
}
REAL_FUNCTIONS = CTORS | {"ASIOpenCamera", "_ZN11CCameraBase10OpenCameraEi"}
INSTALLER = "Java_com_indigo_mobileobservatory_camera_UsbHelper_nativeInstallZwo676MmCompatibility"


class SdkExecution:
    SDK_BASE = 0x10000000
    PROBE_BASE = 0x20000000
    STACK = 0x30000000
    HEAP = 0x40000000
    STUBS = 0x50000000
    STOP = 0x60000000

    def __init__(self, helper=None, corrupt_build_id=False, corrupt_constructor=False):
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.uc.mem_map(self.STACK, 0x10000)
        self.uc.mem_map(self.HEAP, 0x100000)
        self.uc.mem_map(self.STUBS, 0x10000)
        self.uc.mem_map(self.STOP, 0x1000)
        self.uc.reg_write(UC_ARM64_REG_SP, self.STACK + 0xF000)
        self.uc.reg_write(UC_ARM64_REG_TPIDR_EL0, self.STACK)
        self.next_heap = self.HEAP
        self.stubs = {}
        self.stub_names = {}
        self.requested = None
        self.got = {}
        self.callback_returns = []
        self.symbols = self.load(SDK, self.SDK_BASE, sdk=True)
        if corrupt_build_id:
            self.uc.mem_write(self.SDK_BASE + 0x1D8, b"\xff")
        if corrupt_constructor:
            self.write32(self.symbols[MM_CTOR] + 0x198, 0x528CED88)
        self.uc.hook_add(UC_HOOK_CODE, self.step)
        self.helper_symbols = self.load(helper, self.PROBE_BASE, sdk=False) if helper else {}
        self.compat_ready = self.install() if helper else False

    def write32(self, address, value):
        self.uc.mem_write(address, struct.pack("<I", value))

    def write64(self, address, value):
        self.uc.mem_write(address, struct.pack("<Q", value))

    def stub(self, name):
        if name not in self.stub_names:
            address = self.STUBS + 4 * len(self.stub_names)
            self.stub_names[name] = address
            self.stubs[address] = name
        return self.stub_names[name]

    def load(self, path, base, sdk):
        binary = Path(path).read_bytes()
        if sdk and hashlib.sha256(binary).hexdigest() != SDK_SHA256:
            raise AssertionError("SDK differs from the frozen build; review the emulated ABI first")
        elf = ELFFile(io.BytesIO(binary))
        segments = [s for s in elf.iter_segments() if s["p_type"] == "PT_LOAD"]
        end = max(s["p_vaddr"] + s["p_memsz"] for s in segments)
        self.uc.mem_map(base, (end + 0xFFF) & ~0xFFF)
        for segment in segments:
            self.uc.mem_write(base + segment["p_vaddr"], segment.data())
        symbols = elf.get_section_by_name(".dynsym")
        if sdk:
            self.phdr = base + elf.header["e_phoff"]
            self.phnum = elf.header["e_phnum"]
        names = {s.name: base + s["st_value"] for s in symbols.iter_symbols()
                 if s["st_shndx"] != "SHN_UNDEF"}
        for section in elf.iter_sections():
            if section["sh_type"] != "SHT_RELA":
                continue
            for relocation in section.iter_relocations():
                kind = relocation["r_info_type"]
                target = base + relocation["r_offset"]
                addend = relocation["r_addend"]
                if kind == 1027:  # R_AARCH64_RELATIVE
                    self.write64(target, base + addend)
                    continue
                if kind not in (257, 1025, 1026):
                    raise AssertionError(f"Unexpected relocation {kind}")
                symbol = symbols.get_symbol(relocation["r_info_sym"])
                defined = symbol["st_shndx"] != "SHN_UNDEF"
                function = symbol["st_info"]["type"] == "STT_FUNC"
                execute = defined and (not sdk or not function or symbol.name in REAL_FUNCTIONS)
                value = base + symbol["st_value"] if execute else self.stub(symbol.name)
                self.write64(target, value + addend)
                if sdk and kind == 1026:
                    self.got[symbol.name] = target
        return names

    def cstring(self, address):
        result = bytearray()
        while True:
            byte = self.uc.mem_read(address, 1)
            if byte == b"\0":
                return bytes(result)
            result += byte
            address += 1

    def step(self, uc, address, size, data):
        if address not in self.stubs:
            return
        name = self.stubs[address]
        a = uc.reg_read(UC_ARM64_REG_X0)
        b = uc.reg_read(UC_ARM64_REG_X1)
        c = uc.reg_read(UC_ARM64_REG_X2)
        result = 0
        if name == USB_OPEN:
            self.requested = (b, c, uc.reg_read(UC_ARM64_REG_X3))
            uc.emu_stop()
            return
        if name == "__test_return_dl_iterate":
            resume = self.callback_returns.pop()
            uc.reg_write(UC_ARM64_REG_LR, resume)
            uc.reg_write(UC_ARM64_REG_PC, resume)
            return
        if name == "dlopen":
            result = 1
        elif name == "dlsym":
            result = self.symbols.get(self.cstring(b).decode(), 0)
        elif name == "dladdr":
            self.write64(b, self.HEAP + 0xF0000)
            self.write64(b + 8, self.SDK_BASE)
            self.write64(b + 16, 0)
            self.write64(b + 24, a)
            result = 1
        elif name == "dl_iterate_phdr":
            info = self.HEAP + 0xF0100
            filename = self.HEAP + 0xF0000
            uc.mem_write(filename, b"/app/lib/arm64/libASICamera2.so\0")
            self.write64(info, self.SDK_BASE)
            self.write64(info + 8, filename)
            self.write64(info + 16, self.phdr)
            uc.mem_write(info + 24, struct.pack("<H", self.phnum))
            self.callback_returns.append(uc.reg_read(UC_ARM64_REG_LR))
            uc.reg_write(UC_ARM64_REG_X0, info)
            uc.reg_write(UC_ARM64_REG_X1, 64)
            uc.reg_write(UC_ARM64_REG_X2, b)
            uc.reg_write(UC_ARM64_REG_LR, self.stub("__test_return_dl_iterate"))
            uc.reg_write(UC_ARM64_REG_PC, a)
            return
        elif name == "sysconf":
            result = 4096
        elif name in ("_Znwm", "_Znam", "malloc"):
            result = self.next_heap
            self.next_heap += (a + 15) & ~15
        elif name in ("memcpy", "memmove", "__memcpy_chk"):
            uc.mem_write(a, bytes(uc.mem_read(b, c)))
            result = a
        elif name == "memset":
            uc.mem_write(a, bytes([b & 0xFF]) * c)
            result = a
        elif name == "strcmp":
            left, right = self.cstring(a), self.cstring(b)
            result = (left > right) - (left < right)
        elif name == "memcmp":
            left, right = bytes(uc.mem_read(a, c)), bytes(uc.mem_read(b, c))
            result = (left > right) - (left < right)
        elif name == "strstr":
            offset = self.cstring(a).find(self.cstring(b))
            result = a + offset if offset >= 0 else 0
        elif name == "__stack_chk_fail":
            raise AssertionError("SDK stack canary failed")
        # Base construction, logs, vector setup, settings and color controls do
        # not participate in PID selection; those calls return without USB I/O.
        uc.reg_write(UC_ARM64_REG_X0, result & ((1 << 64) - 1))
        uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))

    def call(self, address):
        self.uc.reg_write(UC_ARM64_REG_SP, self.STACK + 0xF000)
        self.uc.reg_write(UC_ARM64_REG_LR, self.STOP)
        # Debug APKs use unoptimized native code; scanning all SDK relocations
        # takes substantially more instructions than the standalone -O2 build.
        self.uc.emu_start(address, self.STOP, count=500000)
        if self.uc.reg_read(UC_ARM64_REG_PC) != self.STOP and self.requested is None:
            raise AssertionError(f"Instruction budget exceeded at {self.uc.reg_read(UC_ARM64_REG_PC):#x}")
        return self.uc.reg_read(UC_ARM64_REG_X0)

    def install(self):
        return self.call(self.helper_symbols[INSTALLER]) == 1

    def open(self, pid, other_pid=None, camera_index=0):
        # Globals populated by ASIGetNumOfConnectedCameras for one Android USB
        # device. Its discovery path and stable camera slot must agree.
        base = self.SDK_BASE
        pids = [pid] if other_pid is None else [pid, other_pid]
        self.requested = None
        self.write32(base + 0x242030, len(pids))
        for index, device_pid in enumerate(pids):
            path = f"{index}\0".encode()
            self.uc.mem_write(base + 0x242438 + 512 * index, path)
            self.uc.mem_write(base + 0x316838 + 512 * index, path)
            self.write32(base + 0x252438 + 4 * index, device_pid)
            self.write64(base + 0x242038 + 8 * index, 0)
        self.uc.reg_write(UC_ARM64_REG_X0, camera_index)
        self.call(self.symbols["ASIOpenCamera"])
        if self.requested is None:
            raise AssertionError("SDK did not reach USB open")
        return self.requested


class Zwo676MmSdkTest(unittest.TestCase):
    helper = None

    def test_676mm_opens_its_own_usb_pid(self):
        self.assertEqual((0x03C3, 0x676C, 1), SdkExecution(self.helper).open(0x676C))

    def test_676mc_keeps_its_usb_pid(self):
        self.assertEqual((0x03C3, 0x676D, 1), SdkExecution(self.helper).open(0x676D))

    def test_715mc_keeps_its_usb_pid(self):
        self.assertEqual((0x03C3, 0x715D, 1), SdkExecution(self.helper).open(0x715D))

    def test_220mini_keeps_its_usb_pid(self):
        self.assertEqual((0x03C3, 0x2209, 1), SdkExecution(self.helper).open(0x2209))

    def test_installer_accepts_pinned_sdk_and_is_idempotent(self):
        if not self.helper:
            self.skipTest("Requires compiled helper")
        execution = SdkExecution(self.helper)
        self.assertTrue(execution.compat_ready)
        self.assertTrue(execution.install())
        for _ in range(2):
            self.assertEqual((0x03C3, 0x676C, 1), execution.open(0x676C))

    def test_unknown_sdk_build_is_rejected_without_replacing_constructor(self):
        if not self.helper:
            self.skipTest("Requires compiled helper")
        execution = SdkExecution(self.helper, corrupt_build_id=True)
        self.assertFalse(execution.compat_ready)
        self.assertEqual((0x03C3, 0x676D, 1), execution.open(0x676C))

    def test_changed_constructor_is_rejected(self):
        if not self.helper:
            self.skipTest("Requires compiled helper")
        execution = SdkExecution(self.helper, corrupt_constructor=True)
        self.assertFalse(execution.compat_ready)

    def test_mm_and_mc_use_distinct_pids_in_one_process(self):
        execution = SdkExecution(self.helper)
        self.assertEqual((0x03C3, 0x676C, 1), execution.open(0x676C, 0x676D, 0))
        self.assertEqual((0x03C3, 0x676D, 1), execution.open(0x676C, 0x676D, 1))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", action="store_true")
    parser.add_argument("--helper", type=Path)
    options = parser.parse_args()
    if not options.baseline and options.helper is None:
        parser.error("Use --baseline or --helper <built libusb_helper_jni.so>")
    Zwo676MmSdkTest.helper = options.helper
    unittest.main(argv=[sys.argv[0]], verbosity=2)
