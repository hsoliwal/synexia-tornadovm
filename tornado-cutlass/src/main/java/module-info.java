open module jdk.m3.tornado.cutlass {
    requires transitive jdk.m3.tornado.api;
    requires jdk.m3.tornado.runtime;

    exports com.m3.tornado.cutlass;
    exports com.m3.tornado.cutlass.provider;
    exports com.m3.tornado.cutlass.tests;

    provides com.m3.tornado.runtime.library.spi.TornadoLibraryProvider with //
            com.m3.tornado.cutlass.provider.CutlassLibraryProvider;
}
