package com.kaleblangley.haikalat.subsystems.ui.render;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * UI/update 与 render thread 之间的双槽或三槽 latest-wins 快照交换器。
 *
 * <p>尚未 acquire 的旧快照可以被丢弃；已经 acquire 的槽在 lease 释放前绝不会复用。
 * 当全部槽都在消费中时 producer 形成容量背压。关闭会丢弃未消费快照并唤醒所有等待方，
 * 已取得的 lease 仍可读取并正常释放。</p>
 */
public final class UiSnapshotExchange implements AutoCloseable {
    /** 默认三槽容量。 */
    public static final int DEFAULT_SLOT_COUNT = 3;

    private static final byte FREE = 0;
    private static final byte PUBLISHED = 1;
    private static final byte ACQUIRED = 2;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition stateChanged = lock.newCondition();
    private final Slot[] slots;
    private boolean closed;
    private long nextPublication = 1L;
    private long publishedCount;
    private long droppedCount;
    private int acquiredCount;

    /** 创建空的默认三槽交换器。 */
    public UiSnapshotExchange() {
        this(DEFAULT_SLOT_COUNT);
    }

    /**
     * 创建双槽或三槽交换器。
     *
     * @param slotCount 快照槽数量；同步路径通常使用 2，异步路径通常使用 3
     */
    public UiSnapshotExchange(int slotCount) {
        if (slotCount < 2 || slotCount > 3) {
            throw new IllegalArgumentException("slotCount must be 2 or 3");
        }
        slots = new Slot[slotCount];
        for (int index = 0; index < slotCount; index++) {
            slots[index] = new Slot();
        }
    }

    /** 返回交换器拥有的槽数量。 */
    public int slotCount() {
        return slots.length;
    }

    /**
     * 发布完整快照。三个槽都处于消费状态时等待，直到 lease 释放或交换器关闭。
     *
     * @param snapshot 不可变渲染快照
     * @throws InterruptedException 等待容量时线程被中断
     * @throws IllegalStateException 交换器已经关闭
     */
    public void publish(UiRenderSnapshot snapshot) throws InterruptedException {
        Objects.requireNonNull(snapshot, "snapshot");
        lock.lockInterruptibly();
        try {
            ensureOpen();
            int slot;
            while ((slot = reusableSlot()) < 0) {
                stateChanged.await();
                ensureOpen();
            }
            publishInto(slot, snapshot);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 在给定时间内尝试发布完整快照。
     *
     * @param snapshot 不可变渲染快照
     * @param timeout 最长等待时间
     * @param unit 时间单位
     * @return 已发布返回 true，容量超时返回 false
     * @throws InterruptedException 等待容量时线程被中断
     * @throws IllegalStateException 交换器已经关闭
     */
    public boolean tryPublish(UiRenderSnapshot snapshot, long timeout, TimeUnit unit)
            throws InterruptedException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(unit, "unit");
        if (timeout < 0L) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
        long remaining = unit.toNanos(timeout);
        lock.lockInterruptibly();
        try {
            ensureOpen();
            int slot;
            while ((slot = reusableSlot()) < 0) {
                if (remaining <= 0L) {
                    return false;
                }
                remaining = stateChanged.awaitNanos(remaining);
                ensureOpen();
            }
            publishInto(slot, snapshot);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 等待并取得最新完整快照，同时丢弃尚未开始消费的更旧快照。
     *
     * @return 生命周期由调用方关闭的 lease；交换器关闭且没有可消费快照时返回 null
     * @throws InterruptedException 等待快照时线程被中断
     */
    public Lease acquire() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            int slot;
            while ((slot = newestPublishedSlot()) < 0) {
                if (closed) {
                    return null;
                }
                stateChanged.await();
            }
            return acquireSlot(slot);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 非阻塞取得最新完整快照。
     *
     * @return lease；当前没有已发布快照时返回 null
     */
    public Lease tryAcquire() {
        lock.lock();
        try {
            int slot = newestPublishedSlot();
            return slot < 0 ? null : acquireSlot(slot);
        } finally {
            lock.unlock();
        }
    }

    /** 返回累计成功发布次数。 */
    public long publishedCount() {
        lock.lock();
        try {
            return publishedCount;
        } finally {
            lock.unlock();
        }
    }

    /** 返回被 latest-wins 或关闭丢弃的未消费快照数。 */
    public long droppedCount() {
        lock.lock();
        try {
            return droppedCount;
        } finally {
            lock.unlock();
        }
    }

    /** 返回当前正在消费的 lease 数。 */
    public int acquiredCount() {
        lock.lock();
        try {
            return acquiredCount;
        } finally {
            lock.unlock();
        }
    }

    /** 返回交换器是否已经关闭。 */
    public boolean isClosed() {
        lock.lock();
        try {
            return closed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 停止新发布、丢弃未 acquire 快照并唤醒等待方。重复关闭安全。
     * 已 acquire 的 lease 不会失效。
     */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            for (Slot slot : slots) {
                if (slot.state == PUBLISHED) {
                    slot.snapshot = null;
                    slot.state = FREE;
                    droppedCount++;
                }
            }
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void publishInto(int index, UiRenderSnapshot snapshot) {
        Slot slot = slots[index];
        if (slot.state == PUBLISHED) {
            droppedCount++;
        }
        slot.generation++;
        slot.publication = nextPublication++;
        slot.snapshot = snapshot;
        slot.state = PUBLISHED;
        publishedCount++;
        stateChanged.signalAll();
    }

    private Lease acquireSlot(int index) {
        Slot newest = slots[index];
        for (int candidate = 0; candidate < slots.length; candidate++) {
            Slot slot = slots[candidate];
            if (candidate != index && slot.state == PUBLISHED) {
                slot.snapshot = null;
                slot.state = FREE;
                droppedCount++;
            }
        }
        newest.state = ACQUIRED;
        acquiredCount++;
        stateChanged.signalAll();
        return new Lease(this, index, newest.generation, newest.snapshot);
    }

    private int reusableSlot() {
        for (int index = 0; index < slots.length; index++) {
            if (slots[index].state == FREE) {
                return index;
            }
        }
        int oldest = -1;
        long oldestPublication = Long.MAX_VALUE;
        for (int index = 0; index < slots.length; index++) {
            Slot slot = slots[index];
            if (slot.state == PUBLISHED && slot.publication < oldestPublication) {
                oldest = index;
                oldestPublication = slot.publication;
            }
        }
        return oldest;
    }

    private int newestPublishedSlot() {
        int newest = -1;
        long newestPublication = Long.MIN_VALUE;
        for (int index = 0; index < slots.length; index++) {
            Slot slot = slots[index];
            if (slot.state == PUBLISHED && slot.publication > newestPublication) {
                newest = index;
                newestPublication = slot.publication;
            }
        }
        return newest;
    }

    private void release(int index, long generation) {
        lock.lock();
        try {
            Slot slot = slots[index];
            if (slot.state != ACQUIRED || slot.generation != generation) {
                throw new IllegalStateException("snapshot lease no longer owns its slot");
            }
            slot.snapshot = null;
            slot.state = FREE;
            acquiredCount--;
            stateChanged.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("UiSnapshotExchange is closed");
        }
    }

    private static final class Slot {
        private byte state = FREE;
        private long generation;
        private long publication;
        private UiRenderSnapshot snapshot;
    }

    /**
     * 已 acquire 快照的独占生命周期。必须在 render 结束的 finally 或
     * try-with-resources 中关闭，释放后对应槽才可复用。
     */
    public static final class Lease implements AutoCloseable {
        private final UiSnapshotExchange owner;
        private final int slot;
        private final long generation;
        private final UiRenderSnapshot snapshot;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(UiSnapshotExchange owner, int slot, long generation,
                      UiRenderSnapshot snapshot) {
            this.owner = owner;
            this.slot = slot;
            this.generation = generation;
            this.snapshot = snapshot;
        }

        /**
         * 返回 lease 持有的不可变快照。
         *
         * @throws IllegalStateException lease 已释放
         */
        public UiRenderSnapshot snapshot() {
            if (released.get()) {
                throw new IllegalStateException("snapshot lease is released");
            }
            return snapshot;
        }

        /** 返回 lease 是否已经释放。 */
        public boolean isReleased() {
            return released.get();
        }

        /** 释放槽；重复调用安全。 */
        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.release(slot, generation);
            }
        }
    }
}
