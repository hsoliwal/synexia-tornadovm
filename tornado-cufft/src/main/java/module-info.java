open module jdk.m3.tornado.cufft {
    requires transitive jdk.m3.tornado.api;
    requires jdk.m3.tornado.runtime;

    exports com.m3.tornado.cufft;
    exports com.m3.tornado.cufft.provider;
    exports com.m3.tornado.cufft.tests;

    provides com.m3.tornado.runtime.library.spi.TornadoLibraryProvider with //
            com.m3.tornado.cufft.provider.CuFftLibraryProvider;
}
