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
 */
package com.m3.tornado.drivers.cuda.graal.compiler;

import jdk.vm.ci.meta.MetaAccessProvider;
import com.m3.tornado.graal.compiler.loop.phases.ConvertDeoptimizeToGuardPhase;
import com.m3.tornado.graal.compiler.loop.phases.LoopFullUnrollPhase;
import com.m3.tornado.graal.compiler.nodes.loop.DefaultLoopPolicies;
import com.m3.tornado.graal.compiler.nodes.loop.LoopPolicies;
import com.m3.tornado.graal.compiler.options.OptionValues;
import com.m3.tornado.graal.compiler.phases.common.CanonicalizerPhase;
import com.m3.tornado.graal.compiler.phases.common.DeadCodeEliminationPhase;
import com.m3.tornado.graal.compiler.phases.common.HighTierLoweringPhase;
import com.m3.tornado.graal.compiler.phases.common.IterativeConditionalEliminationPhase;
import com.m3.tornado.graal.compiler.phases.common.inlining.InliningPhase;
import com.m3.tornado.graal.compiler.phases.schedule.SchedulePhase;
import com.m3.tornado.graal.compiler.virtual.phases.ea.PartialEscapePhase;
import com.m3.tornado.api.TornadoDeviceContext;
import com.m3.tornado.drivers.common.compiler.phases.analysis.TornadoShapeAnalysis;
import com.m3.tornado.drivers.common.compiler.phases.guards.ExceptionSuppression;
import com.m3.tornado.drivers.common.compiler.phases.guards.TornadoValueTypeCleanup;
import com.m3.tornado.drivers.common.compiler.phases.memalloc.TornadoFieldAccessFixup;
import com.m3.tornado.drivers.common.compiler.phases.memalloc.TornadoLocalMemoryAllocation;
import com.m3.tornado.drivers.common.compiler.phases.memalloc.TornadoNewArrayDevirtualizationReplacement;
import com.m3.tornado.drivers.common.compiler.phases.memalloc.TornadoPrivateArrayPiRemoval;
import com.m3.tornado.drivers.cuda.graal.phases.TornadoBatchGlobalIndexOffset;
import com.m3.tornado.drivers.cuda.graal.phases.TornadoHalfFloatReplacement;
import com.m3.tornado.drivers.cuda.graal.phases.TornadoCUDAIntrinsicsReplacements;
import com.m3.tornado.drivers.cuda.graal.phases.TornadoParallelScheduler;
import com.m3.tornado.drivers.cuda.graal.phases.TornadoTaskSpecialisation;
import com.m3.tornado.runtime.common.TornadoOptions;
import com.m3.tornado.runtime.graal.compiler.TornadoHighTier;
import com.m3.tornado.runtime.graal.phases.TornadoInliningPolicy;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoFullInliningPolicy;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoPartialInliningPolicy;

import static com.m3.tornado.graal.compiler.core.common.GraalOptions.ConditionalElimination;
import static com.m3.tornado.graal.compiler.core.common.GraalOptions.OptConvertDeoptsToGuards;
import static com.m3.tornado.graal.compiler.core.common.GraalOptions.PartialEscapeAnalysis;
import static com.m3.tornado.graal.compiler.core.phases.HighTier.Options.Inline;
import static com.m3.tornado.graal.compiler.phases.common.DeadCodeEliminationPhase.Optionality.Optional;

public class CUDAHighTier extends TornadoHighTier {

    public CUDAHighTier(OptionValues options, TornadoDeviceContext deviceContext, CanonicalizerPhase.CustomSimplification customCanonicalizer, MetaAccessProvider metaAccessProvider) {
        super(customCanonicalizer);

        CanonicalizerPhase canonicalizer = createCanonicalizerPhase(options, customCanonicalizer);
        appendPhase(canonicalizer);

        if (Inline.getValue(options)) {
            TornadoInliningPolicy inliningPolicy = (TornadoOptions.FULL_INLINING) ? new TornadoFullInliningPolicy() : new TornadoPartialInliningPolicy();
            appendPhase(new InliningPhase(inliningPolicy, canonicalizer));
            appendPhase(new DeadCodeEliminationPhase(Optional));
            if (ConditionalElimination.getValue(options)) {
                appendPhase(canonicalizer);
                appendPhase(new IterativeConditionalEliminationPhase(canonicalizer, false));
            }
        }
        appendPhase(new TornadoTaskSpecialisation(canonicalizer));
        appendPhase(new TornadoBatchGlobalIndexOffset());
        appendPhase(new TornadoFieldAccessFixup());
        appendPhase(canonicalizer);
        appendPhase(new DeadCodeEliminationPhase(Optional));

        appendPhase(canonicalizer);

        appendPhase(new TornadoNewArrayDevirtualizationReplacement());

        appendPhase(new TornadoHalfFloatReplacement());

        if (PartialEscapeAnalysis.getValue(options)) {
            appendPhase(new PartialEscapePhase(true, canonicalizer, options));
        }

        appendPhase(new TornadoPrivateArrayPiRemoval());

        appendPhase(new TornadoValueTypeCleanup());

        if (OptConvertDeoptsToGuards.getValue(options)) {
            appendPhase(new ConvertDeoptimizeToGuardPhase(canonicalizer));
        }

        appendPhase(new TornadoShapeAnalysis());
        appendPhase(canonicalizer);
        appendPhase(new TornadoParallelScheduler());

        appendPhase(new SchedulePhase(SchedulePhase.SchedulingStrategy.EARLIEST));

        LoopPolicies loopPolicies = new DefaultLoopPolicies();
        appendPhase(new LoopFullUnrollPhase(canonicalizer, loopPolicies));

        appendPhase(canonicalizer);
        appendPhase(new DeadCodeEliminationPhase(Optional));

        appendPhase(new SchedulePhase(SchedulePhase.SchedulingStrategy.EARLIEST));

        appendPhase(new HighTierLoweringPhase(canonicalizer));

        // After the first Lowering, TornadoVM replaces reductions with snippets
        // that contains method calls to barriers.

        appendPhase(new TornadoCUDAIntrinsicsReplacements(metaAccessProvider));

        appendPhase(new TornadoLocalMemoryAllocation());

        appendPhase(new ExceptionSuppression());
    }

    private CanonicalizerPhase createCanonicalizerPhase(OptionValues options, CanonicalizerPhase.CustomSimplification customCanonicalizer) {
        CanonicalizerPhase canonicalizerPhase = CanonicalizerPhase.create();
        return canonicalizerPhase.copyWithCustomSimplification(customCanonicalizer);

    }
}
