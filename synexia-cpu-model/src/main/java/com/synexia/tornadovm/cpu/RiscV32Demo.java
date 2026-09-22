/*
 * Copyright 2026 Synexia contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.synexia.tornadovm.cpu;

import static com.synexia.tornadovm.cpu.RiscV32Assembler.add;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.ebreak;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.mul;
import static com.synexia.tornadovm.cpu.RiscV32Assembler.sw;

/**
 * Four independent virtual CPUs executing the same prepared image with different register inputs.
 *
 * <p>The demo intentionally uses {@link RiscV32ExecutionEngine}: callers should not have to choose
 * interpreter versus compiled-tier executors manually.
 */
public final class RiscV32Demo {

    private RiscV32Demo() {
    }

    public static void main(String[] args) {
        boolean tornado = args.length > 0 && "tornado".equalsIgnoreCase(args[0]);
        RiscV32ExecutionEngine engine = tornado
                ? RiscV32ExecutionEngine.tornado()
                : RiscV32ExecutionEngine.jvm();

        RiscV32Machine machine = new RiscV32Machine(4, 4096);
        int[] program = {
                add(3, 1, 2),
                mul(4, 3, 2),
                sw(4, 0, 256),
                ebreak()
        };
        machine.loadProgramAll(0, program);

        for (int core = 0; core < machine.cores(); core++) {
            machine.register(core, 1, core + 1);
            machine.register(core, 2, 10);
        }

        RiscV32CompilationStats compilation =
                engine.prepareCode(machine, 0, 0, program.length * Integer.BYTES);
        RiscV32ExecutionResult result = engine.execute(machine, 128, 32);

        System.out.println(compilation);
        System.out.println(result);
        for (int core = 0; core < machine.cores(); core++) {
            System.out.println("core=" + core
                    + " x3=" + machine.register(core, 3)
                    + " x4=" + machine.register(core, 4)
                    + " mem[256]=" + machine.readWord(core, 256)
                    + " compiledBlocks=" + machine.compiledBlockExecutions(core)
                    + " status=" + RiscV32.statusName(machine.status(core)));
        }
    }
}
