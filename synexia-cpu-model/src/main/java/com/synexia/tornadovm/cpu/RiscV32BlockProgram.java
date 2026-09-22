/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import uk.ac.manchester.tornado.api.types.arrays.Int8Array;
import uk.ac.manchester.tornado.api.types.arrays.IntArray;
import uk.ac.manchester.tornado.api.types.arrays.LongArray;

/**
 * Primitive-only compiled basic-block program.
 *
 * <p>A halfword-indexed entry map points to packed block descriptors; descriptors point into one
 * contiguous 64-bit micro-op stream. No object is allocated per block or per operation.
 */
public final class RiscV32BlockProgram {

    private final IntArray blockBySlot;
    private final LongArray blockDescriptors;
    private final Int8Array blockValid;
    private final LongArray microOps;
    private final int blockCount;
    private final int operationCount;

    RiscV32BlockProgram(IntArray blockBySlot, LongArray blockDescriptors, Int8Array blockValid,
            LongArray microOps, int blockCount, int operationCount) {
        this.blockBySlot = blockBySlot;
        this.blockDescriptors = blockDescriptors;
        this.blockValid = blockValid;
        this.microOps = microOps;
        this.blockCount = blockCount;
        this.operationCount = operationCount;
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

    public int blockCount() {
        return blockCount;
    }

    public int operationCount() {
        return operationCount;
    }

    static long descriptor(int firstOperation, int operationCount, int guestInstructionCount) {
        if (operationCount < 0 || operationCount > 0xffff) {
            throw new IllegalArgumentException("operationCount must fit 16 bits");
        }
        if (guestInstructionCount < 0 || guestInstructionCount > 0xffff) {
            throw new IllegalArgumentException("guestInstructionCount must fit 16 bits");
        }
        return ((long) firstOperation << 32)
                | ((long) guestInstructionCount << 16)
                | (operationCount & 0xffffL);
    }

    public static int firstOperation(long descriptor) {
        return (int) (descriptor >>> 32);
    }

    public static int operationCount(long descriptor) {
        return (int) descriptor & 0xffff;
    }

    public static int guestInstructionCount(long descriptor) {
        return ((int) descriptor >>> 16) & 0xffff;
    }
}
