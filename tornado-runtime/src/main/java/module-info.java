import com.m3.tornado.runtime.TornadoBackendProvider;
import com.m3.tornado.runtime.library.spi.TornadoLibraryProvider;

open module jdk.m3.tornado.runtime {
    requires java.logging;
    requires jdk.unsupported;
    requires org.objectweb.asm;
    requires org.graalvm.collections;

    requires transitive jdk.internal.vm.ci;
    requires transitive jdk.m3.tornado.graal;
    requires transitive jdk.m3.tornado.api;
    requires commons.math3;
    requires snmp4j;
    requires java.management;

    exports com.m3.tornado.runtime;
    exports com.m3.tornado.runtime.ffm;
    exports com.m3.tornado.runtime.analyzer;
    exports com.m3.tornado.runtime.common;
    exports com.m3.tornado.runtime.common.enums;
    exports com.m3.tornado.runtime.common.exceptions;
    exports com.m3.tornado.runtime.directives;
    exports com.m3.tornado.runtime.domain;
    exports com.m3.tornado.runtime.graal;
    exports com.m3.tornado.runtime.graal.backend;
    exports com.m3.tornado.runtime.graal.compiler;
    exports com.m3.tornado.runtime.graal.nodes;
    exports com.m3.tornado.runtime.graal.nodes.logic;
    exports com.m3.tornado.runtime.graal.nodes.calc;
    exports com.m3.tornado.runtime.graal.phases;
    exports com.m3.tornado.runtime.graph;
    exports com.m3.tornado.runtime.graph.nodes;
    exports com.m3.tornado.runtime.library;
    exports com.m3.tornado.runtime.library.spi;
    exports com.m3.tornado.runtime.profiler;
    exports com.m3.tornado.runtime.sketcher;
    exports com.m3.tornado.runtime.tasks;
    exports com.m3.tornado.runtime.tasks.meta;
    exports com.m3.tornado.runtime.utils;
    exports com.m3.tornado.runtime.graal.phases.sketcher;
    exports com.m3.tornado.runtime.graal.nodes.interfaces;
    exports com.m3.tornado.runtime.jvmci;

    uses TornadoBackendProvider;
    uses TornadoLibraryProvider;
}
