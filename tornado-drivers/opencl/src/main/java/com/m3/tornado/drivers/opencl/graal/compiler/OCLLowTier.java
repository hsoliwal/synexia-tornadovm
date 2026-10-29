/*
 * Copyright (c) 2020, 2023, 2024, APT Group, Department of Computer Science,
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
package com.m3.tornado.drivers.opencl.graal.compiler;

import static com.m3.tornado.graal.compiler.core.common.GraalOptions.ConditionalElimination;

import com.m3.tornado.graal.compiler.options.OptionValues;
import com.m3.tornado.graal.compiler.phases.common.AddressLoweringByNodePhase;
import com.m3.tornado.graal.compiler.phases.common.AddressLoweringByNodePhase.AddressLowering;
import com.m3.tornado.graal.compiler.phases.common.CanonicalizerPhase;
import com.m3.tornado.graal.compiler.phases.common.DeadCodeEliminationPhase;
import com.m3.tornado.graal.compiler.phases.common.FixReadsPhase;
import com.m3.tornado.graal.compiler.phases.common.IterativeConditionalEliminationPhase;
import com.m3.tornado.graal.compiler.phases.common.LowTierLoweringPhase;
import com.m3.tornado.graal.compiler.phases.common.UseTrappingNullChecksPhase;
import com.m3.tornado.graal.compiler.phases.schedule.SchedulePhase;

import com.m3.tornado.api.TornadoDeviceContext;
import com.m3.tornado.drivers.common.compiler.phases.analysis.TornadoFeatureExtraction;
import com.m3.tornado.drivers.common.compiler.phases.loops.TornadoLoopCanonicalization;
import com.m3.tornado.drivers.common.compiler.phases.utils.DumpLowTierGraph;
import com.m3.tornado.drivers.opencl.graal.phases.InfinityReplacementPhase;
import com.m3.tornado.drivers.opencl.graal.phases.InverseSquareRootPhase;
import com.m3.tornado.drivers.opencl.graal.phases.OCLFMAPhase;
import com.m3.tornado.drivers.opencl.graal.phases.OCLFP16SupportPhase;
import com.m3.tornado.drivers.opencl.graal.phases.OCLFP64SupportPhase;
import com.m3.tornado.drivers.opencl.graal.phases.OCLFieldCoopsAccessPhase;
import com.m3.tornado.drivers.opencl.graal.phases.TornadoAtomicsParametersPhase;
import com.m3.tornado.drivers.opencl.graal.phases.TornadoAtomicsScheduling;
import com.m3.tornado.drivers.opencl.graal.phases.TornadoFixedArrayCopyPhase;
import com.m3.tornado.drivers.opencl.graal.phases.TornadoHalfFloatVectorOffset;
import com.m3.tornado.runtime.common.TornadoOptions;
import com.m3.tornado.runtime.graal.compiler.TornadoLowTier;

public class OCLLowTier extends TornadoLowTier {

    TornadoDeviceContext tornadoDeviceContext;

    public OCLLowTier(OptionValues options, TornadoDeviceContext tornadoDeviceContext, AddressLowering addressLowering) {
        this.tornadoDeviceContext = tornadoDeviceContext;
        CanonicalizerPhase canonicalizer = getCannonicalizer(options);

        appendPhase(new OCLFP64SupportPhase(tornadoDeviceContext));

        appendPhase(new OCLFP16SupportPhase(tornadoDeviceContext));

        appendPhase(new LowTierLoweringPhase(canonicalizer));

        if (ConditionalElimination.getValue(options)) {
            appendPhase(new IterativeConditionalEliminationPhase(canonicalizer, true));
        }

        // TODO Investigate why FixReads break kfusion on Nvidia GPUs
        if (TornadoOptions.ENABLE_FIX_READS) {
            appendPhase(new FixReadsPhase(true, new SchedulePhase(SchedulePhase.SchedulingStrategy.LATEST_OUT_OF_LOOPS)));
        }
        appendPhase(new UseTrappingNullChecksPhase());

        appendPhase(new TornadoFixedArrayCopyPhase());

        appendPhase(new AddressLoweringByNodePhase(addressLowering));

        appendPhase(new DeadCodeEliminationPhase(DeadCodeEliminationPhase.Optionality.Required));

        appendPhase(new TornadoHalfFloatVectorOffset());

        appendPhase(new TornadoLoopCanonicalization());

        if (TornadoOptions.ENABLE_FMA) {
            appendPhase(new OCLFMAPhase());
        }

        if (TornadoOptions.MATH_OPTIMIZATIONS) {
            appendPhase(new InverseSquareRootPhase());
        }

        appendPhase(new InfinityReplacementPhase());

        appendPhase(new TornadoAtomicsParametersPhase());

        appendPhase(new TornadoAtomicsScheduling());

        appendPhase(new OCLFieldCoopsAccessPhase());

        appendPhase(new SchedulePhase(SchedulePhase.SchedulingStrategy.LATEST_OUT_OF_LOOPS));

        if (TornadoOptions.FEATURE_EXTRACTION) {
            appendPhase(new TornadoFeatureExtraction(tornadoDeviceContext));
        }

        if (TornadoOptions.DUMP_LOW_TIER_WITH_IGV) {
            appendPhase(new DumpLowTierGraph());
        }

    }

    private CanonicalizerPhase getCannonicalizer(OptionValues options) {
        return CanonicalizerPhase.create();
    }
}
