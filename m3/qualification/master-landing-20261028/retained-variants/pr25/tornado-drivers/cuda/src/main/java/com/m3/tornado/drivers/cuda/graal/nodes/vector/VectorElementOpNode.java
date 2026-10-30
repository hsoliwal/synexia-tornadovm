/*
 * Copyright (c) 2020, 2024, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
 * Copyright (c) 2018, 2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
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
package com.m3.tornado.drivers.cuda.graal.nodes.vector;

import static com.m3.tornado.api.exceptions.TornadoInternalError.guarantee;
import static com.m3.tornado.api.exceptions.TornadoInternalError.shouldNotReachHere;

import com.m3.tornado.graal.compiler.core.common.LIRKind;
import com.m3.tornado.graal.compiler.core.common.type.ObjectStamp;
import com.m3.tornado.graal.compiler.core.common.type.Stamp;
import com.m3.tornado.graal.compiler.core.common.type.StampFactory;
import com.m3.tornado.graal.compiler.graph.NodeClass;
import com.m3.tornado.graal.compiler.lir.ConstantValue;
import com.m3.tornado.graal.compiler.nodeinfo.InputType;
import com.m3.tornado.graal.compiler.nodeinfo.NodeInfo;
import com.m3.tornado.graal.compiler.nodes.ConstantNode;
import com.m3.tornado.graal.compiler.nodes.NodeView;
import com.m3.tornado.graal.compiler.nodes.ValueNode;
import com.m3.tornado.graal.compiler.nodes.calc.FloatingNode;
import com.m3.tornado.graal.compiler.nodes.spi.LIRLowerable;
import com.m3.tornado.graal.compiler.nodes.spi.NodeLIRBuilderTool;

import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.Value;
import com.m3.tornado.drivers.cuda.graal.lir.CUDAKind;
import com.m3.tornado.drivers.cuda.graal.lir.CUDAVectorElementSelect;

/**
 * The {@code LoadIndexedNode} represents a read from an element of an array.
 */
@NodeInfo(nameTemplate = "Op .s{p#lane}")
public abstract class VectorElementOpNode extends FloatingNode implements LIRLowerable, Comparable<VectorElementOpNode> {

    public static final NodeClass<VectorElementOpNode> TYPE = NodeClass.create(VectorElementOpNode.class);
    protected final CUDAKind oclKind;
    @Input(InputType.Extension)
    ValueNode vector;
    @Input
    ValueNode lane;

    protected VectorElementOpNode(NodeClass<? extends VectorElementOpNode> c, CUDAKind kind, ValueNode vector, ValueNode lane) {
        super(c, StampFactory.forKind(kind.asJavaKind()));
        this.oclKind = kind;
        this.vector = vector;
        this.lane = lane;
        Stamp vectorStamp = vector.stamp(NodeView.DEFAULT);
        CUDAKind vectorKind;
        if (vectorStamp instanceof ObjectStamp) {
            ObjectStamp objectStamp = (ObjectStamp) vector.stamp(NodeView.DEFAULT);
            if (objectStamp.type() != null) {
                vectorKind = CUDAKind.fromResolvedJavaType(objectStamp.type());
                guarantee(vectorKind.isVector(), "Cannot apply vector operation to non-vector type: %s", vectorKind);
                guarantee(vectorKind.getVectorLength() >= laneId(), "Invalid lane %d on type %s", laneId(), oclKind);
            }
        } else {
            shouldNotReachHere("invalid type on vector operation: %s (stamp=%s (class=%s))", vector, vector.stamp(NodeView.DEFAULT), vector.stamp(NodeView.DEFAULT).getClass().getName());
        }

    }

    @Override
    public int compareTo(VectorElementOpNode o) {
        return Integer.compare(laneId(), o.laneId());
    }

    @Override
    public boolean inferStamp() {
        return updateStamp(StampFactory.forKind(oclKind.asJavaKind()));
    }

    public final int laneId() {
        guarantee(lane instanceof ConstantNode, "Invalid lane: %s", lane);
        return (lane instanceof ConstantNode) ? lane.asJavaConstant().asInt() : -1;
    }

    public final ValueNode getLaneId() {
        return this.lane;
    }

    public ValueNode getVector() {
        return vector;
    }

    @Override
    public void generate(NodeLIRBuilderTool gen) {
        guarantee(vector != null, "vector is null");
        Value targetVector = gen.operand(getVector());
        guarantee(targetVector != null, "vector is null");
        final CUDAVectorElementSelect element = new CUDAVectorElementSelect(gen.getLIRGeneratorTool().getLIRKind(stamp), targetVector, new ConstantValue(LIRKind.value(CUDAKind.INT), JavaConstant.forInt(
                laneId())));
        gen.setResult(this, element);
    }

}
