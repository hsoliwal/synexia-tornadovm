/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import java.util.Arrays;
import java.util.Objects;

/**
 * Minimal ELF32 little-endian RISC-V loader for bare-metal RV32 images.
 *
 * <p>Only PT_LOAD segments affect machine memory. Segment virtual/physical addresses are treated
 * as flat addresses in the per-core RAM image; when p_paddr is zero, p_vaddr is used. BSS bytes
 * ({@code p_memsz - p_filesz}) are explicitly zeroed, so reloading over a dirty machine is
 * deterministic.
 */
public final class RiscV32ElfLoader {

    private static final int ELF_HEADER_SIZE = 52;
    private static final int PROGRAM_HEADER_SIZE = 32;
    private static final int ELFCLASS32 = 1;
    private static final int ELFDATA2LSB = 1;
    private static final int EV_CURRENT = 1;
    private static final int ET_EXEC = 2;
    private static final int ET_DYN = 3;
    private static final int EM_RISCV = 243;
    private static final int PT_LOAD = 1;
    private static final int PF_X = 1;

    private RiscV32ElfLoader() {
    }

    /**
     * Load an ELF image into every virtual core and reset each core to the ELF entry point.
     *
     * @return entry point
     */
    public static int loadAll(RiscV32Machine machine, byte[] elf) {
        return loadAllImage(machine, elf).entryPoint();
    }

    /**
     * Load an ELF image into every virtual core and return executable-segment metadata.
     */
    public static RiscV32ElfImage loadAllImage(RiscV32Machine machine, byte[] elf) {
        Objects.requireNonNull(machine, "machine");
        Header header = parseHeader(elf);
        for (int core = 0; core < machine.cores(); core++) {
            loadSegments(machine, core, elf, header);
            machine.resetCore(core, header.entry);
        }
        return imageMetadata(machine, elf, header);
    }

    /**
     * Load an ELF image and immediately build the shared predecode/basic-block tier from its
     * executable PT_LOAD span.
     */
    public static RiscV32ElfImage loadAllPrepared(RiscV32Machine machine, byte[] elf,
            int maxBlockInstructions) {
        RiscV32ElfImage image = loadAllImage(machine, elf);
        if (!image.hasExecutableRange()) {
            throw new IllegalArgumentException("ELF contains no executable PT_LOAD segment");
        }
        machine.buildCodeCache(0, image.executableBase(), image.executableBytes());
        machine.buildBlockCache(maxBlockInstructions);
        return image;
    }

    /**
     * Load an ELF image into one virtual core and reset it to the ELF entry point.
     *
     * @return entry point
     */
    public static int load(RiscV32Machine machine, int core, byte[] elf) {
        return loadImage(machine, core, elf).entryPoint();
    }

    /**
     * Load an ELF image into one virtual core and return executable-segment metadata.
     */
    public static RiscV32ElfImage loadImage(RiscV32Machine machine, int core, byte[] elf) {
        Objects.requireNonNull(machine, "machine");
        Header header = parseHeader(elf);
        loadSegments(machine, core, elf, header);
        machine.resetCore(core, header.entry);
        return imageMetadata(machine, elf, header);
    }

    private static Header parseHeader(byte[] elf) {
        Objects.requireNonNull(elf, "elf");
        if (elf.length < ELF_HEADER_SIZE) {
            throw new IllegalArgumentException("ELF image shorter than ELF32 header");
        }
        if ((elf[0] & 0xff) != 0x7f || elf[1] != 'E' || elf[2] != 'L' || elf[3] != 'F') {
            throw new IllegalArgumentException("not an ELF image");
        }
        if ((elf[4] & 0xff) != ELFCLASS32) {
            throw new IllegalArgumentException("only ELF32 is supported");
        }
        if ((elf[5] & 0xff) != ELFDATA2LSB) {
            throw new IllegalArgumentException("only little-endian ELF is supported");
        }
        if ((elf[6] & 0xff) != EV_CURRENT || u32(elf, 20) != EV_CURRENT) {
            throw new IllegalArgumentException("unsupported ELF version");
        }

        int type = u16(elf, 16);
        if (type != ET_EXEC && type != ET_DYN) {
            throw new IllegalArgumentException("ELF type must be ET_EXEC or ET_DYN: " + type);
        }
        int machine = u16(elf, 18);
        if (machine != EM_RISCV) {
            throw new IllegalArgumentException("ELF machine is not RISC-V: " + machine);
        }

        int entry = address(u32(elf, 24), "entry point");
        long programHeaderOffset = u32(elf, 28);
        int elfHeaderSize = u16(elf, 40);
        int programHeaderEntrySize = u16(elf, 42);
        int programHeaderCount = u16(elf, 44);

        if (elfHeaderSize < ELF_HEADER_SIZE) {
            throw new IllegalArgumentException("invalid ELF header size: " + elfHeaderSize);
        }
        if (programHeaderCount > 0 && programHeaderEntrySize < PROGRAM_HEADER_SIZE) {
            throw new IllegalArgumentException("invalid ELF program-header size: " + programHeaderEntrySize);
        }
        long tableEnd = programHeaderOffset + (long) programHeaderEntrySize * programHeaderCount;
        if (programHeaderOffset < 0 || tableEnd < programHeaderOffset || tableEnd > elf.length) {
            throw new IllegalArgumentException("ELF program-header table is outside the image");
        }
        if ((entry & 1) != 0) {
            throw new IllegalArgumentException("RV32IMAC entry point must be 2-byte aligned: " + entry);
        }

        return new Header(entry, (int) programHeaderOffset, programHeaderEntrySize, programHeaderCount);
    }

    private static void loadSegments(RiscV32Machine machine, int core, byte[] elf, Header header) {
        for (int index = 0; index < header.programHeaderCount; index++) {
            int offset = header.programHeaderOffset + index * header.programHeaderEntrySize;
            int type = (int) u32(elf, offset);
            if (type != PT_LOAD) {
                continue;
            }

            long fileOffset = u32(elf, offset + 4);
            long virtualAddress = u32(elf, offset + 8);
            long physicalAddress = u32(elf, offset + 12);
            long fileSize = u32(elf, offset + 16);
            long memorySize = u32(elf, offset + 20);

            if (fileSize > memorySize) {
                throw new IllegalArgumentException("PT_LOAD p_filesz exceeds p_memsz");
            }
            if (fileOffset > elf.length || fileSize > elf.length - fileOffset) {
                throw new IllegalArgumentException("PT_LOAD file range is outside the ELF image");
            }

            long selectedAddress = physicalAddress != 0 ? physicalAddress : virtualAddress;
            int destination = address(selectedAddress, "PT_LOAD address");
            int bytes = length(memorySize, "PT_LOAD memory size");
            int fileBytes = length(fileSize, "PT_LOAD file size");

            if (bytes > 0) {
                long end = (long) destination + bytes;
                if (end > machine.memoryBytesPerCore()) {
                    throw new IllegalArgumentException("PT_LOAD range exceeds virtual-core RAM: "
                            + destination + ".." + end + " > " + machine.memoryBytesPerCore());
                }
            }

            if (fileBytes > 0) {
                byte[] payload = Arrays.copyOfRange(elf, (int) fileOffset, (int) fileOffset + fileBytes);
                machine.loadBytes(core, destination, payload);
            }
            if (bytes > fileBytes) {
                machine.fillBytes(core, destination + fileBytes, bytes - fileBytes, 0);
            }
        }

        if (header.entry < 0 || header.entry > machine.memoryBytesPerCore() - 2) {
            throw new IllegalArgumentException("ELF entry point is outside virtual-core RAM: " + header.entry);
        }
    }

    private static RiscV32ElfImage imageMetadata(RiscV32Machine machine, byte[] elf, Header header) {
        int executableBase = Integer.MAX_VALUE;
        int executableEnd = -1;

        for (int index = 0; index < header.programHeaderCount; index++) {
            int offset = header.programHeaderOffset + index * header.programHeaderEntrySize;
            if ((int) u32(elf, offset) != PT_LOAD) {
                continue;
            }

            int flags = (int) u32(elf, offset + 24);
            if ((flags & PF_X) == 0) {
                continue;
            }

            long virtualAddress = u32(elf, offset + 8);
            long physicalAddress = u32(elf, offset + 12);
            int destination = address(physicalAddress != 0 ? physicalAddress : virtualAddress,
                    "executable PT_LOAD address");
            int bytes = length(u32(elf, offset + 20), "executable PT_LOAD memory size");
            if (bytes == 0) {
                continue;
            }

            executableBase = Math.min(executableBase, destination);
            executableEnd = Math.max(executableEnd, Math.addExact(destination, bytes));
        }

        if (executableEnd < 0) {
            return new RiscV32ElfImage(header.entry, 0, 0);
        }

        int alignedBase = executableBase & ~1;
        int alignedEnd = (executableEnd + 1) & ~1;
        if (alignedEnd > machine.memoryBytesPerCore()) {
            alignedEnd = machine.memoryBytesPerCore();
        }
        return new RiscV32ElfImage(header.entry, alignedBase, alignedEnd);
    }

    private static int u16(byte[] bytes, int offset) {
        require(bytes, offset, 2);
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    private static long u32(byte[] bytes, int offset) {
        require(bytes, offset, 4);
        return ((long) bytes[offset] & 0xff)
                | (((long) bytes[offset + 1] & 0xff) << 8)
                | (((long) bytes[offset + 2] & 0xff) << 16)
                | (((long) bytes[offset + 3] & 0xff) << 24);
    }

    private static void require(byte[] bytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new IllegalArgumentException("truncated ELF structure");
        }
    }

    private static int address(long value, String label) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " exceeds this flat RV32 memory model: "
                    + Long.toUnsignedString(value));
        }
        return (int) value;
    }

    private static int length(long value, String label) {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(label + " is too large: " + Long.toUnsignedString(value));
        }
        return (int) value;
    }

    private static final class Header {
        private final int entry;
        private final int programHeaderOffset;
        private final int programHeaderEntrySize;
        private final int programHeaderCount;

        private Header(int entry, int programHeaderOffset, int programHeaderEntrySize, int programHeaderCount) {
            this.entry = entry;
            this.programHeaderOffset = programHeaderOffset;
            this.programHeaderEntrySize = programHeaderEntrySize;
            this.programHeaderCount = programHeaderCount;
        }
    }
}
