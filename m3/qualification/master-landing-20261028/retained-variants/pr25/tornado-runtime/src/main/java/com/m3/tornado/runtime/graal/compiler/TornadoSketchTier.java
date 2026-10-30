/*
 * Copyright (c) 2020, APT Group, Department of Computer Science,
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
package com.m3.tornado.runtime.graal.compiler;

import static com.m3.tornado.graal.compiler.core.common.GraalOptions.ConditionalElimination;
import static com.m3.tornado.graal.compiler.core.phases.HighTier.Options.Inline;
import static com.m3.tornado.graal.compiler.phases.common.DeadCodeEliminationPhase.Optionality.Optional;

import com.m3.tornado.graal.compiler.options.OptionValues;
import com.m3.tornado.graal.compiler.phases.PhaseSuite;
import com.m3.tornado.graal.compiler.phases.common.CanonicalizerPhase;
import com.m3.tornado.graal.compiler.phases.common.DeadCodeEliminationPhase;
import com.m3.tornado.graal.compiler.phases.common.IterativeConditionalEliminationPhase;
import com.m3.tornado.graal.compiler.phases.common.inlining.InliningPhase;
import com.m3.tornado.graal.compiler.phases.common.inlining.policy.InliningPolicy;

import com.m3.tornado.runtime.common.TornadoOptions;
import com.m3.tornado.runtime.graal.phases.TornadoHalfFloatFixedGuardElimination;
import com.m3.tornado.runtime.graal.phases.TornadoSketchTierContext;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoApiReplacement;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoAutoParalleliser;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoBatchFunctionAnalysis;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoDataflowAnalysis;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoFullInliningPolicy;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoKernelContextReplacement;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoNativeTypeElimination;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoNumericPromotionPhase;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoPanamaPrivateMemory;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoPartialInliningPolicy;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoReduceReplacement;
import com.m3.tornado.runtime.graal.phases.sketcher.TornadoStampResolver;

public class TornadoSketchTier extends PhaseSuite<TornadoSketchTierContext> {

    protected final CanonicalizerPhase.CustomSimplification customSimplification;

    public TornadoSketchTier(OptionValues options, CanonicalizerPhase.CustomSimplification customCanonicalizer) {
        this.customSimplification = customCanonicalizer;

        appendPhase(new TornadoNumericPromotionPhase());

        InliningPolicy inliningPolicy = (TornadoOptions.FULL_INLINING) ? new TornadoFullInliningPolicy() : new TornadoPartialInliningPolicy();

        CanonicalizerPhase canonicalizer = CanonicalizerPhase.create();
        appendPhase(canonicalizer);

        if (Inline.getValue(options)) {
            appendPhase(new InliningPhase(inliningPolicy, canonicalizer));
            appendPhase(new DeadCodeEliminationPhase(Optional));

            if (ConditionalElimination.getValue(options)) {
                appendPhase(canonicalizer);
                appendPhase(new IterativeConditionalEliminationPhase(canonicalizer, false));
            }
        }

        appendPhase(new TornadoStampResolver());
        appendPhase(new TornadoHalfFloatFixedGuardElimination());
        appendPhase(new TornadoNativeTypeElimination());
        appendPhase(new TornadoReduceReplacement());
        appendPhase(new TornadoApiReplacement());
        appendPhase(new TornadoKernelContextReplacement());
        appendPhase(new TornadoAutoParalleliser());
        appendPhase(new TornadoDataflowAnalysis());
        appendPhase(new TornadoPanamaPrivateMemory());
        appendPhase(new TornadoBatchFunctionAnalysis());
    }

}
