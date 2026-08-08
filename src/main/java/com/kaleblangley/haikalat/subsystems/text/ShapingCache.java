package com.kaleblangley.haikalat.subsystems.text;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 具有明确 entry 上限和访问顺序 LRU 淘汰的 shaping 缓存。
 *
 * <p>该对象按 UI/text owner 线程封闭，不执行内部加锁。</p>
 */
public final class ShapingCache {
    private final int maximumEntries;
    private final LinkedHashMap<ShapingCacheKey, TextRun> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long hits;
    private long misses;
    private long evictions;

    public ShapingCache(int maximumEntries) {
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.maximumEntries = maximumEntries;
    }

    /** 返回缓存上限。 */
    public int maximumEntries() {
        return maximumEntries;
    }

    /** 返回当前 entry 数量。 */
    public int size() {
        return entries.size();
    }

    /** 查询并更新 LRU 访问顺序。 */
    public Optional<TextRun> get(ShapingCacheKey key) {
        Objects.requireNonNull(key, "key");
        TextRun result = entries.get(key);
        if (result == null) {
            misses++;
            return Optional.empty();
        }
        hits++;
        return Optional.of(result);
    }

    /**
     * 查询 shaping 结果；miss 时只计算一次并加入缓存。
     */
    public TextRun getOrCompute(ShapingCacheKey key, Function<? super ShapingCacheKey, ? extends TextRun> factory) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(factory, "factory");
        TextRun result = entries.get(key);
        if (result != null) {
            hits++;
            return result;
        }
        misses++;
        TextRun computed = Objects.requireNonNull(factory.apply(key), "factory result");
        requireMatchingKey(key, computed);
        putInternal(key, computed);
        return computed;
    }

    /** 插入或替换一个结果；替换不会计作淘汰。 */
    public void put(ShapingCacheKey key, TextRun run) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(run, "run");
        requireMatchingKey(key, run);
        putInternal(key, run);
    }

    /** 删除指定 face 的全部 entry，返回删除数量。 */
    public int invalidateFace(FontFaceId faceId) {
        Objects.requireNonNull(faceId, "faceId");
        return removeMatching(entry -> entry.getKey().faceId().equals(faceId));
    }

    /** 删除不属于指定 font generation 的 entry，返回删除数量。 */
    public int retainGeneration(FontGeneration generation) {
        Objects.requireNonNull(generation, "generation");
        return removeMatching(entry -> !entry.getKey().fontGeneration().equals(generation));
    }

    /** 清空内容和统计值。 */
    public void clear() {
        entries.clear();
        hits = 0L;
        misses = 0L;
        evictions = 0L;
    }

    /** 返回当前累计统计快照。 */
    public Statistics statistics() {
        return new Statistics(hits, misses, evictions, entries.size(), maximumEntries);
    }

    private void putInternal(ShapingCacheKey key, TextRun run) {
        boolean replacement = entries.containsKey(key);
        entries.put(key, run);
        if (!replacement && entries.size() > maximumEntries) {
            Iterator<Map.Entry<ShapingCacheKey, TextRun>> iterator = entries.entrySet().iterator();
            iterator.next();
            iterator.remove();
            evictions++;
        }
    }

    private int removeMatching(java.util.function.Predicate<Map.Entry<ShapingCacheKey, TextRun>> predicate) {
        int removed = 0;
        Iterator<Map.Entry<ShapingCacheKey, TextRun>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            if (predicate.test(iterator.next())) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    private static void requireMatchingKey(ShapingCacheKey key, TextRun run) {
        if (!key.equals(run.key())) {
            throw new IllegalArgumentException("TextRun key does not match shaping cache key");
        }
    }

    /** shaping 缓存累计统计。 */
    public record Statistics(long hits, long misses, long evictions, int size, int maximumEntries) {
    }
}
