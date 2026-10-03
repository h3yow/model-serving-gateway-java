package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.ArrayList;
import java.util.List;

/**
 * 有界状态时间线：保留每个后端最近 N 条关键状态变化，写满后覆盖最旧记录。
 *
 * <p>线程安全：追加与快照均在短临界区内完成（环形数组原地写入，无扩容/拷贝压力，
 * 不会拖慢评分主链路）；不会无限增长；
 * 并发迁移/查询下不丢记录、不错乱（序号单调，快照按时间正序）。
 */
public class StateTimeline {

    private final int capacity;
    private final TimelineEvent[] ring;
    /** 下一条写入位置（对 capacity 取模）。 */
    private int head = 0;
    private long nextSeq = 1L;

    public StateTimeline(int capacity) {
        this.capacity = Math.max(1, capacity);
        this.ring = new TimelineEvent[this.capacity];
    }

    /** 追加一条状态变化；写满后覆盖最旧的。 */
    public synchronized TimelineEvent append(long timestampMs, CircuitState from, CircuitState to,
                                             String reason, String detail) {
        TimelineEvent event = new TimelineEvent(nextSeq++, timestampMs, from, to, reason, detail);
        ring[head] = event;
        head = (head + 1) % capacity;
        return event;
    }

    /** 只读快照：按时间正序返回最近最多 capacity 条（不推进任何状态）。 */
    @Override
    public synchronized List<TimelineEvent> snapshot() {
        int size = size();
        List<TimelineEvent> out = new ArrayList<>(size);
        if (size == 0) {
            return List.copyOf(out);
        }
        // 最旧记录的位置：写满一圈后最旧的恰好位于 head；未满时从 0 开始
        int start = (size == capacity) ? head : 0;
        for (int i = 0; i < size; i++) {
            out.add(ring[(start + i) % capacity]);
        }
        return List.copyOf(out);
    }

    /** 当前实际保存的记录数（不超过 capacity）。 */
    public synchronized int size() {
        long written = nextSeq - 1L;
        return (int) Math.min(written, capacity);
    }

    public int capacity() {
        return capacity;
    }

    public synchronized void clear() {
        java.util.Arrays.fill(ring, null);
        head = 0;
        nextSeq = 1L;
    }
}
