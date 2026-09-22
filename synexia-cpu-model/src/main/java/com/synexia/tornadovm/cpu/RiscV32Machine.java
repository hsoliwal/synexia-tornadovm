/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import java.util.Objects;

import uk.ac.manchester.tornado.api.types.arrays.IntArray;

/**
 * Dense host-side owner of the arrays consumed by {@link RiscV32Kernel}.
 *
 * <p>Registers, CSRs and RAM are stored transposed for GPU coalescing:
 *
 * <ul>
 *   <li>{@code registers[register * cores + core]}</li>
 *   <li>{@code csrs[slot * cores + core]}</li>
 *   <li>{@code memory[wordAddress * cores + core]}</li>
 * </ul>
 *
 * Neighboring accelerator work-items therefore touch neighboring physical words when virtual cores
 * execute the same instruction stream, which is the common SIMD/SIMT case.
 */
public final class RiscV32Machine {

    private final int cores;
    private final int memoryBytesPerCore;
    private final int wordsPerCore;

    private final IntArray registers;
    private final IntArray pc;
    private final IntArray status;
    private final IntArray trapCause;
    private final IntArray trapValue;
    private final IntArray retiredInstructions;
    private final IntArray csrs;
    private final IntArray reservations;
    private final IntArray memory;

    private int executionFlags;

    public RiscV32Machine(int cores, int memoryBytesPerCore) {
        if (cores <= 0) {
            throw new IllegalArgumentException("cores must be positive");
        }
        if (memoryBytesPerCore <= 0 || (memoryBytesPerCore & 3) != 0) {
            throw new IllegalArgumentException("memoryBytesPerCore must be positive and divisible by four");
        }
        long totalWords = (long) cores * (memoryBytesPerCore / 4);
        if (totalWords > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("combined packed memory exceeds IntArray index space");
        }

        this.cores = cores;
        this.memoryBytesPerCore = memoryBytesPerCore;
        this.wordsPerCore = memoryBytesPerCore / 4;
        this.registers = new IntArray(Math.multiplyExact(cores, RiscV32.REGISTER_COUNT));
        this.pc = new IntArray(cores);
        this.status = new IntArray(cores);
        this.trapCause = new IntArray(cores);
        this.trapValue = new IntArray(cores);
        this.retiredInstructions = new IntArray(cores);
        this.csrs = new IntArray(Math.multiplyExact(cores, RiscV32.CSR_SLOT_COUNT));
        this.reservations = new IntArray(cores);
        this.memory = new IntArray((int) totalWords);
        this.executionFlags = RiscV32.DEFAULT_EXECUTION_FLAGS;
        resetAll(0);
    }

    public int cores() {
        return cores;
    }

    public int memoryBytesPerCore() {
        return memoryBytesPerCore;
    }

    public int wordsPerCore() {
        return wordsPerCore;
    }

    public IntArray registers() {
        return registers;
    }

    public IntArray pc() {
        return pc;
    }

    public IntArray status() {
        return status;
    }

    public IntArray trapCause() {
        return trapCause;
    }

    public IntArray trapValue() {
        return trapValue;
    }

    public IntArray retiredInstructions() {
        return retiredInstructions;
    }

    public IntArray csrs() {
        return csrs;
    }

    public IntArray reservations() {
        return reservations;
    }

    public IntArray memory() {
        return memory;
    }

    public int executionFlags() {
        return executionFlags;
    }

    public RiscV32Machine withTrapVectoring(boolean enabled) {
        if (enabled) {
            executionFlags |= RiscV32.FLAG_VECTOR_TRAPS;
        } else {
            executionFlags &= ~RiscV32.FLAG_VECTOR_TRAPS;
        }
        return this;
    }

    public RiscV32Machine withEbreakHalt(boolean enabled) {
        if (enabled) {
            executionFlags |= RiscV32.FLAG_EBREAK_HALT;
        } else {
            executionFlags &= ~RiscV32.FLAG_EBREAK_HALT;
        }
        return this;
    }

    public void resetAll(int entryPoint) {
        requireHalfwordAddress(entryPoint);
        requireMemoryRange(entryPoint, 2);
        registers.init(0);
        pc.init(entryPoint);
        status.init(RiscV32.STATUS_RUNNING);
        trapCause.init(RiscV32.TRAP_NONE);
        trapValue.init(0);
        retiredInstructions.init(0);
        csrs.init(0);
        reservations.init(-1);
    }

    public void resetCore(int core, int entryPoint) {
        core(core);
        requireHalfwordAddress(entryPoint);
        requireMemoryRange(entryPoint, 2);
        for (int register = 0; register < RiscV32.REGISTER_COUNT; register++) {
            registers.set(registerIndex(core, register), 0);
        }
        for (int slot = 0; slot < RiscV32.CSR_SLOT_COUNT; slot++) {
            csrs.set(csrIndex(core, slot), 0);
        }
        pc.set(core, entryPoint);
        status.set(core, RiscV32.STATUS_RUNNING);
        trapCause.set(core, RiscV32.TRAP_NONE);
        trapValue.set(core, 0);
        retiredInstructions.set(core, 0);
        reservations.set(core, -1);
    }

    public void clearMemory() {
        memory.init(0);
    }

    public void loadProgramAll(int byteAddress, int... words) {
        Objects.requireNonNull(words, "words");
        requireWordAddress(byteAddress);
        long byteLength = (long) words.length * 4L;
        if (byteLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("program is too large");
        }
        if (words.length > 0) {
            requireMemoryRange(byteAddress, (int) byteLength);
        }

        int firstWord = byteAddress >>> 2;
        for (int offset = 0; offset < words.length; offset++) {
            int wordBase = (firstWord + offset) * cores;
            int value = words[offset];
            for (int core = 0; core < cores; core++) {
                memory.set(wordBase + core, value);
            }
        }
    }

    public void loadProgram(int core, int byteAddress, int... words) {
        core(core);
        Objects.requireNonNull(words, "words");
        requireWordAddress(byteAddress);
        long byteLength = (long) words.length * 4L;
        if (byteLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("program is too large");
        }
        if (words.length > 0) {
            requireMemoryRange(byteAddress, (int) byteLength);
        }

        int firstWord = byteAddress >>> 2;
        for (int offset = 0; offset < words.length; offset++) {
            memory.set((firstWord + offset) * cores + core, words[offset]);
        }
    }

    public int register(int core, int register) {
        core(core);
        register(register);
        return registers.get(registerIndex(core, register));
    }

    public void register(int core, int register, int value) {
        core(core);
        register(register);
        if (register != 0) {
            registers.set(registerIndex(core, register), value);
        }
    }

    public int pc(int core) {
        core(core);
        return pc.get(core);
    }

    public int status(int core) {
        core(core);
        return status.get(core);
    }

    public int trapCause(int core) {
        core(core);
        return trapCause.get(core);
    }

    public int trapValue(int core) {
        core(core);
        return trapValue.get(core);
    }

    public long retiredInstructions(int core) {
        core(core);
        return Integer.toUnsignedLong(retiredInstructions.get(core));
    }

    public long totalRetiredInstructions() {
        long total = 0;
        for (int core = 0; core < cores; core++) {
            total += Integer.toUnsignedLong(retiredInstructions.get(core));
        }
        return total;
    }

    public int csr(int core, int address) {
        core(core);
        int slot = RiscV32.csrSlot(address);
        if (slot >= 0) {
            return csrs.get(csrIndex(core, slot));
        }
        switch (address) {
            case RiscV32.CSR_MISA:
                return RiscV32.MISA_RV32_IMAC;
            case RiscV32.CSR_MHARTID:
                return core;
            case RiscV32.CSR_MCYCLE:
            case RiscV32.CSR_MINSTRET:
            case RiscV32.CSR_CYCLE:
            case RiscV32.CSR_INSTRET:
                return retiredInstructions.get(core);
            default:
                throw new IllegalArgumentException("unsupported CSR 0x" + Integer.toHexString(address));
        }
    }

    public void csr(int core, int address, int value) {
        core(core);
        int slot = RiscV32.csrSlot(address);
        if (slot >= 0) {
            csrs.set(csrIndex(core, slot), value);
            return;
        }
        if (address == RiscV32.CSR_MCYCLE || address == RiscV32.CSR_MINSTRET) {
            retiredInstructions.set(core, value);
            return;
        }
        throw new IllegalArgumentException("CSR is read-only or unsupported: 0x" + Integer.toHexString(address));
    }

    public int readUnsignedByte(int core, int byteAddress) {
        core(core);
        requireMemoryRange(byteAddress, 1);
        int word = memory.get(memoryIndex(core, byteAddress));
        return (word >>> ((byteAddress & 3) << 3)) & 0xff;
    }

    public void writeByte(int core, int byteAddress, int value) {
        core(core);
        requireMemoryRange(byteAddress, 1);
        int index = memoryIndex(core, byteAddress);
        int shift = (byteAddress & 3) << 3;
        int mask = 0xff << shift;
        int oldWord = memory.get(index);
        memory.set(index, (oldWord & ~mask) | ((value & 0xff) << shift));
    }

    public void loadBytes(int core, int byteAddress, byte[] bytes) {
        core(core);
        Objects.requireNonNull(bytes, "bytes");
        requireMemoryRange(byteAddress, bytes.length);
        for (int index = 0; index < bytes.length; index++) {
            writeByte(core, byteAddress + index, bytes[index]);
        }
    }

    public void fillBytes(int core, int byteAddress, int length, int value) {
        core(core);
        requireMemoryRange(byteAddress, length);
        for (int index = 0; index < length; index++) {
            writeByte(core, byteAddress + index, value);
        }
    }

    public int readWord(int core, int byteAddress) {
        core(core);
        requireWordAddress(byteAddress);
        requireMemoryRange(byteAddress, 4);
        return memory.get(memoryIndex(core, byteAddress));
    }

    public void writeWord(int core, int byteAddress, int value) {
        core(core);
        requireWordAddress(byteAddress);
        requireMemoryRange(byteAddress, 4);
        memory.set(memoryIndex(core, byteAddress), value);
    }

    public boolean allStopped() {
        for (int core = 0; core < cores; core++) {
            if (status.get(core) == RiscV32.STATUS_RUNNING) {
                return false;
            }
        }
        return true;
    }

    public int runningCores() {
        int running = 0;
        for (int core = 0; core < cores; core++) {
            if (status.get(core) == RiscV32.STATUS_RUNNING) {
                running++;
            }
        }
        return running;
    }

    public int waitingCores() {
        int waiting = 0;
        for (int core = 0; core < cores; core++) {
            if (status.get(core) == RiscV32.STATUS_WAITING) {
                waiting++;
            }
        }
        return waiting;
    }

    public void resumeWaitingCores() {
        for (int core = 0; core < cores; core++) {
            if (status.get(core) == RiscV32.STATUS_WAITING) {
                status.set(core, RiscV32.STATUS_RUNNING);
            }
        }
    }

    private int registerIndex(int core, int register) {
        return register * cores + core;
    }

    private int csrIndex(int core, int slot) {
        return slot * cores + core;
    }

    private int memoryIndex(int core, int byteAddress) {
        return (byteAddress >>> 2) * cores + core;
    }

    private void core(int core) {
        Objects.checkIndex(core, cores);
    }

    private static void register(int register) {
        Objects.checkIndex(register, RiscV32.REGISTER_COUNT);
    }

    private static void requireHalfwordAddress(int address) {
        if ((address & 1) != 0) {
            throw new IllegalArgumentException("address must be two-byte aligned: " + address);
        }
    }

    private static void requireWordAddress(int address) {
        if ((address & 3) != 0) {
            throw new IllegalArgumentException("address must be four-byte aligned: " + address);
        }
    }

    private void requireMemoryRange(int address, int bytes) {
        if (address < 0 || bytes < 0 || address > memoryBytesPerCore - bytes) {
            throw new IndexOutOfBoundsException(
                    "memory range [" + address + "," + ((long) address + bytes) + ") outside 0.." + memoryBytesPerCore);
        }
    }
}
