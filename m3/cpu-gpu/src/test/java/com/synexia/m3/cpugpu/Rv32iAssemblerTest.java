/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class Rv32iAssemblerTest {
    @Test
    void roundTripsImmediateEncodings() {
        int addi = Rv32iAssembler.addi(7, 3, -2048);
        assertEquals(-2048, Rv32i.immediateI(addi));

        int store = Rv32iAssembler.sw(9, 4, 2047);
        assertEquals(2047, Rv32i.immediateS(store));

        int backwardBranch = Rv32iAssembler.bne(1, 2, -4096);
        assertEquals(-4096, Rv32i.immediateB(backwardBranch));

        int forwardJump = Rv32iAssembler.jal(1, 1_048_574);
        assertEquals(1_048_574, Rv32i.immediateJ(forwardJump));
    }

    @Test
    void rejectsOutOfRangeOrMisalignedEncodings() {
        assertThrows(IllegalArgumentException.class, () -> Rv32iAssembler.addi(1, 0, 2048));
        assertThrows(IllegalArgumentException.class, () -> Rv32iAssembler.addi(1, 0, -2049));
        assertThrows(IllegalArgumentException.class, () -> Rv32iAssembler.beq(1, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> Rv32iAssembler.jal(1, 1));
        assertThrows(IllegalArgumentException.class, () -> Rv32iAssembler.slli(1, 2, 32));
        assertThrows(IllegalArgumentException.class, () -> Rv32iAssembler.add(32, 0, 0));
    }

    @Test
    void emitsCanonicalFenceAndSemihostingEncodings() {
        assertEquals(0x0000_000F, Rv32iAssembler.fence());
        assertEquals(0x0000_100F, Rv32iAssembler.fenceI());
        assertEquals(0x0000_0073, Rv32iAssembler.ecall());
        assertEquals(0x0010_0073, Rv32iAssembler.ebreak());
        assertEquals(Rv32i.XM3_HALT, Rv32iAssembler.halt());
    }
}
