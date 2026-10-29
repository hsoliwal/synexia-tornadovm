/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2026, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
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
package com.m3.tornado.drivers.metal.graal.lir;

import java.util.HashMap;

import com.m3.tornado.graal.compiler.core.common.LIRKind;
import com.m3.tornado.graal.compiler.lir.ConstantValue;
import com.m3.tornado.graal.compiler.lir.Variable;
import com.m3.tornado.graal.compiler.nodes.NodeView;
import com.m3.tornado.graal.compiler.nodes.ParameterNode;

import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.Local;
import jdk.vm.ci.meta.PrimitiveConstant;
import jdk.vm.ci.meta.Value;
import com.m3.tornado.drivers.common.logging.Logger;
import com.m3.tornado.drivers.metal.MetalTargetDescription;
import com.m3.tornado.drivers.metal.graal.MetalArchitecture;
import com.m3.tornado.drivers.metal.graal.MetalArchitecture.MetalMemoryBase;
import com.m3.tornado.drivers.metal.graal.asm.MetalAssembler.MetalBinaryIntrinsic;
import com.m3.tornado.drivers.metal.graal.asm.MetalAssembler.MetalUnaryOp;
import com.m3.tornado.drivers.metal.graal.compiler.MetalLIRGenerator;
import com.m3.tornado.drivers.metal.graal.lir.MetalLIRStmt.AssignStmt;
import com.m3.tornado.drivers.metal.graal.lir.MetalLIRStmt.VectorLoadStmt;
import com.m3.tornado.drivers.metal.graal.lir.MetalUnary.MemoryAccess;
import com.m3.tornado.drivers.metal.graal.lir.MetalUnary.MetalAddressCast;
import com.m3.tornado.drivers.metal.graal.nodes.vector.VectorUtil;
import com.m3.tornado.runtime.common.MetalTokens;

public class MetalGenTool {

    protected MetalLIRGenerator gen;

    private final HashMap<ParameterNode, Variable> parameterToVariable = new HashMap<>();

    public MetalGenTool(MetalLIRGenerator gen) {
        this.gen = gen;
    }

    public void emitVectorLoad(AllocatableValue result, MetalBinaryIntrinsic op, Value index, MetalAddressCast cast, MemoryAccess address) {
        Logger.traceBuildLIR(Logger.BACKEND.Metal, "emitVectorLoad: %s = (%s) %s", result.toString(), result.getPlatformKind().toString(), address.toString());
        gen.append(new VectorLoadStmt(result, op, index, cast, address));
    }

    private String getParameterName(Local local) {
        String parameterName = local.getName();
        if (MetalTokens.metalTokens.contains(parameterName)) {
            parameterName = "_" + parameterName;
        }
        return parameterName;
    }

    public Value emitParameterLoad(Local local, ParameterNode paramNode) {

        Logger.traceBuildLIR(Logger.BACKEND.Metal, "emitParameterLoad: stamp=%s", paramNode.stamp(NodeView.DEFAULT));

        LIRKind lirKind = gen.getLIRKind(paramNode.stamp(NodeView.DEFAULT));
        MetalKind metalKind = (MetalKind) lirKind.getPlatformKind();
        MetalTargetDescription oclTarget = gen.target();

        Variable result = (metalKind.isVector()) ? gen.newVariable(LIRKind.value(oclTarget.getMetalKind(JavaKind.Object))) : gen.newVariable(lirKind);
        String parameterName = getParameterName(local);
        gen.append(new AssignStmt(result, new MetalNullary.Parameter(MetalUnaryOp.CAST_TO_ULONG + parameterName, lirKind)));
        parameterToVariable.put(paramNode, result);

        if (metalKind.isVector()) {

            Variable vector = gen.newVariable(lirKind);
            MetalMemoryBase base = MetalArchitecture.globalSpace;
            MetalBinaryIntrinsic intrinsic = VectorUtil.resolveLoadIntrinsic(metalKind);
            MetalAddressCast cast = new MetalAddressCast(base, LIRKind.value(metalKind.getElementKind()));
            MemoryAccess address = new MemoryAccess(base, result);

            emitVectorLoad(vector, intrinsic, new ConstantValue(LIRKind.value(MetalKind.INT), PrimitiveConstant.INT_0), cast, address);
            result = vector;
        }

        return result;
    }

    public HashMap<ParameterNode, Variable> getParameterToVariable() {
        return parameterToVariable;
    }
}
