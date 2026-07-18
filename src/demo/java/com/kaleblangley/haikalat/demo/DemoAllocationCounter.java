package com.kaleblangley.haikalat.demo;

import java.lang.management.ManagementFactory;

/** Demo 基准共用的当前线程分配计数器。 */
final class DemoAllocationCounter {
    private static final com.sun.management.ThreadMXBean BEAN = allocationBean();

    private DemoAllocationCounter() {
    }

    static long currentThreadBytes() {
        return BEAN == null ? -1L : BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private static com.sun.management.ThreadMXBean allocationBean() {
        if (!(ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean)
                || !bean.isThreadAllocatedMemorySupported()) return null;
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }
}
