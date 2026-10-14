// SPDX-License-Identifier: Apache-2.0
package com.synexia.tornadom3.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.synexia.m3.compute.vector.VectorCompute;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

/** No hardware is needed; real device and nonempty kernel tests remain separate. */
final class TornadoVectorAddProviderTest {
    @Test void metadataAndEmptyInput() {
        TornadoVectorAddProvider provider = new TornadoVectorAddProvider();
        assertEquals("tornadovm-vector-add", provider.id());
        assertArrayEquals(new int[0], provider.add(new int[0], new int[0]));
    }

    @Test void providerIsDiscoveredThroughJavaServices() {
        assertTrue(ServiceLoader.load(VectorCompute.Provider.class).stream()
                .map(ServiceLoader.Provider::get)
                .anyMatch(provider -> provider.id().equals("tornadovm-vector-add")));
    }
}
