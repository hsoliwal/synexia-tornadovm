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
open module jdk.m3.tornado.fuzz {
    requires transitive jdk.m3.tornado.api;
    requires java.compiler;

    exports com.m3.tornado.fuzz;
    exports com.m3.tornado.fuzz.oracle;
    exports com.m3.tornado.fuzz.kernels;
    exports com.m3.tornado.fuzz.report;
    exports com.m3.tornado.fuzz.gen;
}
