package io.hortora.trellis.issues;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Map;
import java.util.NoSuchElementException;

@Path("/api/repos/{owner}/{repo}")
@Produces(MediaType.APPLICATION_JSON)
public class EpicResource {

    private static final Logger LOG = Logger.getLogger(EpicResource.class);

    @Inject
    IssueEngine engine;

    @Inject
    EpicAnalyzer analyzer;

    @Inject
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Inject
    io.hortora.trellis.coordinator.EnhancedRecommendationCache recommendationCache;

    @GET
    @Path("/epics/{number}/analysis")
    public Response analysis(@PathParam("owner") String owner,
                             @PathParam("repo") String repo,
                             @PathParam("number") int number) {
        var issues = engine.fetchIssues(owner, repo);
        try {
            var analysis = analyzer.analyze(owner, repo, number, issues);
            var node     = objectMapper.valueToTree(analysis);
            var epicRef  = owner + "/" + repo + "#" + number;
            var enhanced = recommendationCache.get(epicRef);
            if (enhanced != null) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) node).putPOJO("enhancedRecommendations", enhanced);
            }
            return Response.ok(node).build();
        } catch (NoSuchElementException e) {
            return Response.status(404).entity(Map.of("error", e.getMessage())).build();
        }
    }

    @GET
    @Path("/portfolio")
    public Response portfolio(@PathParam("owner") String owner,
                              @PathParam("repo") String repo) {
        var issues    = engine.fetchIssues(owner, repo);
        var summaries = new ArrayList<EpicSummary>();

        for (var issue : issues) {
            if (!"OPEN".equals(issue.state())) {continue;}
            var children = EpicBodyParser.parseChildren(issue.body(), owner, repo);
            if (children.isEmpty()) {continue;}

            try {
                var analysis = analyzer.analyze(owner, repo, issue.number(), issues);
                summaries.add(analyzer.summarize(analysis, issue.key(), issue.title()));
            } catch (Exception e) {
                LOG.warnf(e, "Failed to analyze epic %s#%d — skipping", owner + "/" + repo, issue.number());
            }
        }

        return Response.ok(summaries).build();
    }
}
