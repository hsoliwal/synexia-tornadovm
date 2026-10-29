import com.m3.tornado.runtime.TornadoBackendProvider;

module jdk.m3.tornado.drivers.cuda {
    requires transitive jdk.internal.vm.ci;
    requires transitive jdk.m3.tornado.graal;
    requires transitive org.graalvm.collections;
    requires transitive org.graalvm.word;
    requires transitive jdk.m3.tornado.api;
    requires transitive jdk.m3.tornado.runtime;
    requires jdk.m3.tornado.drivers.common;

    exports com.m3.tornado.drivers.cuda;
    exports com.m3.tornado.drivers.cuda.builtins;
    exports com.m3.tornado.drivers.cuda.enums;
    exports com.m3.tornado.drivers.cuda.exceptions;
    exports com.m3.tornado.drivers.cuda.graal;
    exports com.m3.tornado.drivers.cuda.graal.asm;
    exports com.m3.tornado.drivers.cuda.graal.backend;
    exports com.m3.tornado.drivers.cuda.graal.compiler;
    exports com.m3.tornado.drivers.cuda.graal.compiler.plugins;
    exports com.m3.tornado.drivers.cuda.graal.lir;
    exports com.m3.tornado.drivers.cuda.graal.meta;
    exports com.m3.tornado.drivers.cuda.graal.nodes;
    exports com.m3.tornado.drivers.cuda.graal.nodes.logic;
    exports com.m3.tornado.drivers.cuda.graal.nodes.vector;
    exports com.m3.tornado.drivers.cuda.graal.phases;
    exports com.m3.tornado.drivers.cuda.graal.snippets;
    exports com.m3.tornado.drivers.cuda.mm;
    exports com.m3.tornado.drivers.cuda.runtime;
    exports com.m3.tornado.drivers.cuda.tests;
    exports com.m3.tornado.drivers.cuda.power;
    exports com.m3.tornado.drivers.cuda.scheduler;
    exports com.m3.tornado.drivers.cuda.natives;

    provides TornadoBackendProvider with
            com.m3.tornado.drivers.cuda.CUDATornadoDriverProvider;
}
