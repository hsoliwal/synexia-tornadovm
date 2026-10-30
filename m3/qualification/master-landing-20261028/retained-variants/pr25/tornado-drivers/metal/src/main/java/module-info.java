import com.m3.tornado.runtime.TornadoBackendProvider;

module jdk.m3.tornado.drivers.metal {
    requires transitive jdk.internal.vm.ci;
    requires transitive jdk.m3.tornado.graal;
    requires transitive org.graalvm.collections;
    requires transitive org.graalvm.word;
    requires transitive jdk.m3.tornado.api;
    requires transitive jdk.m3.tornado.runtime;
    requires jdk.m3.tornado.drivers.common;

    requires java.management;
    requires jdk.management;

    exports com.m3.tornado.drivers.metal;
    exports com.m3.tornado.drivers.metal.builtins;
    exports com.m3.tornado.drivers.metal.enums;
    exports com.m3.tornado.drivers.metal.ffm;
    exports com.m3.tornado.drivers.metal.exceptions;
    exports com.m3.tornado.drivers.metal.graal;
    exports com.m3.tornado.drivers.metal.graal.asm;
    exports com.m3.tornado.drivers.metal.graal.backend;
    exports com.m3.tornado.drivers.metal.graal.compiler;
    exports com.m3.tornado.drivers.metal.graal.compiler.plugins;
    exports com.m3.tornado.drivers.metal.graal.lir;
    exports com.m3.tornado.drivers.metal.graal.meta;
    exports com.m3.tornado.drivers.metal.graal.nodes;
    exports com.m3.tornado.drivers.metal.graal.nodes.logic;
    exports com.m3.tornado.drivers.metal.graal.nodes.vector;
    exports com.m3.tornado.drivers.metal.graal.phases;
    exports com.m3.tornado.drivers.metal.graal.snippets;
    exports com.m3.tornado.drivers.metal.mm;
    exports com.m3.tornado.drivers.metal.runtime;
    exports com.m3.tornado.drivers.metal.tests;
    exports com.m3.tornado.drivers.metal.power;
    exports com.m3.tornado.drivers.metal.scheduler;
    exports com.m3.tornado.drivers.metal.natives;

    provides TornadoBackendProvider with
            com.m3.tornado.drivers.metal.MetalTornadoDriverProvider;
}
