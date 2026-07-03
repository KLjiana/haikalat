package com.kaleblangley.haikalat.gl.command;

@FunctionalInterface
public interface RenderCommand {
    void execute();

    static RenderCommand of(Runnable runnable) {
        return runnable::run;
    }
}
