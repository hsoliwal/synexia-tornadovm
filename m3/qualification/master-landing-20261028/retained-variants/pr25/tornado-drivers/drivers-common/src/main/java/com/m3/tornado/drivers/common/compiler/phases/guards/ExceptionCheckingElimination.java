/*
 * Copyright (c) 2018, 2020, 2024 APT Group, Department of Computer Science,
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
 */
package com.m3.tornado.drivers.common.compiler.phases.guards;

import java.util.Optional;

import com.m3.tornado.graal.compiler.graph.Node;
import com.m3.tornado.graal.compiler.nodes.ConstantNode;
import com.m3.tornado.graal.compiler.nodes.GraphState;
import com.m3.tornado.graal.compiler.nodes.GuardNode;
import com.m3.tornado.graal.compiler.nodes.LogicConstantNode;
import com.m3.tornado.graal.compiler.nodes.LogicNode;
import com.m3.tornado.graal.compiler.nodes.StructuredGraph;
import com.m3.tornado.graal.compiler.nodes.ValueNode;
import com.m3.tornado.graal.compiler.nodes.calc.IntegerBelowNode;
import com.m3.tornado.graal.compiler.nodes.calc.IsNullNode;
import com.m3.tornado.graal.compiler.nodes.extended.GuardedNode;
import com.m3.tornado.graal.compiler.phases.BasePhase;

import com.m3.tornado.runtime.graal.phases.TornadoMidTierContext;

public class ExceptionCheckingElimination extends BasePhase<TornadoMidTierContext> {
    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    /**
     * Removes all exception checking - loop bounds and null checks.
     */
    @Override
    protected void run(StructuredGraph graph, TornadoMidTierContext context) {

        graph.getNodes().filter(GuardedNode.class::isInstance).snapshot().forEach((node) -> {
            GuardedNode guardedNode = (GuardedNode) node;
            if (guardedNode.getGuard() instanceof GuardNode guard) {

                LogicNode condition = guard.getCondition();

                if (condition instanceof IsNullNode) {
                    Node input = condition.inputs().first();

                    if (guard.isNegated()) {
                        condition.replaceFirstInput(input, LogicConstantNode.contradiction(graph));
                    } else {
                        condition.replaceFirstInput(input, LogicConstantNode.tautology(graph));
                    }

                } else if (condition instanceof IntegerBelowNode) {
                    ValueNode x = ((IntegerBelowNode) condition).getX();
                    condition.replaceFirstInput(x, graph.addOrUnique(ConstantNode.forInt(Integer.MAX_VALUE)));
                }
            }
        });
    }
}
