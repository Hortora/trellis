package io.hortora.trellis.repl.sidecar;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.function.Consumer;

public final class SidecarClient {

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    public SidecarClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public String terminalUrl(String name) {
        return baseUrl + "/api/terminals/" + name;
    }

    public String agentStartUrl(String name) {
        return baseUrl + "/api/terminals/" + name + "/agent/start";
    }

    public String agentStopUrl(String name) {
        return baseUrl + "/api/terminals/" + name + "/agent/stop";
    }

    public String inputUrl(String name) {
        return baseUrl + "/api/terminals/" + name + "/input";
    }

    public String getTerminal(String name) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(terminalUrl(name))).GET().build();
        return http.send(req, BodyHandlers.ofString()).body();
    }

    public void sendInput(String terminal, String text) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(inputUrl(terminal)))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(text + "\n"))
                .build();
        http.send(req, BodyHandlers.discarding());
    }

    public void startAgent(String terminal) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(agentStartUrl(terminal)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        http.send(req, BodyHandlers.discarding());
    }

    public void stopAgent(String terminal) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(agentStopUrl(terminal)))
                .POST(HttpRequest.BodyPublishers.ofString(""))
                .build();
        http.send(req, BodyHandlers.discarding());
    }

    public void subscribeSSE(String topics, Consumer<String> listener) {
        Thread.startVirtualThread(() -> {
            try {
                var url = baseUrl + "/sse?topics=" + topics;
                var req = HttpRequest.newBuilder(URI.create(url)).GET().build();
                http.send(req, BodyHandlers.ofLines()).body()
                        .filter(line -> line.startsWith("data:"))
                        .map(line -> line.substring(5).strip())
                        .forEach(listener);
            } catch (Exception e) {
                // Connection lost — reconnect on next attempt
            }
        });
    }
}
