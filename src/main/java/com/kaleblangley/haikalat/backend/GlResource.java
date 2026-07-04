package com.kaleblangley.haikalat.backend;

public interface GlResource extends AutoCloseable {
    int id();

    boolean isClosed();

    @Override
    void close();
}
