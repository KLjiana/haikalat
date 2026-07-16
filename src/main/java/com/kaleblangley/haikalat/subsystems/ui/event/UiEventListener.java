package com.kaleblangley.haikalat.subsystems.ui.event;

/** 接收 UI 事件的无分配函数接口。 */
@FunctionalInterface
public interface UiEventListener<E extends UiEvent> {
    void handle(E event);
}
