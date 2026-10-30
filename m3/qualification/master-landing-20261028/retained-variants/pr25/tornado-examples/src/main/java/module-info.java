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
open module jdk.m3.tornado.examples {
    requires transitive java.desktop;
    requires transitive jdk.m3.tornado.api;
    requires jdk.m3.tornado.cublas;
    requires jdk.incubator.vector;

    exports com.m3.tornado.examples;
    exports com.m3.tornado.examples.arrays;
    exports com.m3.tornado.examples.common;
    exports com.m3.tornado.examples.compute;
    exports com.m3.tornado.examples.fft;
    exports com.m3.tornado.examples.flatmap;
    exports com.m3.tornado.examples.kernelcontext.compute;
    exports com.m3.tornado.examples.kernelcontext.matrices;
    exports com.m3.tornado.examples.kernelcontext.reductions;
    exports com.m3.tornado.examples.tile;
    exports com.m3.tornado.examples.matrices;
    exports com.m3.tornado.examples.reductions;
    exports com.m3.tornado.examples.vectors;
}
