/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2020, APT Group, Department of Computer Science,
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
package com.m3.tornado.drivers.cuda.virtual;

import java.util.ArrayList;
import java.util.List;

import com.m3.tornado.drivers.cuda.CUDAContextInterface;
import com.m3.tornado.drivers.cuda.CUDATargetDevice;
import com.m3.tornado.runtime.common.TornadoLogger;

public class VirtualCUDAContext implements CUDAContextInterface {

    private final List<CUDATargetDevice> devices;
    private final VirtualCUDAPlatform platform;

    public VirtualCUDAContext(VirtualCUDAPlatform platform, CUDATargetDevice device) {
        this.platform = platform;
        this.devices = new ArrayList<>();
        devices.add(device);
    }

    public int getNumDevices() {
        return 1;
    }

    public List<CUDATargetDevice> devices() {
        return devices;
    }

    @Override
    public long getContextId() {
        return 0;
    }

    public void cleanup() {
    }

    @Override
    public String toString() {
        return getClass().getName();
    }

    @Override
    public VirtualCUDADeviceContext createDeviceContext(int index) {
        new TornadoLogger().debug("creating device context for device: %s", devices.get(index).toString());
        return new VirtualCUDADeviceContext(devices.get(index), this);
    }

    public int getPlatformIndex() {
        return platform.getIndex();
    }

    public VirtualCUDAPlatform getPlatform() {
        return platform;
    }

    @Override
    public void createCommandQueue(int index) {
    }
}
