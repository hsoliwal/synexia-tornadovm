/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */
package com.m3.tornado.drivers.opencl.graal.nodes;

import com.m3.tornado.graal.compiler.core.common.type.StampFactory;
import com.m3.tornado.graal.compiler.graph.NodeClass;
import com.m3.tornado.graal.compiler.nodeinfo.NodeInfo;
import com.m3.tornado.graal.compiler.nodes.FixedWithNextNode;
import com.m3.tornado.graal.compiler.nodes.memory.MemoryKill;
import com.m3.tornado.graal.compiler.nodes.spi.LIRLowerable;
import com.m3.tornado.graal.compiler.nodes.spi.NodeLIRBuilderTool;

import com.m3.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryIntrinsic;
import com.m3.tornado.drivers.opencl.graal.lir.OCLLIRStmt;
import com.m3.tornado.drivers.opencl.graal.lir.OCLUnary;

@NodeInfo
public class OCLBarrierNode extends FixedWithNextNode implements LIRLowerable, MemoryKill {

    public static final NodeClass<OCLBarrierNode> TYPE = NodeClass.create(OCLBarrierNode.class);

    public enum OCLMemFenceFlags {
        GLOBAL, LOCAL;
    }

    private final OCLMemFenceFlags flags;

    public OCLBarrierNode(OCLMemFenceFlags flags) {
        super(TYPE, StampFactory.forVoid());
        this.flags = flags;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        gen.getLIRGeneratorTool().append(new OCLLIRStmt.ExprStmt(new OCLUnary.Barrier(OCLUnaryIntrinsic.BARRIER, flags)));
    }
}
