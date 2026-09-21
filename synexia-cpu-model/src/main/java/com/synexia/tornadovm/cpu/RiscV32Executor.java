/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

/**
 * Backend-neutral executor for the exact same {@link RiscV32Kernel}.
 */
public interface RiscV32Executor {

    /**
     * Run the machine until every core stops or {@code maxQuanta} have executed.
     *
     * @param machine machine state
     * @param instructionsPerQuantum sequential instructions executed by each accelerator work-item per dispatch
     * @param maxQuanta maximum dispatches/reference-kernel invocations
     * @return execution summary
     */
    RiscV32ExecutionResult execute(RiscV32Machine machine, int instructionsPerQuantum, int maxQuanta);
}
