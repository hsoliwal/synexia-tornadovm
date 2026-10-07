/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import java.util.Objects;

import uk.ac.manchester.tornado.api.types.arrays.Int8Array;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.types.arrays.LongArray;

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

    private IntArray decodedInstructions;
    private IntArray decodedRawInstructions;
    private Int8Array decodedInstructionLengths;
    private int codeCacheBase;
    private int codeCacheEnd;

    private IntArray blockBySlot;
    private LongArray blockDescriptors;
    private Int8Array blockValid;
    private LongArray microOps;
    private IntArray tierFallbackBudget;
    private IntArray compiledBlockExecutions;
    private int compiledBlockCount;
    private int compiledOperationCount;

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
        this.decodedInstructions = new IntArray(1);
        this.decodedRawInstructions = new IntArray(1);
        this.decodedInstructionLengths = new Int8Array(1);
        this.codeCacheBase = 0;
        this.codeCacheEnd = 0;
        this.blockBySlot = new IntArray(1);
        this.blockDescriptors = new LongArray(1);
        this.blockValid = new Int8Array(1);
        this.microOps = new LongArray(1);
        this.tierFallbackBudget = new IntArray(cores);
        this.compiledBlockExecutions = new IntArray(cores);
        this.compiledBlockCount = 0;
        this.compiledOperationCount = 0;
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

    public IntArray decodedInstructions() {
        return decodedInstructions;
    }

    public IntArray decodedRawInstructions() {
        return decodedRawInstructions;
    }

    public Int8Array decodedInstructionLengths() {
        return decodedInstructionLengths;
    }

    public int codeCacheBase() {
        return codeCacheBase;
    }

    public int codeCacheEnd() {
        return codeCacheEnd;
    }

    public IntArray blockBySlot() {
        return blockBySlot;
    }

    public LongArray blockDescriptors() {
        return blockDescriptors;
    }

    public Int8Array blockValid() {
        return blockValid;
    }

    public LongArray microOps() {
        return microOps;
    }

    public IntArray tierFallbackBudget() {
        return tierFallbackBudget;
    }

    public IntArray compiledBlockExecutionsArray() {
        return compiledBlockExecutions;
    }

    public long compiledBlockExecutions(int core) {
        core(core);
        return Integer.toUnsignedLong(compiledBlockExecutions.get(core));
    }

    public long totalCompiledBlockExecutions() {
        long total = 0;
        for (int core = 0; core < cores; core++) {
            total += Integer.toUnsignedLong(compiledBlockExecutions.get(core));
        }
        return total;
    }

    public int compiledBlockCount() {
        return compiledBlockCount;
    }

    public int compiledOperationCount() {
        return compiledOperationCount;
    }

    public boolean hasBlockCache() {
        return compiledBlockCount > 0;
    }

    public RiscV32CompilationStats compilationStats() {
        int cachedInstructions = 0;
        for (int slot = 0; slot < decodedInstructionLengths.getSize(); slot++) {
            if ((decodedInstructionLengths.get(slot) & 0xff) != 0) {
                cachedInstructions++;
            }
        }

        int compiledGuestInstructions = 0;
        for (int block = 0; block < compiledBlockCount; block++) {
            compiledGuestInstructions += RiscV32BlockProgram.guestInstructionCount(
                    blockDescriptors.get(block));
        }

        int fusedInstructions = Math.max(0, compiledGuestInstructions - compiledOperationCount);
        return new RiscV32CompilationStats(
                Math.max(0, codeCacheEnd - codeCacheBase),
                cachedInstructions,
                compiledBlockCount,
                compiledGuestInstructions,
                compiledOperationCount,
                fusedInstructions,
                totalCompiledBlockExecutions());
    }

    public boolean hasCodeCache() {
        return codeCacheEnd > codeCacheBase;
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
        tierFallbackBudget.init(0);
        compiledBlockExecutions.init(0);
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
        tierFallbackBudget.set(core, 0);
        compiledBlockExecutions.set(core, 0);
        reservations.set(core, -1);
    }

    public void clearMemory() {
        memory.init(0);
    }

    /**
     * Build a shared canonical instruction cache from one core's immutable code image.
     *
     * <p>The cache is shared by all virtual cores and is therefore intended for the common
     * accelerator case where many cores execute the same program image. If guest code stores into
     * the cached region, the kernel conservatively invalidates the affected shared cache entries
     * and falls back to architectural memory fetch for those addresses.
     */
    public void buildCodeCache(int sourceCore, int byteAddress, int byteLength) {
        core(sourceCore);
        clearBlockCache();
        requireHalfwordAddress(byteAddress);
        if (byteLength <= 0 || (byteLength & 1) != 0) {
            throw new IllegalArgumentException("byteLength must be positive and two-byte aligned");
        }
        requireMemoryRange(byteAddress, byteLength);

        int slots = byteLength >>> 1;
        IntArray canonical = new IntArray(slots);
        IntArray raw = new IntArray(slots);
        Int8Array lengths = new Int8Array(slots);

        int pc = byteAddress;
        int end = byteAddress + byteLength;
        while (pc < end) {
            int slot = (pc - byteAddress) >>> 1;
            int halfword = readUnsignedByte(sourceCore, pc)
                    | (readUnsignedByte(sourceCore, pc + 1) << 8);

            if ((halfword & 3) != 3) {
                canonical.set(slot, RiscV32Kernel.decompressInstruction(halfword));
                raw.set(slot, halfword);
                lengths.set(slot, (byte) 2);
                pc += 2;
            } else {
                if (pc > end - 4) {
                    break;
                }
                int instruction = halfword
                        | (readUnsignedByte(sourceCore, pc + 2) << 16)
                        | (readUnsignedByte(sourceCore, pc + 3) << 24);
                canonical.set(slot, instruction);
                raw.set(slot, instruction);
                lengths.set(slot, (byte) 4);
                pc += 4;
            }
        }

        decodedInstructions = canonical;
        decodedRawInstructions = raw;
        decodedInstructionLengths = lengths;
        codeCacheBase = byteAddress;
        codeCacheEnd = end;
    }

    public void clearCodeCache() {
        clearBlockCache();
        decodedInstructions = new IntArray(1);
        decodedRawInstructions = new IntArray(1);
        decodedInstructionLengths = new Int8Array(1);
        codeCacheBase = 0;
        codeCacheEnd = 0;
    }

    /**
     * Compile the current canonical code cache into packed basic blocks.
     *
     * @param maxBlockInstructions
     *         maximum guest instructions placed in one compiled block
     */
    public void buildBlockCache(int maxBlockInstructions) {
        RiscV32BlockProgram program = RiscV32BlockCompiler.compile(this, maxBlockInstructions);
        blockBySlot = program.blockBySlot();
        blockDescriptors = program.blockDescriptors();
        blockValid = program.blockValid();
        microOps = program.microOps();
        compiledBlockCount = program.blockCount();
        compiledOperationCount = program.operationCount();
        tierFallbackBudget.init(0);
    }

    public void clearBlockCache() {
        blockBySlot = new IntArray(1);
        blockDescriptors = new LongArray(1);
        blockValid = new Int8Array(1);
        microOps = new LongArray(1);
        compiledBlockCount = 0;
        compiledOperationCount = 0;
        tierFallbackBudget.init(0);
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
            invalidateHostCodeCache(byteAddress, (int) byteLength);
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
            invalidateHostCodeCache(byteAddress, (int) byteLength);
        }

        int firstWord = byteAddress >>> 2;
        for (int offset = 0; offset < words.length; offset++) {
            memory.set((firstWord + offset) * cores + core, words[offset]);
        }
    }

    public void loadHalfwordsAll(int byteAddress, int... halfwords) {
        Objects.requireNonNull(halfwords, "halfwords");
        for (int core = 0; core < cores; core++) {
            loadHalfwords(core, byteAddress, halfwords);
        }
    }

    public void loadHalfwords(int core, int byteAddress, int... halfwords) {
        core(core);
        Objects.requireNonNull(halfwords, "halfwords");
        requireHalfwordAddress(byteAddress);
        long byteLength = (long) halfwords.length * 2L;
        if (byteLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("compressed program is too large");
        }
        if (halfwords.length > 0) {
            requireMemoryRange(byteAddress, (int) byteLength);
            invalidateHostCodeCache(byteAddress, (int) byteLength);
        }
        for (int offset = 0; offset < halfwords.length; offset++) {
            int value = halfwords[offset] & 0xffff;
            int address = byteAddress + offset * 2;
            writeByte(core, address, value);
            writeByte(core, address + 1, value >>> 8);
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
            if (address == RiscV32.CSR_MIP && value != 0 && status.get(core) == RiscV32.STATUS_WAITING) {
                status.set(core, RiscV32.STATUS_RUNNING);
            }
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
        invalidateHostCodeCache(byteAddress, 1);
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
        invalidateHostCodeCache(byteAddress, bytes.length);
        for (int index = 0; index < bytes.length; index++) {
            writeByte(core, byteAddress + index, bytes[index]);
        }
    }

    public void fillBytes(int core, int byteAddress, int length, int value) {
        core(core);
        requireMemoryRange(byteAddress, length);
        invalidateHostCodeCache(byteAddress, length);
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
        invalidateHostCodeCache(byteAddress, 4);
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

    public void setMachineSoftwareInterrupt(int core, boolean pending) {
        setPendingInterrupt(core, RiscV32.MIP_MSIP, pending);
    }

    public void setMachineTimerInterrupt(int core, boolean pending) {
        setPendingInterrupt(core, RiscV32.MIP_MTIP, pending);
    }

    public void setMachineExternalInterrupt(int core, boolean pending) {
        setPendingInterrupt(core, RiscV32.MIP_MEIP, pending);
    }

    public void setPendingInterrupt(int core, int mask, boolean pending) {
        core(core);
        int supported = RiscV32.MIP_MSIP | RiscV32.MIP_MTIP | RiscV32.MIP_MEIP;
        if ((mask & ~supported) != 0 || (mask & supported) == 0) {
            throw new IllegalArgumentException("unsupported machine interrupt mask: 0x" + Integer.toHexString(mask));
        }
        int index = csrIndex(core, RiscV32.CSR_SLOT_MIP);
        int value = csrs.get(index);
        value = pending ? value | mask : value & ~mask;
        csrs.set(index, value);
        if (pending && status.get(core) == RiscV32.STATUS_WAITING) {
            status.set(core, RiscV32.STATUS_RUNNING);
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

    private void invalidateHostCodeCache(int address, int length) {
        if (!hasCodeCache() || length <= 0 || address >= codeCacheEnd || address + length <= codeCacheBase) {
            return;
        }
        int first = Math.max(address - 2, codeCacheBase);
        int last = Math.min(address + length - 1, codeCacheEnd - 1);
        int firstSlot = (first - codeCacheBase) >>> 1;
        int lastSlot = (last - codeCacheBase) >>> 1;
        for (int slot = firstSlot; slot <= lastSlot; slot++) {
            decodedInstructionLengths.set(slot, (byte) 0);
        }
        if (hasBlockCache()) {
            blockValid.init((byte) 0);
        }
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
