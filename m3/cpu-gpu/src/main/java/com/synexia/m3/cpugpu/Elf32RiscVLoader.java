/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

/**
 * Minimal deterministic ELF32 little-endian RISC-V ET_EXEC loader.
 * It maps PT_LOAD segments into a core's flat RAM and does not implement an MMU,
 * dynamic linking, relocations, TLS, or privileged boot state.
 */
public final class Elf32RiscVLoader {
    private static final int ELF_HEADER_SIZE = 52;
    private static final int PROGRAM_HEADER_SIZE = 32;
    private static final int ET_EXEC = 2;
    private static final int EM_RISCV = 243;
    private static final int PT_LOAD = 1;

    private Elf32RiscVLoader() {
    }

    public static int load(byte[] elf, Rv32iBatch batch, int core) {
        if (elf.length < ELF_HEADER_SIZE) {
            throw new IllegalArgumentException("ELF header is truncated");
        }
        if ((elf[0] & 0xFF) != 0x7F || elf[1] != 'E' || elf[2] != 'L' || elf[3] != 'F') {
            throw new IllegalArgumentException("not an ELF image");
        }
        if ((elf[4] & 0xFF) != 1) {
            throw new IllegalArgumentException("only ELFCLASS32 is supported");
        }
        if ((elf[5] & 0xFF) != 1) {
            throw new IllegalArgumentException("only little-endian ELF is supported");
        }
        if ((elf[6] & 0xFF) != 1) {
            throw new IllegalArgumentException("unsupported ELF identification version");
        }
        if (u16(elf, 16) != ET_EXEC) {
            throw new IllegalArgumentException("only static ET_EXEC images are supported");
        }
        if (u16(elf, 18) != EM_RISCV) {
            throw new IllegalArgumentException("ELF machine is not RISC-V");
        }
        if (u32(elf, 20) != 1) {
            throw new IllegalArgumentException("unsupported ELF version");
        }

        long entry = u32(elf, 24);
        long programHeaderOffset = u32(elf, 28);
        int programHeaderEntrySize = u16(elf, 42);
        int programHeaderCount = u16(elf, 44);
        if (programHeaderEntrySize < PROGRAM_HEADER_SIZE) {
            throw new IllegalArgumentException("program header entry is too small");
        }

        long programHeaderBytes = Math.multiplyExact((long) programHeaderEntrySize, programHeaderCount);
        requireFileRange(elf.length, programHeaderOffset, programHeaderBytes, "program header table");

        for (int i = 0; i < programHeaderCount; i++) {
            int offset = Math.toIntExact(programHeaderOffset + (long) i * programHeaderEntrySize);
            if (u32(elf, offset) != PT_LOAD) {
                continue;
            }
            long fileOffset = u32(elf, offset + 4);
            long virtualAddress = u32(elf, offset + 8);
            long physicalAddress = u32(elf, offset + 12);
            long fileSize = u32(elf, offset + 16);
            long memorySize = u32(elf, offset + 20);
            if (memorySize < fileSize) {
                throw new IllegalArgumentException("PT_LOAD memory size is smaller than file size");
            }
            requireFileRange(elf.length, fileOffset, fileSize, "PT_LOAD payload");

            long loadAddress = physicalAddress != 0 ? physicalAddress : virtualAddress;
            requireGuestRange(batch.memoryBytesPerCore(), loadAddress, memorySize);

            for (long j = 0; j < fileSize; j++) {
                batch.writeByte(core, Math.toIntExact(loadAddress + j),
                        elf[Math.toIntExact(fileOffset + j)] & 0xFF);
            }
            for (long j = fileSize; j < memorySize; j++) {
                batch.writeByte(core, Math.toIntExact(loadAddress + j), 0);
            }
        }

        if (entry > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("entry point is outside flat signed address space");
        }
        batch.setProgramCounter(core, (int) entry);
        return (int) entry;
    }

    private static int u16(byte[] data, int offset) {
        requireFileRange(data.length, offset, 2, "u16");
        return (data[offset] & 0xFF) | (data[offset + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] data, int offset) {
        requireFileRange(data.length, offset, 4, "u32");
        int value = (data[offset] & 0xFF)
                | (data[offset + 1] & 0xFF) << 8
                | (data[offset + 2] & 0xFF) << 16
                | (data[offset + 3] & 0xFF) << 24;
        return Integer.toUnsignedLong(value);
    }

    private static void requireFileRange(long fileLength, long offset, long length, String name) {
        if (offset < 0 || length < 0 || offset > fileLength || length > fileLength - offset) {
            throw new IllegalArgumentException(name + " is outside the ELF image");
        }
    }

    private static void requireGuestRange(long memoryLength, long offset, long length) {
        if (offset < 0 || length < 0 || offset > memoryLength || length > memoryLength - offset) {
            throw new IllegalArgumentException("PT_LOAD segment is outside virtual core RAM");
        }
    }
}
