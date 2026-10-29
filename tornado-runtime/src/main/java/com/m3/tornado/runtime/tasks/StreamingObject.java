package com.m3.tornado.runtime.tasks;

public class StreamingObject {
    final int mode;
    Object object;

    public StreamingObject(final int mode, Object object) {
        this.mode = mode;
        this.object = object;
    }

    public Object getObject() {
        return object;
    }

    public int getMode() {
        return mode;
    }
}
