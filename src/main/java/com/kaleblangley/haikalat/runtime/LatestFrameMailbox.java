package com.kaleblangley.haikalat.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在生产线程和渲染线程之间非阻塞地交接最新值。
 *
 * <p>发布新值会替换消费者尚未观察到的旧帧。值必须是不可变快照：mailbox 提供原子发布和可见性，
 * 但刻意不加锁，也不复制应用对象。</p>
 *
 * @param <T> 不可变帧快照类型
 */
public final class LatestFrameMailbox<T> {
    private final AtomicReference<Snapshot<T>> latest;

    public LatestFrameMailbox(T initialValue) {
        latest = new AtomicReference<>(new Snapshot<>(0L,
                Objects.requireNonNull(initialValue, "initialValue")));
    }

    public long publish(T value) {
        T publishedValue = Objects.requireNonNull(value, "value");
        Snapshot<T> published = latest.updateAndGet(previous ->
                new Snapshot<>(previous.sequence() + 1L, publishedValue));
        return published.sequence();
    }

    public Snapshot<T> latest() {
        return latest.get();
    }

    public record Snapshot<T>(long sequence, T value) {
        public Snapshot {
            if (sequence < 0L) {
                throw new IllegalArgumentException("sequence must be non-negative");
            }
            Objects.requireNonNull(value, "value");
        }
    }
}
