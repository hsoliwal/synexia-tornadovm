/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import static com.synexia.tornadovm.cpu.RiscV32Assembler.addi;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.ebreak;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.sw;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class RiscV32ElfLoaderTest {

    @Test
    public void loadsExecutableSegmentsZerosBssAndRunsAllCores() {
        int[] program = {
                addi(1, 0, 41),
                addi(1, 1, 1),
                sw(1, 0, 128),
                ebreak()
        };
        byte[] elf = executable(program, 32);

        RiscV32Machine machine = new RiscV32Machine(2, 512);
        machine.writeWord(0, 16, -1);
        machine.writeWord(1, 16, -1);

        int entry = RiscV32ElfLoader.loadAll(machine, elf);
        assertEquals(0, entry);
        assertEquals(0, machine.readWord(0, 16));
        assertEquals(0, machine.readWord(1, 16));

        new JvmRiscV32Executor().execute(machine, 32, 4);

        for (int core = 0; core < machine.cores(); core++) {
            assertEquals(42, machine.register(core, 1));
            assertEquals(42, machine.readWord(core, 128));
            assertEquals(RiscV32.STATUS_HALTED, machine.status(core));
        }
    }

    @Test
    public void acceptsHalfwordAlignedCompressedEntryPoint() {
        byte[] elf = executable(new int[] { ebreak() }, 4);
        put32(elf, 24, 2);

        RiscV32Machine machine = new RiscV32Machine(1, 256);
        int entry = RiscV32ElfLoader.loadAll(machine, elf);

        assertEquals(2, entry);
        assertEquals(2, machine.pc(0));
    }

    @Test
    public void preparesTierDirectlyFromExecutableElfSegment() {
        int[] program = {
                addi(1, 0, 41),
                addi(1, 1, 1),
                sw(1, 0, 128),
                ebreak()
        };
        byte[] elf = executable(program, 32);

        RiscV32Machine machine = new RiscV32Machine(4, 512);
        RiscV32ElfImage image = RiscV32ElfLoader.loadAllPrepared(machine, elf, 16);

        assertEquals(0, image.entryPoint());
        assertEquals(0, image.executableBase());
        assertEquals(32, image.executableEnd());
        assertEquals(32, image.executableBytes());
        assertEquals(true, machine.hasCodeCache());
        assertEquals(true, machine.hasBlockCache());
        assertEquals(3, machine.compilationStats().compiledGuestInstructions());
        assertEquals(2, machine.compilationStats().microOps());

        RiscV32ExecutionResult result = new JvmTieredRiscV32Executor().execute(machine, 16, 4);
        assertEquals(true, result.allStopped());
        for (int core = 0; core < machine.cores(); core++) {
            assertEquals(42, machine.register(core, 1));
            assertEquals(42, machine.readWord(core, 128));
        }
    }

    @Test
    public void rejectsNonRiscVElf() {
        byte[] elf = executable(new int[] { ebreak() }, 4);
        put16(elf, 18, 62); // EM_X86_64

        assertThrows(IllegalArgumentException.class,
                () -> RiscV32ElfLoader.loadAll(new RiscV32Machine(1, 256), elf));
    }

    private static byte[] executable(int[] program, int memorySize) {
        final int elfHeaderSize = 52;
        final int programHeaderSize = 32;
        final int payloadOffset = elfHeaderSize + programHeaderSize;
        byte[] bytes = new byte[payloadOffset + program.length * 4];

        bytes[0] = 0x7f;
        bytes[1] = 'E';
        bytes[2] = 'L';
        bytes[3] = 'F';
        bytes[4] = 1; // ELFCLASS32
        bytes[5] = 1; // ELFDATA2LSB
        bytes[6] = 1; // EV_CURRENT

        put16(bytes, 16, 2);       // ET_EXEC
        put16(bytes, 18, 243);     // EM_RISCV
        put32(bytes, 20, 1);       // EV_CURRENT
        put32(bytes, 24, 0);       // entry
        put32(bytes, 28, elfHeaderSize);
        put16(bytes, 40, elfHeaderSize);
        put16(bytes, 42, programHeaderSize);
        put16(bytes, 44, 1);

        int ph = elfHeaderSize;
        put32(bytes, ph, 1);                       // PT_LOAD
        put32(bytes, ph + 4, payloadOffset);
        put32(bytes, ph + 8, 0);                   // p_vaddr
        put32(bytes, ph + 12, 0);                  // p_paddr -> p_vaddr
        put32(bytes, ph + 16, program.length * 4); // p_filesz
        put32(bytes, ph + 20, memorySize);         // p_memsz
        put32(bytes, ph + 24, 5);                  // R|X
        put32(bytes, ph + 28, 4);

        for (int index = 0; index < program.length; index++) {
            put32(bytes, payloadOffset + index * 4, program[index]);
        }
        return bytes;
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }
}
