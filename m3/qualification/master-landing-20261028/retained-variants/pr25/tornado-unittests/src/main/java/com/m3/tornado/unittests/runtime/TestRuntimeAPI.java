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
package com.m3.tornado.unittests.runtime;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.m3.tornado.api.TornadoBackend;
import com.m3.tornado.api.TornadoRuntime;
import com.m3.tornado.api.runtime.TornadoRuntimeProvider;
import com.m3.tornado.unittests.common.TornadoTestBase;

/**
 * How to run?
 *
 * <p>
 * <code>
 * tornado-test -V uk.ac.manchester.tornado.unittests.runtime.TestRuntimeAPI
 * </code>
 * </p>
 */
public class TestRuntimeAPI extends TornadoTestBase {

    @Test
    public void test01() {

        TornadoRuntime runtime = TornadoRuntimeProvider.getTornadoRuntime();

        // Obtain the first backend in the list
        TornadoBackend backend = runtime.getBackend(0);
        assertNotNull(backend);

        // TornadoVM finds at least one backend
        var numBackends = runtime.getNumBackends();
        assertTrue(numBackends >= 1);

        // At least one device should be available per backend.
        for (int i = 0; i < numBackends; i++) {
            backend = runtime.getBackend(i);
            assertTrue(backend.getNumDevices() >= 1);
        }
    }
}
