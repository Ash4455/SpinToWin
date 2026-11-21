package com.spintowin.draw.support;

import com.spintowin.draw.draw.service.RandomPpmSource;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 固定的隨機數序列：測試先 {@link #willReturn} 排好每一抽的 ppm 落點，抽獎依序取用。
 * 序列用完時直接失敗，避免測試在不知情下抽到未安排的結果。
 */
public class SequenceRandomPpmSource implements RandomPpmSource {

    private final ConcurrentLinkedQueue<Integer> queue = new ConcurrentLinkedQueue<>();

    public void willReturn(int... ppms) {
        for (int ppm : ppms) {
            queue.add(ppm);
        }
    }

    public void reset() {
        queue.clear();
    }

    @Override
    public int nextPpm() {
        Integer next = queue.poll();
        if (next == null) {
            throw new IllegalStateException("SequenceRandomPpmSource is exhausted; arrange more values with willReturn(..)");
        }
        return next;
    }
}
