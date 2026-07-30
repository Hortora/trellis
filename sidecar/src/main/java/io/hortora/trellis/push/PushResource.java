package io.hortora.trellis.push;

import io.casehub.pages.push.TopicRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.List;
import java.util.UUID;

@Path("/api/push")
public class PushResource {

    @Inject
    SseSessionSender sessionSender;

    @Inject
    TopicRegistry topicRegistry;

    @GET
    @Produces(MediaType.SERVER_SENT_EVENTS)
    public void stream(@QueryParam("topics") List<String> topics,
                       @Context SseEventSink sink,
                       @Context Sse sse) {
        String connectionId = UUID.randomUUID().toString();
        sessionSender.register(connectionId, sink, sse);

        if (topics != null && !topics.isEmpty()) {
            topicRegistry.listen(connectionId, topics);
        }

        sink.send(sse.newEvent("connected", connectionId));
    }
}
