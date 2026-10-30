open module jdk.m3.tornado.cusparse {
    requires transitive jdk.m3.tornado.api;
    requires jdk.m3.tornado.runtime;

    exports com.m3.tornado.cusparse;
    exports com.m3.tornado.cusparse.provider;

    provides com.m3.tornado.runtime.library.spi.TornadoLibraryProvider with //
            com.m3.tornado.cusparse.provider.CusparseLibraryProvider;
}
