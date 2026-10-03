package com.github.h3yow.modelservinggatewayjava.gateway.resilience;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个后端的状态变化时间线：有界环形缓冲。
 *
 * <ul>
 *   <li>容量固定，写满后覆盖最旧记录，内存占用有上限，不会拖慢评分主链路（追加 O(1)）；</li>
 *   <li>所有方法在同一把短锁上完成：并发状态迁移不会丢记录，并发查询拿到的是
 *       某一刻一致的有序快照（旧 -&gt; 新）；</li>
 *   <li>只存储 {@link StateTransitionEvent} 这类不可变对象，快照可安全对外暴露。</li>
 * </ul>
 */
public class StateTimeline {

    private final StateTransitionEvent[] ring;
    private int head = 0;
    private int size = 0;

    public StateTimeline(int capacity) {
        this.ring = new StateTransitionEvent[Math.max(1, capacity)];
    }

    /** 追加一条状态变化；容量满时覆盖最旧的一条。 */
    public synchronized void append(StateTransitionEvent event) {
        if (size < ring.length) {
            ring[(head + size) % ring.length] = event;
            size++;
        } else {
            ring[head] = event;
            head = (head + 1) % ring.length;
        }
    }

    /** 返回按时间从旧到新排列的快照副本，与后续追加完全隔离。 */
    public synchronized List<StateTransitionEvent> snapshot() {
        List<StateTransitionEvent> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(ring[(head + i) % ring.length]);
        }
        return out;
    }

    public synchronized int capacity() {
        return ring.length;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void clear() {
        head = 0;
        size = 0;
    }
}
