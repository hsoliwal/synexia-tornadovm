/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CpuRv32iEngineTest {

    @Test
    void executesRv32iAndMArithmetic() {
        Rv32iBatch batch = singleCore(
                Rv32iAssembler.addi(1, 0, 7),
                Rv32iAssembler.addi(2, 0, -3),
                Rv32iAssembler.add(3, 1, 2),
                Rv32iAssembler.sub(4, 1, 2),
                Rv32iAssembler.mul(5, 1, 2),
                Rv32iAssembler.div(6, 1, 2),
                Rv32iAssembler.rem(7, 1, 2),
                Rv32iAssembler.slt(8, 2, 1),
                Rv32iAssembler.sltu(9, 2, 1),
                Rv32iAssembler.slli(11, 1, 2),
                Rv32iAssembler.srai(12, 2, 1),
                Rv32iAssembler.addi(0, 0, 123),
                Rv32iAssembler.addi(10, 3, 0),
                Rv32iAssembler.halt());

        new CpuRv32iEngine().runSlice(batch, 100);

        assertEquals(Rv32i.STATUS_HALTED, batch.status(0));
        assertEquals(0, batch.register(0, 0));
        assertEquals(4, batch.register(0, 3));
        assertEquals(10, batch.register(0, 4));
        assertEquals(-21, batch.register(0, 5));
        assertEquals(-2, batch.register(0, 6));
        assertEquals(1, batch.register(0, 7));
        assertEquals(1, batch.register(0, 8));
        assertEquals(0, batch.register(0, 9));
        assertEquals(28, batch.register(0, 11));
        assertEquals(-2, batch.register(0, 12));
        assertEquals(4, batch.exitCode(0));
    }

    @Test
    void executesLoadsStoresAndSignedness() {
        Rv32iBatch batch = singleCore(
                Rv32iAssembler.addi(1, 0, 128),
                Rv32iAssembler.addi(2, 0, -1),
                Rv32iAssembler.sb(2, 1, 0),
                Rv32iAssembler.lb(3, 1, 0),
                Rv32iAssembler.lbu(4, 1, 0),
                Rv32iAssembler.addi(2, 0, 0x123),
                Rv32iAssembler.sh(2, 1, 2),
                Rv32iAssembler.lh(5, 1, 2),
                Rv32iAssembler.lhu(6, 1, 2),
                Rv32iAssembler.lui(7, 0x12345),
                Rv32iAssembler.addi(7, 7, 0x678),
                Rv32iAssembler.sw(7, 1, 4),
                Rv32iAssembler.lw(8, 1, 4),
                Rv32iAssembler.halt());

        new CpuRv32iEngine().runSlice(batch, 100);

        assertEquals(-1, batch.register(0, 3));
        assertEquals(255, batch.register(0, 4));
        assertEquals(0x123, batch.register(0, 5));
        assertEquals(0x123, batch.register(0, 6));
        assertEquals(0x12345678, batch.register(0, 8));
        assertEquals(0x0123_00FF, batch.readWord(0, 128));
        assertEquals(0x12345678, batch.readWord(0, 132));
    }

    @Test
    void executesBranchesJumpsAndBoundedSlices() {
        Rv32iBatch batch = singleCore(Rv32iDemo.sumOneToHundredProgram());

        new CpuRv32iEngine().runSlice(batch, 20);
        assertEquals(Rv32i.STATUS_YIELDED, batch.status(0));
        int firstRetired = batch.retiredInstructions(0);

        new CpuRv32iEngine().runSlice(batch, 1000);
        assertEquals(Rv32i.STATUS_HALTED, batch.status(0));
        assertEquals(5050, batch.register(0, 10));
        assertEquals(5050, batch.exitCode(0));
        assertTrue(batch.retiredInstructions(0) > firstRetired);
    }

    @Test
    void reportsIllegalAndMisalignedTrapsWithoutRetiringFaultingInstruction() {
        Rv32iBatch illegal = singleCore(0xFFFF_FFFF);
        new CpuRv32iEngine().runSlice(illegal, 1);
        assertEquals(Rv32i.STATUS_TRAPPED, illegal.status(0));
        assertEquals(Rv32i.TRAP_ILLEGAL_INSTRUCTION, illegal.trapCause(0));
        assertEquals(0xFFFF_FFFF, illegal.trapValue(0));
        assertEquals(0, illegal.retiredInstructions(0));

        Rv32iBatch misaligned = singleCore(
                Rv32iAssembler.addi(1, 0, 1),
                Rv32iAssembler.lw(2, 1, 0));
        new CpuRv32iEngine().runSlice(misaligned, 10);
        assertEquals(Rv32i.STATUS_TRAPPED, misaligned.status(0));
        assertEquals(Rv32i.TRAP_LOAD_ADDRESS_MISALIGNED, misaligned.trapCause(0));
        assertEquals(1, misaligned.trapValue(0));
        assertEquals(1, misaligned.retiredInstructions(0));
    }

    @Test
    void implementsMExtensionDivisionCornerCases() {
        Rv32iBatch batch = singleCore(
                Rv32iAssembler.lui(1, 0x80000),
                Rv32iAssembler.addi(2, 0, -1),
                Rv32iAssembler.div(3, 1, 2),
                Rv32iAssembler.rem(4, 1, 2),
                Rv32iAssembler.addi(5, 0, 0),
                Rv32iAssembler.div(6, 1, 5),
                Rv32iAssembler.rem(7, 1, 5),
                Rv32iAssembler.divu(8, 1, 5),
                Rv32iAssembler.remu(9, 1, 5),
                Rv32iAssembler.halt());
        new CpuRv32iEngine().runSlice(batch, 100);

        assertEquals(Integer.MIN_VALUE, batch.register(0, 3));
        assertEquals(0, batch.register(0, 4));
        assertEquals(-1, batch.register(0, 6));
        assertEquals(Integer.MIN_VALUE, batch.register(0, 7));
        assertEquals(-1, batch.register(0, 8));
        assertEquals(Integer.MIN_VALUE, batch.register(0, 9));
    }

    private static Rv32iBatch singleCore(int... program) {
        Rv32iBatch batch = new Rv32iBatch(1, 4096);
        batch.loadWords(0, 0, program);
        batch.setProgramCounter(0, 0);
        return batch;
    }
}
