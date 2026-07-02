package com.kaleblangley.haikalat.gl;

public interface GlResource extends AutoCloseable {
    int id();

    boolean isClosed();

    @Override
    void close();
}
