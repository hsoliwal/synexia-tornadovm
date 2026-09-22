/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Metadata discovered while loading an ELF image.
 */
public final class RiscV32ElfImage {

    private final int entryPoint;
    private final int executableBase;
    private final int executableEnd;

    RiscV32ElfImage(int entryPoint, int executableBase, int executableEnd) {
        this.entryPoint = entryPoint;
        this.executableBase = executableBase;
        this.executableEnd = executableEnd;
    }

    public int entryPoint() {
        return entryPoint;
    }

    public boolean hasExecutableRange() {
        return executableEnd > executableBase;
    }

    public int executableBase() {
        return executableBase;
    }

    public int executableEnd() {
        return executableEnd;
    }

    public int executableBytes() {
        return Math.max(0, executableEnd - executableBase);
    }

    @Override
    public String toString() {
        return "RiscV32ElfImage{entryPoint=" + entryPoint
                + ", executableBase=" + executableBase
                + ", executableEnd=" + executableEnd
                + ", executableBytes=" + executableBytes() + "}";
    }
}
