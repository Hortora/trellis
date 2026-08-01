package io.hortora.trellis.issues;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class IssueEngine {

    private static final Logger LOG = Logger.getLogger(IssueEngine.class);

    @Inject
    IssueCache cache;

    @Inject
    DependencyParser parser;

    @Inject
    ObjectMapper mapper;
    @Inject
    @io.hortora.trellis.coordinator.IssuesCacheRefreshed
    jakarta.enterprise.event.Event<String> issuesCacheRefreshed;


    public List<IssueInfo> fetchIssues(String owner, String repo) {
        try {
            var issues = fetchFromGitHub(owner, repo);
            cache.save(owner, repo, issues);
            if (issuesCacheRefreshed != null) issuesCacheRefreshed.fireAsync(owner + "/" + repo);
            return issues;
        } catch (Exception e) {
            LOG.warnf(e, "Failed to fetch issues from GitHub for %s/%s — serving from cache", owner, repo);
            return cache.load(owner, repo);
        }
    }

    public DependencyGraph buildGraph(String owner, String repo, List<IssueInfo> issues) {
        var nodes = issues.stream().map(IssueInfo::key).collect(Collectors.toSet());
        var deps = new ArrayList<Dependency>();
        for (var issue : issues) {
            deps.addAll(parser.parse(issue));
        }
        var closed = issues.stream()
                .filter(i -> "CLOSED".equals(i.state()))
                .map(IssueInfo::key)
                .collect(Collectors.toSet());
        return new DependencyGraph(nodes, deps, closed);
    }

    List<IssueInfo> fetchFromGitHub(String owner, String repo) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(
                "gh", "issue", "list",
                "--repo", owner + "/" + repo,
                "--json", "number,title,body,labels,state,closedAt",
                "--limit", "500",
                "--state", "all"
        );
        pb.redirectErrorStream(false);
        var process = pb.start();
        var stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return process.getInputStream().readAllBytes(); }
            catch (java.io.IOException e) { return new byte[0]; }
        });
        var stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getErrorStream().readAllBytes()); }
            catch (java.io.IOException e) { return ""; }
        });

        boolean completed = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
        if (!completed) {
            process.destroyForcibly();
            throw new IOException("gh issue list timed out after 60s");
        }

        var output = stdoutFuture.join();
        var errOutput = stderrFuture.join();
        int exit = process.exitValue();

        if (exit != 0) {
            throw new IOException("gh issue list failed (exit " + exit + "): " + errOutput);
        }

        var jsonNodes = mapper.readTree(output);
        var issues = new ArrayList<IssueInfo>();

        for (var node : jsonNodes) {
            var labels = new ArrayList<String>();
            if (node.has("labels")) {
                for (var label : node.get("labels")) {
                    labels.add(label.get("name").asText());
                }
            }

            String closedAtStr = node.has("closedAt") && !node.get("closedAt").isNull()
                    ? node.get("closedAt").asText() : null;
            Instant closedAt = closedAtStr != null ? Instant.parse(closedAtStr) : null;

            issues.add(new IssueInfo(
                    owner, repo,
                    node.get("number").asInt(),
                    node.get("title").asText(),
                    node.get("state").asText(),
                    labels,
                    node.has("body") ? node.get("body").asText() : null,
                    closedAt
            ));
        }

        return issues;
    }
}
