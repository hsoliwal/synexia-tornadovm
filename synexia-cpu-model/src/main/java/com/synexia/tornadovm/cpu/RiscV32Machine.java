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
 */
public final class RiscV32Machine {

    private final int cores;
    private final int memoryBytesPerCore;
    private final int wordsPerCore;

    private final IntArray registers;
    private final IntArray pc;
    private final IntArray status;
    private final IntArray trapCause;
    private final IntArray retiredInstructions;
    private final IntArray memory;

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
        this.retiredInstructions = new IntArray(cores);
        this.memory = new IntArray((int) totalWords);
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

    public IntArray retiredInstructions() {
        return retiredInstructions;
    }

    public IntArray memory() {
        return memory;
    }

    public void resetAll(int entryPoint) {
        requireWordAddress(entryPoint);
        requireMemoryRange(entryPoint, 4);
        registers.init(0);
        pc.init(entryPoint);
        status.init(RiscV32.STATUS_RUNNING);
        trapCause.init(RiscV32.TRAP_NONE);
        retiredInstructions.init(0);
    }

    public void resetCore(int core, int entryPoint) {
        core(core);
        requireWordAddress(entryPoint);
        requireMemoryRange(entryPoint, 4);
        int base = core * RiscV32.REGISTER_COUNT;
        for (int register = 0; register < RiscV32.REGISTER_COUNT; register++) {
            registers.set(base + register, 0);
        }
        pc.set(core, entryPoint);
        status.set(core, RiscV32.STATUS_RUNNING);
        trapCause.set(core, RiscV32.TRAP_NONE);
        retiredInstructions.set(core, 0);
    }

    public void clearMemory() {
        memory.init(0);
    }

    public void loadProgramAll(int byteAddress, int... words) {
        Objects.requireNonNull(words, "words");
        for (int core = 0; core < cores; core++) {
            loadProgram(core, byteAddress, words);
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
        int base = core * wordsPerCore + (byteAddress >>> 2);
        for (int index = 0; index < words.length; index++) {
            memory.set(base + index, words[index]);
        }
    }

    public int register(int core, int register) {
        core(core);
        register(register);
        return registers.get(core * RiscV32.REGISTER_COUNT + register);
    }

    public void register(int core, int register, int value) {
        core(core);
        register(register);
        if (register != 0) {
            registers.set(core * RiscV32.REGISTER_COUNT + register, value);
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

    public int readUnsignedByte(int core, int byteAddress) {
        core(core);
        requireMemoryRange(byteAddress, 1);
        int word = memory.get(core * wordsPerCore + (byteAddress >>> 2));
        return (word >>> ((byteAddress & 3) << 3)) & 0xff;
    }

    public void writeByte(int core, int byteAddress, int value) {
        core(core);
        requireMemoryRange(byteAddress, 1);
        int index = core * wordsPerCore + (byteAddress >>> 2);
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
        return memory.get(core * wordsPerCore + (byteAddress >>> 2));
    }

    public void writeWord(int core, int byteAddress, int value) {
        core(core);
        requireWordAddress(byteAddress);
        requireMemoryRange(byteAddress, 4);
        memory.set(core * wordsPerCore + (byteAddress >>> 2), value);
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

    private void core(int core) {
        Objects.checkIndex(core, cores);
    }

    private static void register(int register) {
        Objects.checkIndex(register, RiscV32.REGISTER_COUNT);
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
