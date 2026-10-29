package com.m3.tornado.api.plan.types;

import com.m3.tornado.api.ExecutionPlanType;
import com.m3.tornado.api.TornadoExecutionPlan;

public final class WithCUDAGraph extends ExecutionPlanType {

    public WithCUDAGraph(TornadoExecutionPlan parent) {
        super(parent);
    }

}
