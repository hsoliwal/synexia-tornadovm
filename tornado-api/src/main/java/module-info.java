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
module jdk.m3.tornado.api {
    requires java.management;
    exports com.m3.tornado.api;
    exports com.m3.tornado.api.annotations;
    exports com.m3.tornado.api.common;
    exports com.m3.tornado.api.enums;
    exports com.m3.tornado.api.exceptions;
    exports com.m3.tornado.api.memory;
    exports com.m3.tornado.api.profiler;
    exports com.m3.tornado.api.runtime;
    exports com.m3.tornado.api.tile;
    exports com.m3.tornado.api.internal.annotations;
    exports com.m3.tornado.api.utils;

    opens com.m3.tornado.api;
    // Still required: marking TileContext host-only in the interpreter stops it consuming a
    // kernel argument slot, but the ALLOC path builds its object list elsewhere and still
    // creates a (never used) device buffer by reflecting over the context's fields - exactly as
    // it already does for KernelContext. Skipping that allocation for host-only contexts would
    // let this be dropped, and would help KernelContext too.
    opens com.m3.tornado.api.tile;
    exports com.m3.tornado.api.math;
    exports com.m3.tornado.api.types.arrays;
    opens com.m3.tornado.api.types.arrays;
    exports com.m3.tornado.api.types.collections;
    opens com.m3.tornado.api.types.collections;
    exports com.m3.tornado.api.types.matrix;
    opens com.m3.tornado.api.types.matrix;
    opens com.m3.tornado.api.types.images;
    exports com.m3.tornado.api.types.images;
    exports com.m3.tornado.api.types.utils;
    opens com.m3.tornado.api.types.utils;
    exports com.m3.tornado.api.types.common;
    opens com.m3.tornado.api.types.common;
    exports com.m3.tornado.api.types.volumes;
    opens com.m3.tornado.api.types.volumes;
    exports com.m3.tornado.api.types.vectors;
    opens com.m3.tornado.api.types.vectors;
    exports com.m3.tornado.api.types;
    opens com.m3.tornado.api.types;
    opens com.m3.tornado.api.runtime;
    exports com.m3.tornado.api.plan.types;
    opens com.m3.tornado.api.plan.types;
}
