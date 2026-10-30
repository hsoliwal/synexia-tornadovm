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
package com.m3.tornado.drivers.opencl.graal.lir;

import com.m3.tornado.graal.compiler.nodes.ParameterNode;
import com.m3.tornado.graal.compiler.nodes.PiNode;
import com.m3.tornado.graal.compiler.nodes.ValueNode;
import com.m3.tornado.graal.compiler.nodes.memory.FloatingReadNode;
import com.m3.tornado.graal.compiler.nodes.memory.ReadNode;
import com.m3.tornado.graal.compiler.nodes.memory.address.AddressNode;
import com.m3.tornado.graal.compiler.phases.common.AddressLoweringByNodePhase;

import com.m3.tornado.api.exceptions.TornadoInternalError;
import com.m3.tornado.drivers.opencl.graal.OCLArchitecture;
import com.m3.tornado.drivers.opencl.graal.OCLArchitecture.OCLMemoryBase;
import com.m3.tornado.drivers.opencl.graal.nodes.FixedArrayNode;
import com.m3.tornado.drivers.opencl.graal.nodes.FixedArrayCopyNode;
import com.m3.tornado.drivers.opencl.graal.nodes.LocalArrayNode;
import com.m3.tornado.drivers.opencl.graal.nodes.OCLDecompressedReadFieldNode;
import com.m3.tornado.runtime.graal.nodes.calc.TornadoAddressArithmeticNode;

public class OCLAddressLowering extends AddressLoweringByNodePhase.AddressLowering {

    @Override
    public AddressNode lower(ValueNode base, ValueNode offset) {
        OCLMemoryBase memoryRegister = OCLArchitecture.globalSpace;
        if (base instanceof FixedArrayNode fixedArrayNode) {
            memoryRegister = fixedArrayNode.getMemoryRegister();
        } else if (base instanceof FixedArrayCopyNode fixedArrayCopyNode) {
            memoryRegister = fixedArrayCopyNode.getMemoryRegister();
        } else if (base instanceof LocalArrayNode localArrayNode) {
            memoryRegister = localArrayNode.getMemoryRegister();
        } else if (!((base instanceof TornadoAddressArithmeticNode) || (base instanceof ParameterNode) || (base instanceof ReadNode) || (base instanceof FloatingReadNode) || (base instanceof PiNode)
                // An OCLDecompressedReadFieldNode yields the absolute device address of the referenced
                // object (its generate() decompresses the field's compressed reference against the owning
                // object's base), so it is a valid base for a further address computation - e.g. writing a
                // scalar HalfFloat into VectorHalf.storage (a HalfFloatArray field), where the
                // WriteHalfFloatNode's OffsetAddressNode uses the decompressed storage reference directly
                // as its base.
                || (base instanceof OCLDecompressedReadFieldNode))) {
            TornadoInternalError.unimplemented("address origin unimplemented: %s", base.getClass().getName());
        }

        OCLAddressNode result = new OCLAddressNode(base, offset, memoryRegister);
        return (base.graph().unique(result));
    }
}
