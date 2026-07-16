package com.kaleblangley.haikalat.subsystems.windowing.input;

/** 平台 IME 向 UI/update 线程发布 composition 生命周期的边界。 */
public interface TextInputClient {
    void compositionStarted();

    void compositionUpdated(ImeComposition composition);

    void compositionCommitted(String text);

    void compositionCancelled();
}
