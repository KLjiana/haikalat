package com.kaleblangley.haikalat.core.buffer;

public enum InstanceUploadStrategy {
    TRIPLE_BUFFER_SUB_DATA(false),
    FENCE_PROTECTED_SUB_DATA(false);

    private final boolean persistent;

    InstanceUploadStrategy(boolean persistent) {
        this.persistent = persistent;
    }

    public boolean persistent() {
        return persistent;
    }
}
