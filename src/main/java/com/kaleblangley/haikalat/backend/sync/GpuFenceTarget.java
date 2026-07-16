package com.kaleblangley.haikalat.backend.sync;

/**
 * 在已经录制的 GPU 工作之后插入资源所有权 fence 的强类型目标。
 *
 * <p>实现只负责自身 fence 生命周期，不得借此改变渲染管线状态。</p>
 */
@FunctionalInterface
public interface GpuFenceTarget {
    void insertGpuFence();

    /**
     * CommandExecutor 未到达或未成功完成本 target 时的正式失败通知。
     * 默认实现无状态；持有 pending 资源所有权的实现应在此回滚或建立保守 fence。
     */
    default void executionFailed(Throwable failure) {
    }
}
