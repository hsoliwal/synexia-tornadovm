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
 *
 */
package com.m3.tornado.drivers.common.compiler.phases.analysis;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.m3.tornado.graal.compiler.graph.NodeBitMap;
import com.m3.tornado.graal.compiler.nodes.ConstantNode;
import com.m3.tornado.graal.compiler.nodes.GraphState;
import com.m3.tornado.graal.compiler.nodes.LoopBeginNode;
import com.m3.tornado.graal.compiler.nodes.StructuredGraph;
import com.m3.tornado.graal.compiler.nodes.ValueNode;
import com.m3.tornado.graal.compiler.nodes.loop.LoopEx;
import com.m3.tornado.graal.compiler.nodes.loop.LoopFragmentInside;
import com.m3.tornado.graal.compiler.nodes.loop.LoopsData;
import com.m3.tornado.graal.compiler.phases.BasePhase;

import com.m3.tornado.runtime.common.TornadoLogger;
import com.m3.tornado.runtime.domain.DomainTree;
import com.m3.tornado.runtime.domain.IntDomain;
import com.m3.tornado.runtime.graal.nodes.ParallelRangeNode;
import com.m3.tornado.runtime.graal.nodes.TornadoLoopsData;
import com.m3.tornado.runtime.graal.phases.TornadoHighTierContext;

/**
 * It analyses the loop index space and determines the correct indices using
 * strides in loops.
 *
 */
public class TornadoShapeAnalysis extends BasePhase<TornadoHighTierContext> {

    private TornadoLogger logger = new TornadoLogger(this.getClass());

    private static int getIntegerValue(ValueNode value) {
        if (value instanceof ConstantNode) {
            return value.asJavaConstant().asInt();
        } else {
            return Integer.MIN_VALUE;
        }
    }

    @Override
    public Optional<NotApplicable> notApplicableTo(GraphState graphState) {
        return ALWAYS_APPLICABLE;
    }

    private int getMaxLevelNestedLoops(StructuredGraph graph) {
        int dimensions = 1;

        if (graph.hasLoops()) {
            final LoopsData data = new TornadoLoopsData(graph);
            data.detectCountedLoops();

            final List<LoopEx> loops = data.outerFirst();

            for (LoopEx loopEx : loops) {
                LoopFragmentInside inside = loopEx.inside();
                NodeBitMap nodes = inside.nodes();

                List<LoopBeginNode> snapshot = nodes.filter(LoopBeginNode.class).snapshot();
                if (snapshot.size() > 1) {
                    dimensions = Math.max(dimensions, snapshot.size());
                }
            }
        }
        return dimensions;
    }

    private void setDomainTree(int dimensions, List<ParallelRangeNode> ranges, TornadoHighTierContext context) {
        final DomainTree domainTree = new DomainTree(dimensions);

        int lastIndex = -1;
        boolean valid = true;
        for (int i = 0; i < dimensions; i++) {
            final ParallelRangeNode range = ranges.get(i);
            final int index = range.index();
            if (index != lastIndex && getIntegerValue(range.offset().value()) != Integer.MIN_VALUE && getIntegerValue(range.stride().value()) != Integer.MIN_VALUE && getIntegerValue(range
                    .value()) != Integer.MIN_VALUE) {
                domainTree.set(index, new IntDomain(getIntegerValue(range.offset().value()), getIntegerValue(range.stride().value()), getIntegerValue(range.value())));
            } else {
                valid = false;
                logger.info("unsupported multiple parallel loops");
                break;
            }
            lastIndex = index;
        }

        if (valid) {
            logger.trace("loop nest depth = %d\n", domainTree.getDepth());
            logger.debug("discovered parallel domain: %s\n", domainTree);
            context.getMeta().setDomain(domainTree);
        }
    }

    private boolean shouldPerformShapeAnalysis(TornadoHighTierContext context) {
        return context.hasMeta() && context.getMeta().getDomain() == null;
    }

    @Override
    protected void run(StructuredGraph graph, TornadoHighTierContext context) {
        /*
         * An instance of {@link
         * uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData} is assigned per
         * task. If there is a callee that does not get inlined then we might overwrite
         * the domain for the task method (set in a previous run of this phase) with the
         * domain of the callee. We don't care about the domains of callees at the
         * moment, since we support {@link
         * uk.ac.manchester.tornado.api.annotations.Parallel} annotations only on the
         * root task method. To circumvent the overwriting, we have the null check in
         * the shouldPerformShapeAnalysis method.
         */
        if (!shouldPerformShapeAnalysis(context)) {
            return;
        }

        int dimensions = getMaxLevelNestedLoops(graph);

        final List<ParallelRangeNode> ranges = graph.getNodes().filter(ParallelRangeNode.class).snapshot();
        if (ranges.size() < dimensions) {
            dimensions = ranges.size();
        }
        Collections.sort(ranges);

        setDomainTree(dimensions, ranges, context);

    }

}
