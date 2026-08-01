package io.hortora.trellis.coordinator;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class EventRing {

    private static final int DEFAULT_CAPACITY = 256;
    private final CoordinatorEvent[] buffer;
    private int head = 0;
    private int size = 0;

    public EventRing() {
        this(DEFAULT_CAPACITY);
    }

    public EventRing(int capacity) {
        this.buffer = new CoordinatorEvent[capacity];
    }

    public synchronized void add(CoordinatorEvent event) {
        buffer[head] = event;
        head = (head + 1) % buffer.length;
        if (size < buffer.length) size++;
    }

    public synchronized List<CoordinatorEvent> snapshot() {
        var result = new ArrayList<CoordinatorEvent>(size);
        int start = (head - size + buffer.length) % buffer.length;
        for (int i = 0; i < size; i++) {
            result.add(buffer[(start + i) % buffer.length]);
        }
        return result;
    }

    public synchronized int size() {
        return size;
    }
}
