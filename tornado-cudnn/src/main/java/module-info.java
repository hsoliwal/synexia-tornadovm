open module jdk.m3.tornado.cudnn {
    requires transitive jdk.m3.tornado.api;
    requires jdk.m3.tornado.runtime;

    exports com.m3.tornado.cudnn;
    exports com.m3.tornado.cudnn.provider;
    exports com.m3.tornado.cudnn.tests;

    provides com.m3.tornado.runtime.library.spi.TornadoLibraryProvider with //
            com.m3.tornado.cudnn.provider.CuDnnLibraryProvider;
}
