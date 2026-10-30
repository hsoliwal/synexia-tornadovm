open module jdk.m3.tornado.drivers.common {
    requires transitive jdk.internal.vm.ci;
    requires transitive jdk.m3.tornado.graal;
    requires transitive jdk.m3.tornado.runtime;
    
    exports com.m3.tornado.drivers.providers;
    exports com.m3.tornado.drivers.common;
    exports com.m3.tornado.drivers.common.architecture;
    exports com.m3.tornado.drivers.common.mm;
    exports com.m3.tornado.drivers.common.code;
    exports com.m3.tornado.drivers.common.logging;
    exports com.m3.tornado.drivers.common.compiler.phases.guards;
    exports com.m3.tornado.drivers.common.compiler.phases.memalloc;
    exports com.m3.tornado.drivers.common.compiler.phases.loops;
    exports com.m3.tornado.drivers.common.compiler.phases.utils;
    exports com.m3.tornado.drivers.common.compiler.phases.analysis;
    exports com.m3.tornado.drivers.common.utils;
    exports com.m3.tornado.drivers.common.power;
    exports com.m3.tornado.drivers.common.ffm;
}
