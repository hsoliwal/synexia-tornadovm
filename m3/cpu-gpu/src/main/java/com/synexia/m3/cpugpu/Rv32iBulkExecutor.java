/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Bulk execution facade over the existing CPU oracle and TornadoVM RV32IM session.
 *
 * <p>The same guest program/state is applied over many independent virtual cores. CPU mode is the
 * deterministic reference. Accelerated mode is admitted only after full registers/state/RAM parity
 * with an independently executed CPU copy.</p>
 */
public final class Rv32iBulkExecutor {
    private static final String RECEIPT_DOMAIN = "M3_RV32I_BULK_RECEIPT_V1";
    private static final String VERIFY_DOMAIN = "M3_RV32I_CPU_TORNADO_PARITY_V1";

    private final CpuRv32iEngine cpu = new CpuRv32iEngine();

    public Rv32iBulkReceipt executeCpu(
            Rv32iBatch batch,
            int instructionBudget) {
        Objects.requireNonNull(batch, "batch");
        String inputRoot = stateRoot(batch);
        cpu.runSlice(batch, instructionBudget);
        String outputRoot = stateRoot(batch);
        return new Rv32iBulkReceipt(
                Rv32iBulkReceipt.Mode.CPU,
                batch.coreCount(),
                instructionBudget,
                0,
                inputRoot,
                outputRoot,
                sha256(VERIFY_DOMAIN, "CPU_ONLY", outputRoot),
                null);
    }

    public Rv32iBulkReceipt executeVerifiedTornado(
            Rv32iBatch batch,
            int instructionBudget,
            int localWork) throws Exception {
        Objects.requireNonNull(batch, "batch");
        if (localWork < 1) {
            throw new IllegalArgumentException("localWork");
        }

        String inputRoot = stateRoot(batch);
        Rv32iBatch expected = batch.copy();
        cpu.runSlice(expected, instructionBudget);

        try (TornadoRv32iSession session =
                new TornadoRv32iSession(batch, localWork)) {
            session.runSlice(instructionBudget);
            session.synchronizeAll();
        }

        requireEqual("registers", expected.snapshotRegisters(), batch.snapshotRegisters());
        requireEqual("state", expected.snapshotState(), batch.snapshotState());
        requireEqual("memory", expected.snapshotMemory(), batch.snapshotMemory());

        String outputRoot = stateRoot(batch);
        String verificationRoot =
                sha256(
                        VERIFY_DOMAIN,
                        stateRoot(expected),
                        outputRoot,
                        Integer.toString(batch.coreCount()),
                        Integer.toString(instructionBudget));
        return new Rv32iBulkReceipt(
                Rv32iBulkReceipt.Mode.TORNADO_VERIFIED,
                batch.coreCount(),
                instructionBudget,
                localWork,
                inputRoot,
                outputRoot,
                verificationRoot,
                null);
    }

    static String stateRoot(Rv32iBatch batch) {
        MessageDigest digest = digest();
        frame(digest, "M3_RV32I_BATCH_STATE_V1");
        frame(digest, Integer.toString(batch.coreCount()));
        frame(digest, Integer.toString(batch.memoryBytesPerCore()));
        ints(digest, batch.snapshotRegisters());
        ints(digest, batch.snapshotState());
        ints(digest, batch.snapshotMemory());
        return HexFormat.of().formatHex(digest.digest());
    }

    static String receiptRoot(
            Rv32iBulkReceipt.Mode mode,
            int cores,
            int instructionBudget,
            int localWork,
            String inputRoot,
            String outputRoot,
            String verificationRoot) {
        return sha256(
                RECEIPT_DOMAIN,
                mode.name(),
                Integer.toString(cores),
                Integer.toString(instructionBudget),
                Integer.toString(localWork),
                inputRoot,
                outputRoot,
                verificationRoot);
    }

    static String requireSha256(String value, String field) {
        String checked = Objects.requireNonNull(value, field);
        if (!checked.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field);
        }
        return checked;
    }

    private static void requireEqual(
            String name,
            int[] expected,
            int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("CPU/Tornado mismatch in " + name);
        }
    }

    private static String sha256(String domain, String... values) {
        MessageDigest digest = digest();
        frame(digest, domain);
        for (String value : values) frame(digest, value);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new ExceptionInInitializerError(impossible);
        }
    }

    private static void frame(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void ints(MessageDigest digest, int[] values) {
        frame(digest, Integer.toString(values.length));
        ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES * 4096);
        for (int value : values) {
            if (buffer.remaining() < Integer.BYTES) {
                digest.update(buffer.array(), 0, buffer.position());
                buffer.clear();
            }
            buffer.putInt(value);
        }
        if (buffer.position() > 0) {
            digest.update(buffer.array(), 0, buffer.position());
        }
    }
}
