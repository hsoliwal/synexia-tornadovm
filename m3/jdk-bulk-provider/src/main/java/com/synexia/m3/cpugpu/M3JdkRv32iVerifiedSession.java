/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Persistent verified TornadoVM session for repeated slices over one RV32IM batch.
 *
 * <p>The device plan remains resident across slices, but every slice is still independently
 * executed by the CPU oracle and compared across registers, architectural state and complete RAM.
 * A mismatch or device failure closes the session and produces no verified receipt.</p>
 */
public final class M3JdkRv32iVerifiedSession implements AutoCloseable {
    private static final String VERIFY_DOMAIN = "M3_RV32I_CPU_TORNADO_PARITY_V1";

    private final Rv32iBatch batch;
    private final int localWork;
    private final CpuRv32iEngine cpu = new CpuRv32iEngine();
    private final TornadoRv32iSession tornado;
    private boolean closed;
    private long verifiedSlices;

    public M3JdkRv32iVerifiedSession(Rv32iBatch batch, int localWork) {
        this.batch = Objects.requireNonNull(batch, "batch");
        if (localWork < 1) {
            throw new IllegalArgumentException("localWork");
        }
        this.localWork = localWork;
        this.tornado = new TornadoRv32iSession(batch, localWork);
    }

    public synchronized Rv32iBulkReceipt runVerifiedSlice(int instructionBudget) {
        requireOpen();
        if (instructionBudget < 1) {
            throw new IllegalArgumentException("instructionBudget");
        }

        String inputRoot = Rv32iBulkExecutor.stateRoot(batch);
        Rv32iBatch expected = batch.copy();
        cpu.runSlice(expected, instructionBudget);

        try {
            tornado.runSlice(instructionBudget);
            tornado.synchronizeAll();
            requireEqual("registers", expected.snapshotRegisters(), batch.snapshotRegisters());
            requireEqual("state", expected.snapshotState(), batch.snapshotState());
            requireEqual("memory", expected.snapshotMemory(), batch.snapshotMemory());
        } catch (RuntimeException failure) {
            closeAfterFailure(failure);
            throw failure;
        }

        String outputRoot = Rv32iBulkExecutor.stateRoot(batch);
        String verificationRoot =
                sha256(
                        VERIFY_DOMAIN,
                        Rv32iBulkExecutor.stateRoot(expected),
                        outputRoot,
                        Integer.toString(batch.coreCount()),
                        Integer.toString(instructionBudget));
        verifiedSlices++;
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

    public Rv32iBatch batch() {
        return batch;
    }

    public int localWork() {
        return localWork;
    }

    public synchronized long verifiedSlices() {
        return verifiedSlices;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            tornado.close();
        } catch (Exception failure) {
            throw new IllegalStateException("cannot close TornadoVM RV32IM session", failure);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("verified RV32IM session is closed");
        }
    }

    private void closeAfterFailure(RuntimeException original) {
        if (closed) {
            return;
        }
        closed = true;
        try {
            tornado.close();
        } catch (Exception closeFailure) {
            original.addSuppressed(closeFailure);
        }
    }

    private static void requireEqual(String name, int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new IllegalStateException("CPU/Tornado mismatch in " + name);
        }
    }

    private static String sha256(String domain, String... values) {
        MessageDigest digest = digest();
        frame(digest, domain);
        for (String value : values) {
            frame(digest, value);
        }
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
}
