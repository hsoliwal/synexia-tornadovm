open module jdk.m3.tornado.cublas {
    requires transitive jdk.m3.tornado.api;
    requires jdk.m3.tornado.runtime;

    exports com.m3.tornado.cublas;
    exports com.m3.tornado.cublas.enums;
    exports com.m3.tornado.cublas.provider;
    exports com.m3.tornado.cublas.tests;

    provides com.m3.tornado.runtime.library.spi.TornadoLibraryProvider with //
            com.m3.tornado.cublas.provider.CuBlasLibraryProvider, //
            com.m3.tornado.cublas.provider.CuBlasLtLibraryProvider;
}
