package com.kaleblangley.haikalat.subsystems.ui;

/** 轻量线程所有权断言，不给每个 node 增加锁。 */
final class UiThreadGuard {
    private final Thread owner;
    private final String resourceName;

    UiThreadGuard(String resourceName) {
        owner = Thread.currentThread();
        this.resourceName = resourceName;
    }

    void check() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException(resourceName + " is owned by thread " + owner.getName()
                    + " but was accessed from " + Thread.currentThread().getName());
        }
    }
}
