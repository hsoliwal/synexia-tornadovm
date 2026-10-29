/*
 * Copyright (c) 2024, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.m3.tornado.api;

import com.m3.tornado.api.plan.types.OffConcurrentDevices;
import com.m3.tornado.api.plan.types.OffMemoryLimit;
import com.m3.tornado.api.plan.types.OffPrintKernel;
import com.m3.tornado.api.plan.types.OffProfiler;
import com.m3.tornado.api.plan.types.OffThreadInfo;
import com.m3.tornado.api.plan.types.WithAllGraphs;
import com.m3.tornado.api.plan.types.WithBatch;
import com.m3.tornado.api.plan.types.WithCUDAGraph;
import com.m3.tornado.api.plan.types.WithClearProfiles;
import com.m3.tornado.api.plan.types.WithCompilerFlags;
import com.m3.tornado.api.plan.types.WithConcurrentDevices;
import com.m3.tornado.api.plan.types.WithDefaultScheduler;
import com.m3.tornado.api.plan.types.WithDevice;
import com.m3.tornado.api.plan.types.WithFreeDeviceMemory;
import com.m3.tornado.api.plan.types.WithGraph;
import com.m3.tornado.api.plan.types.WithGridScheduler;
import com.m3.tornado.api.plan.types.WithIntraPlanConcurrency;
import com.m3.tornado.api.plan.types.WithMemoryLimit;
import com.m3.tornado.api.plan.types.WithPreCompilation;
import com.m3.tornado.api.plan.types.WithPrintKernel;
import com.m3.tornado.api.plan.types.WithProfiler;
import com.m3.tornado.api.plan.types.WithResetDevice;
import com.m3.tornado.api.plan.types.WithStagedTransfers;
import com.m3.tornado.api.plan.types.WithThreadInfo;
import com.m3.tornado.api.plan.types.WithWarmUpIterations;
import com.m3.tornado.api.plan.types.WithWarmUpTime;

public abstract sealed class ExecutionPlanType extends TornadoExecutionPlan //
        permits OffConcurrentDevices, OffMemoryLimit, OffPrintKernel, OffProfiler, //
        OffThreadInfo, WithAllGraphs, WithPreCompilation, WithBatch, WithClearProfiles, WithCompilerFlags, //
        WithConcurrentDevices, WithDefaultScheduler, WithDevice,  //
        WithFreeDeviceMemory, WithGraph, WithGridScheduler, WithMemoryLimit, WithPrintKernel, WithProfiler, //
        WithResetDevice, WithThreadInfo, WithWarmUpIterations, WithWarmUpTime, WithCUDAGraph, WithIntraPlanConcurrency, //
        WithStagedTransfers { //

    public ExecutionPlanType(TornadoExecutionPlan parentNode) {

        // Set link between the previous action (parent) and the new one
        this.parentLink = parentNode;

        // Propagate the root node of the current execution plan
        this.rootNode = parentNode.rootNode;

        // Set Link the root node to the leaf
        this.rootNode.childLink = this;

        // Copy the reference for the executor
        this.tornadoExecutor = parentNode.tornadoExecutor;

        // Copy the reference for the execution frame
        this.executionFrame = parentNode.executionFrame;

        // Set child reference to this instance
        this.childLink = this;
    }
}
