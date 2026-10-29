import com.m3.tornado.runtime.TornadoBackendProvider;

module jdk.m3.tornado.drivers.opencl {
    requires jdk.unsupported;
    requires transitive jdk.internal.vm.ci;
    requires transitive jdk.m3.tornado.graal;
    requires transitive org.graalvm.collections;
    requires transitive org.graalvm.word;
    requires transitive jdk.m3.tornado.api;
    requires transitive jdk.m3.tornado.runtime;
    requires jdk.m3.tornado.drivers.common;

    exports com.m3.tornado.drivers.opencl;
    exports com.m3.tornado.drivers.opencl.builtins;
    exports com.m3.tornado.drivers.opencl.enums;
    exports com.m3.tornado.drivers.opencl.ffm;
    exports com.m3.tornado.drivers.opencl.exceptions;
    exports com.m3.tornado.drivers.opencl.graal;
    exports com.m3.tornado.drivers.opencl.graal.asm;
    exports com.m3.tornado.drivers.opencl.graal.backend;
    exports com.m3.tornado.drivers.opencl.graal.compiler;
    exports com.m3.tornado.drivers.opencl.graal.compiler.plugins;
    exports com.m3.tornado.drivers.opencl.graal.lir;
    exports com.m3.tornado.drivers.opencl.graal.meta;
    exports com.m3.tornado.drivers.opencl.graal.nodes;
    exports com.m3.tornado.drivers.opencl.graal.nodes.logic;
    exports com.m3.tornado.drivers.opencl.graal.nodes.vector;
    exports com.m3.tornado.drivers.opencl.graal.phases;
    exports com.m3.tornado.drivers.opencl.graal.snippets;
    exports com.m3.tornado.drivers.opencl.mm;
    exports com.m3.tornado.drivers.opencl.runtime;
    exports com.m3.tornado.drivers.opencl.tests;
    exports com.m3.tornado.drivers.opencl.power;
    exports com.m3.tornado.drivers.opencl.scheduler;
    exports com.m3.tornado.drivers.opencl.natives;

    provides TornadoBackendProvider with
            com.m3.tornado.drivers.opencl.OCLTornadoDriverProvider;
}
