package io.hortora.trellis.push;

import io.casehub.pages.push.SessionSender;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SseSessionSender implements SessionSender {

    private final ConcurrentHashMap<String, SinkEntry> connections = new ConcurrentHashMap<>();

    public void register(String connectionId, SseEventSink sink, Sse sse) {
        connections.put(connectionId, new SinkEntry(sink, sse));
    }

    public void remove(String connectionId) {
        connections.remove(connectionId);
    }

    @Override
    public void send(String connectionId, String message) {
        var entry = connections.get(connectionId);
        if (entry == null) return;
        if (entry.sink.isClosed()) {
            connections.remove(connectionId);
            return;
        }
        entry.sink.send(entry.sse.newEvent(message));
    }

    public void purgeClosedConnections() {
        connections.entrySet().removeIf(e -> e.getValue().sink.isClosed());
    }

    private record SinkEntry(SseEventSink sink, Sse sse) {}
}
