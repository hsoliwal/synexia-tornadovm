/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
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
package com.m3.tornado.drivers.opencl.graal.lir;

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
import com.m3.tornado.drivers.opencl.OCLTargetDescription;
import com.m3.tornado.drivers.opencl.graal.OCLArchitecture;
import com.m3.tornado.drivers.opencl.graal.OCLArchitecture.OCLMemoryBase;
import com.m3.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLBinaryIntrinsic;
import com.m3.tornado.drivers.opencl.graal.asm.OCLAssembler.OCLUnaryOp;
import com.m3.tornado.drivers.opencl.graal.compiler.OCLLIRGenerator;
import com.m3.tornado.drivers.opencl.graal.lir.OCLLIRStmt.AssignStmt;
import com.m3.tornado.drivers.opencl.graal.lir.OCLLIRStmt.VectorLoadStmt;
import com.m3.tornado.drivers.opencl.graal.lir.OCLUnary.MemoryAccess;
import com.m3.tornado.drivers.opencl.graal.lir.OCLUnary.OCLAddressCast;
import com.m3.tornado.drivers.opencl.graal.nodes.vector.VectorUtil;
import com.m3.tornado.runtime.common.OCLTokens;

public class OCLGenTool {

    protected OCLLIRGenerator gen;

    private final HashMap<ParameterNode, Variable> parameterToVariable = new HashMap<>();

    public OCLGenTool(OCLLIRGenerator gen) {
        this.gen = gen;
    }

    public void emitVectorLoad(AllocatableValue result, OCLBinaryIntrinsic op, Value index, OCLAddressCast cast, MemoryAccess address) {
        Logger.traceBuildLIR(Logger.BACKEND.OpenCL, "emitVectorLoad: %s = (%s) %s", result.toString(), result.getPlatformKind().toString(), address.toString());
        gen.append(new VectorLoadStmt(result, op, index, cast, address));
    }

    private String getParameterName(Local local) {
        String parameterName = local.getName();
        if (OCLTokens.openCLTokens.contains(parameterName)) {
            parameterName = "_" + parameterName;
        }
        return parameterName;
    }

    public Value emitParameterLoad(Local local, ParameterNode paramNode) {

        Logger.traceBuildLIR(Logger.BACKEND.OpenCL, "emitParameterLoad: stamp=%s", paramNode.stamp(NodeView.DEFAULT));

        LIRKind lirKind = gen.getLIRKind(paramNode.stamp(NodeView.DEFAULT));
        OCLKind oclKind = (OCLKind) lirKind.getPlatformKind();
        OCLTargetDescription oclTarget = gen.target();

        Variable result = (oclKind.isVector()) ? gen.newVariable(LIRKind.value(oclTarget.getOCLKind(JavaKind.Object))) : gen.newVariable(lirKind);
        String parameterName = getParameterName(local);
        gen.append(new AssignStmt(result, new OCLNullary.Parameter(OCLUnaryOp.CAST_TO_ULONG + parameterName, lirKind)));
        parameterToVariable.put(paramNode, result);

        if (oclKind.isVector()) {

            Variable vector = gen.newVariable(lirKind);
            OCLMemoryBase base = OCLArchitecture.globalSpace;
            OCLBinaryIntrinsic intrinsic = VectorUtil.resolveLoadIntrinsic(oclKind);
            OCLAddressCast cast = new OCLAddressCast(base, LIRKind.value(oclKind.getElementKind()));
            MemoryAccess address = new MemoryAccess(base, result);

            emitVectorLoad(vector, intrinsic, new ConstantValue(LIRKind.value(OCLKind.INT), PrimitiveConstant.INT_0), cast, address);
            result = vector;
        }

        return result;
    }

    public HashMap<ParameterNode, Variable> getParameterToVariable() {
        return parameterToVariable;
    }
}
