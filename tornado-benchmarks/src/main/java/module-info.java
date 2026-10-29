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
open module jdk.m3.tornado.benchmarks {
    requires org.apache.commons.lang3;
    requires java.logging;
    requires jmh.core;

    requires transitive jdk.m3.tornado.api;
    requires transitive jdk.m3.tornado.matrices;

    exports com.m3.tornado.benchmarks;
    exports com.m3.tornado.benchmarks.addImage;
    exports com.m3.tornado.benchmarks.blackscholes;
    exports com.m3.tornado.benchmarks.blurFilter;
    exports com.m3.tornado.benchmarks.convolvearray;
    exports com.m3.tornado.benchmarks.convolveimage;
    exports com.m3.tornado.benchmarks.dft;
    exports com.m3.tornado.benchmarks.dgemm;
    exports com.m3.tornado.benchmarks.dotimage;
    exports com.m3.tornado.benchmarks.dotvector;
    exports com.m3.tornado.benchmarks.euler;
    exports com.m3.tornado.benchmarks.hilbert;
    exports com.m3.tornado.benchmarks.mandelbrot;
    exports com.m3.tornado.benchmarks.montecarlo;
    exports com.m3.tornado.benchmarks.nbody;
    exports com.m3.tornado.benchmarks.rotateimage;
    exports com.m3.tornado.benchmarks.rotatevector;
    exports com.m3.tornado.benchmarks.saxpy;
    exports com.m3.tornado.benchmarks.sgemm;
    exports com.m3.tornado.benchmarks.sgemv;
    exports com.m3.tornado.benchmarks.spmv;
    exports com.m3.tornado.benchmarks.stencil;
}
