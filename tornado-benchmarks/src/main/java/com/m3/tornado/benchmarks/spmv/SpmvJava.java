/*
 * Copyright (c) 2013-2023, APT Group, Department of Computer Science,
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
package com.m3.tornado.benchmarks.spmv;

import static com.m3.tornado.benchmarks.LinearAlgebraArrays.spmv;

import com.m3.tornado.api.common.TornadoDevice;
import com.m3.tornado.api.types.arrays.FloatArray;
import com.m3.tornado.benchmarks.BenchmarkDriver;
import com.m3.tornado.matrix.SparseMatrixUtils.CSRMatrix;

public class SpmvJava extends BenchmarkDriver {

    private final CSRMatrix<FloatArray> matrix;
    private FloatArray v;
    private FloatArray y;

    public SpmvJava(int iterations, CSRMatrix<FloatArray> matrix) {
        super(iterations);
        this.matrix = matrix;
    }

    @Override
    public void setUp() {
        v = new FloatArray(matrix.size);
        y = new FloatArray(matrix.size);
        Benchmark.initData(v);
    }

    @Override
    public void tearDown() {
        v = null;
        y = null;
        super.tearDown();
    }

    @Override
    public void runBenchmark(TornadoDevice device) {
        spmv(matrix.vals, matrix.cols, matrix.rows, v, matrix.size, y);
    }

    @Override
    public void barrier() {
    }

    @Override
    public boolean validate(TornadoDevice device) {
        return true;
    }

    public void printSummary() {
        System.out.printf("id=java-serial, elapsed=%f, per iteration=%f\n", getElapsed(), getElapsedPerIteration());
    }

}
