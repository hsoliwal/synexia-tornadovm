/* SPDX-License-Identifier: Apache-2.0 */
package com.synexia.m3.cpugpu;

import java.util.Objects;

/**
 * Content-addressed execution receipt for one RV32IM bulk slice.
 *
 * <p>The receipt records what actually executed. A verified Tornado receipt is issued only after
 * an independent CPU run agrees on registers, architectural state and RAM.</p>
 */
public record Rv32iBulkReceipt(
        Mode mode,
        int cores,
        int instructionBudget,
        int localWork,
        String inputRoot,
        String outputRoot,
        String verificationRoot,
        String root) {

    public enum Mode {
        CPU,
        TORNADO_VERIFIED
    }

    public Rv32iBulkReceipt {
        Objects.requireNonNull(mode, "mode");
        if (cores < 1) throw new IllegalArgumentException("cores");
        if (instructionBudget < 1) throw new IllegalArgumentException("instructionBudget");
        if (localWork < 0) throw new IllegalArgumentException("localWork");
        inputRoot = Rv32iBulkExecutor.requireSha256(inputRoot, "inputRoot");
        outputRoot = Rv32iBulkExecutor.requireSha256(outputRoot, "outputRoot");
        verificationRoot = Rv32iBulkExecutor.requireSha256(verificationRoot, "verificationRoot");
        String expected =
                Rv32iBulkExecutor.receiptRoot(
                        mode,
                        cores,
                        instructionBudget,
                        localWork,
                        inputRoot,
                        outputRoot,
                        verificationRoot);
        if (root == null) {
            root = expected;
        } else if (!root.equals(expected)) {
            throw new IllegalArgumentException("receipt root mismatch");
        }
    }
}
