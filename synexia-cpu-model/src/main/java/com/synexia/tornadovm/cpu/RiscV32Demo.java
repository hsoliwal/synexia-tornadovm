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
 * Four independent virtual CPUs executing the same image with different register inputs.
 */
public final class RiscV32Demo {

    private RiscV32Demo() {
    }

    public static void main(String[] args) {
        boolean tornado = args.length > 0 && "tornado".equalsIgnoreCase(args[0]);
        RiscV32Executor executor = tornado ? new TornadoRiscV32Executor() : new JvmRiscV32Executor();

        RiscV32Machine machine = new RiscV32Machine(4, 4096);
        machine.loadProgramAll(0,
                add(3, 1, 2),
                mul(4, 3, 2),
                sw(4, 0, 256),
                ebreak());

        for (int core = 0; core < machine.cores(); core++) {
            machine.register(core, 1, core + 1);
            machine.register(core, 2, 10);
        }

        RiscV32ExecutionResult result = executor.execute(machine, 128, 32);
        System.out.println(result);
        for (int core = 0; core < machine.cores(); core++) {
            System.out.println("core=" + core
                    + " x3=" + machine.register(core, 3)
                    + " x4=" + machine.register(core, 4)
                    + " mem[256]=" + machine.readWord(core, 256)
                    + " status=" + RiscV32.statusName(machine.status(core)));
        }
    }
}
