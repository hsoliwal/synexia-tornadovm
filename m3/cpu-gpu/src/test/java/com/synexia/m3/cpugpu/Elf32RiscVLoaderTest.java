/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

final class Elf32RiscVLoaderTest {
    @Test
    void mapsLoadSegmentZeroFillsBssAndRunsEntry() {
        int[] program = {
                Rv32iAssembler.addi(10, 0, 77),
                Rv32iAssembler.halt()
        };
        byte[] elf = executable(program, 64, 16);
        Rv32iBatch batch = new Rv32iBatch(1, 4096);
        batch.writeWord(0, 72, 0xA5A5_A5A5);
        batch.writeWord(0, 76, 0x5A5A_5A5A);

        assertEquals(64, Elf32RiscVLoader.load(elf, batch, 0));
        assertEquals(program[0], batch.readWord(0, 64));
        assertEquals(program[1], batch.readWord(0, 68));
        assertEquals(0, batch.readWord(0, 72));
        assertEquals(0, batch.readWord(0, 76));

        new CpuRv32iEngine().runSlice(batch, 16);
        assertEquals(Rv32i.STATUS_HALTED, batch.status(0));
        assertEquals(77, batch.exitCode(0));
    }

    @Test
    void rejectsWrongMachineAndOutOfRangeSegment() {
        byte[] wrongMachine = executable(new int[] { Rv32iAssembler.halt() }, 0, 4);
        put16(wrongMachine, 18, 62);
        assertThrows(IllegalArgumentException.class,
                () -> Elf32RiscVLoader.load(wrongMachine, new Rv32iBatch(1, 4096), 0));

        byte[] tooLarge = executable(new int[] { Rv32iAssembler.halt() }, 4092, 8);
        assertThrows(IllegalArgumentException.class,
                () -> Elf32RiscVLoader.load(tooLarge, new Rv32iBatch(1, 4096), 0));
    }

    private static byte[] executable(int[] words, int guestAddress, int memorySize) {
        int payloadOffset = 0x100;
        byte[] image = new byte[payloadOffset + words.length * Integer.BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(image).order(ByteOrder.LITTLE_ENDIAN);
        image[0] = 0x7F;
        image[1] = 'E';
        image[2] = 'L';
        image[3] = 'F';
        image[4] = 1;
        image[5] = 1;
        image[6] = 1;
        buffer.putShort(16, (short) 2);
        buffer.putShort(18, (short) 243);
        buffer.putInt(20, 1);
        buffer.putInt(24, guestAddress);
        buffer.putInt(28, 52);
        buffer.putShort(40, (short) 52);
        buffer.putShort(42, (short) 32);
        buffer.putShort(44, (short) 1);

        int ph = 52;
        buffer.putInt(ph, 1);
        buffer.putInt(ph + 4, payloadOffset);
        buffer.putInt(ph + 8, guestAddress);
        buffer.putInt(ph + 12, guestAddress);
        buffer.putInt(ph + 16, words.length * Integer.BYTES);
        buffer.putInt(ph + 20, memorySize);
        buffer.putInt(ph + 24, 5);
        buffer.putInt(ph + 28, 4);

        buffer.position(payloadOffset);
        for (int word : words) {
            buffer.putInt(word);
        }
        return image;
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }
}
