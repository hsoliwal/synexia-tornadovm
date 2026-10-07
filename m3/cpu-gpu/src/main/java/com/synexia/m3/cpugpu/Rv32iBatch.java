/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import java.util.Arrays;

/**
 * Flat structure-of-arrays state shared by the CPU oracle and GPU session.
 * Each virtual core owns an independent RAM slice, which makes every GPU work-item
 * independent and removes inter-core synchronization from the kernel contract.
 */
public final class Rv32iBatch {
    private final int coreCount;
    private final int memoryBytesPerCore;
    private final int memoryWordsPerCore;
    private final int[] memory;
    private final int[] registers;
    private final int[] state;

    public Rv32iBatch(int coreCount, int memoryBytesPerCore) {
        if (coreCount <= 0) {
            throw new IllegalArgumentException("coreCount must be > 0");
        }
        if (memoryBytesPerCore <= 0 || (memoryBytesPerCore & 3) != 0) {
            throw new IllegalArgumentException("memoryBytesPerCore must be positive and 4-byte aligned");
        }
        this.coreCount = coreCount;
        this.memoryBytesPerCore = memoryBytesPerCore;
        this.memoryWordsPerCore = memoryBytesPerCore >>> 2;
        this.memory = new int[Math.multiplyExact(coreCount, memoryWordsPerCore)];
        this.registers = new int[Math.multiplyExact(coreCount, Rv32i.REGISTER_COUNT)];
        this.state = new int[Math.multiplyExact(coreCount, Rv32i.STATE_STRIDE)];
        for (int core = 0; core < coreCount; core++) {
            this.state[stateBase(core) + Rv32i.STATE_TRAP_CAUSE] = Rv32i.TRAP_NONE;
        }
    }

    private Rv32iBatch(Rv32iBatch source) {
        this.coreCount = source.coreCount;
        this.memoryBytesPerCore = source.memoryBytesPerCore;
        this.memoryWordsPerCore = source.memoryWordsPerCore;
        this.memory = source.memory.clone();
        this.registers = source.registers.clone();
        this.state = source.state.clone();
    }

    public Rv32iBatch copy() {
        return new Rv32iBatch(this);
    }

    public int coreCount() {
        return coreCount;
    }

    public int memoryBytesPerCore() {
        return memoryBytesPerCore;
    }

    public int memoryWordsPerCore() {
        return memoryWordsPerCore;
    }

    public void resetCore(int core) {
        checkCore(core);
        Arrays.fill(registers, registerBase(core), registerBase(core) + Rv32i.REGISTER_COUNT, 0);
        Arrays.fill(state, stateBase(core), stateBase(core) + Rv32i.STATE_STRIDE, 0);
        state[stateBase(core) + Rv32i.STATE_TRAP_CAUSE] = Rv32i.TRAP_NONE;
        Arrays.fill(memory, memoryBase(core), memoryBase(core) + memoryWordsPerCore, 0);
    }

    public void setProgramCounter(int core, int byteAddress) {
        checkCore(core);
        if ((byteAddress & 3) != 0 || byteAddress < 0 || byteAddress > memoryBytesPerCore - 4) {
            throw new IllegalArgumentException("program counter must be an in-range 4-byte address");
        }
        state[stateBase(core) + Rv32i.STATE_PC] = byteAddress;
        state[stateBase(core) + Rv32i.STATE_STATUS] = Rv32i.STATUS_READY;
        state[stateBase(core) + Rv32i.STATE_TRAP_CAUSE] = Rv32i.TRAP_NONE;
        state[stateBase(core) + Rv32i.STATE_TRAP_VALUE] = 0;
    }

    public int programCounter(int core) {
        return state(core, Rv32i.STATE_PC);
    }

    public int status(int core) {
        return state(core, Rv32i.STATE_STATUS);
    }

    public int retiredInstructions(int core) {
        return state(core, Rv32i.STATE_RETIRED);
    }

    public int trapCause(int core) {
        return state(core, Rv32i.STATE_TRAP_CAUSE);
    }

    public int trapValue(int core) {
        return state(core, Rv32i.STATE_TRAP_VALUE);
    }

    public int exitCode(int core) {
        return state(core, Rv32i.STATE_EXIT_CODE);
    }

    public int register(int core, int register) {
        checkCore(core);
        checkRegister(register);
        return register == 0 ? 0 : registers[registerBase(core) + register];
    }

    public void setRegister(int core, int register, int value) {
        checkCore(core);
        checkRegister(register);
        if (register != 0) {
            registers[registerBase(core) + register] = value;
        }
    }

    public void loadWords(int core, int byteAddress, int... words) {
        checkCore(core);
        if ((byteAddress & 3) != 0) {
            throw new IllegalArgumentException("word image must start on a 4-byte boundary");
        }
        long byteLength = (long) words.length << 2;
        checkRange(byteAddress, byteLength);
        int destination = memoryBase(core) + (byteAddress >>> 2);
        System.arraycopy(words, 0, memory, destination, words.length);
    }

    public void loadWordsAllCores(int byteAddress, int... words) {
        for (int core = 0; core < coreCount; core++) {
            loadWords(core, byteAddress, words);
        }
    }

    public void writeBytes(int core, int byteAddress, byte[] bytes) {
        checkCore(core);
        checkRange(byteAddress, bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            writeByte(core, byteAddress + i, bytes[i] & 0xFF);
        }
    }

    public int readByteUnsigned(int core, int byteAddress) {
        checkCore(core);
        checkRange(byteAddress, 1);
        int word = memory[memoryBase(core) + (byteAddress >>> 2)];
        int shift = (byteAddress & 3) << 3;
        return word >>> shift & 0xFF;
    }

    public int readWord(int core, int byteAddress) {
        checkCore(core);
        if ((byteAddress & 3) != 0) {
            throw new IllegalArgumentException("word address is not aligned");
        }
        checkRange(byteAddress, 4);
        return memory[memoryBase(core) + (byteAddress >>> 2)];
    }

    public void writeWord(int core, int byteAddress, int value) {
        checkCore(core);
        if ((byteAddress & 3) != 0) {
            throw new IllegalArgumentException("word address is not aligned");
        }
        checkRange(byteAddress, 4);
        memory[memoryBase(core) + (byteAddress >>> 2)] = value;
    }

    public int[] snapshotRegisters() {
        return registers.clone();
    }

    public int[] snapshotState() {
        return state.clone();
    }

    public int[] snapshotMemory() {
        return memory.clone();
    }

    int[] rawMemory() {
        return memory;
    }

    int[] rawRegisters() {
        return registers;
    }

    int[] rawState() {
        return state;
    }

    int memoryBase(int core) {
        return core * memoryWordsPerCore;
    }

    int registerBase(int core) {
        return core * Rv32i.REGISTER_COUNT;
    }

    int stateBase(int core) {
        return core * Rv32i.STATE_STRIDE;
    }

    void replaceRegistersAndState(int[] deviceRegisters, int[] deviceState) {
        if (deviceRegisters.length != registers.length || deviceState.length != state.length) {
            throw new IllegalArgumentException("device state shape mismatch");
        }
        System.arraycopy(deviceRegisters, 0, registers, 0, registers.length);
        System.arraycopy(deviceState, 0, state, 0, state.length);
        for (int core = 0; core < coreCount; core++) {
            registers[registerBase(core)] = 0;
        }
    }

    void replaceMemory(int[] deviceMemory) {
        if (deviceMemory.length != memory.length) {
            throw new IllegalArgumentException("device memory shape mismatch");
        }
        System.arraycopy(deviceMemory, 0, memory, 0, memory.length);
    }

    private int state(int core, int field) {
        checkCore(core);
        return state[stateBase(core) + field];
    }

    void writeByte(int core, int byteAddress, int value) {
        checkCore(core);
        checkRange(byteAddress, 1);
        int index = memoryBase(core) + (byteAddress >>> 2);
        int shift = (byteAddress & 3) << 3;
        int mask = 0xFF << shift;
        memory[index] = memory[index] & ~mask | (value & 0xFF) << shift;
    }

    private void checkCore(int core) {
        if (core < 0 || core >= coreCount) {
            throw new IndexOutOfBoundsException("core=" + core);
        }
    }

    private static void checkRegister(int register) {
        if (register < 0 || register >= Rv32i.REGISTER_COUNT) {
            throw new IndexOutOfBoundsException("register=" + register);
        }
    }

    private void checkRange(int byteAddress, long byteLength) {
        if (byteAddress < 0 || byteLength < 0 || byteLength > memoryBytesPerCore
                || byteAddress > memoryBytesPerCore - byteLength) {
            throw new IndexOutOfBoundsException("memory range address=" + byteAddress + " length=" + byteLength);
        }
    }
}
