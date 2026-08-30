package io.hortora.trellis.intelligence;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.hortora.trellis.scanner.FileWatcherService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.logging.Logger;

@ApplicationScoped
public class IntelligenceSweepJob {

    private static final Logger LOG = Logger.getLogger(IntelligenceSweepJob.class.getName());

    private final WorklogEventAdapter worklogAdapter;
    private final EnrichmentAdapter enrichmentAdapter;
    private final DeferredItemAdapter deferredAdapter;
    private final FileWatcherService fileWatcherService;

    @Inject
    public IntelligenceSweepJob(WorklogEventAdapter worklogAdapter,
                                EnrichmentAdapter enrichmentAdapter,
                                DeferredItemAdapter deferredAdapter,
                                FileWatcherService fileWatcherService) {
        this.worklogAdapter = worklogAdapter;
        this.enrichmentAdapter = enrichmentAdapter;
        this.deferredAdapter = deferredAdapter;
        this.fileWatcherService = fileWatcherService;
    }

    @Scheduled(every = "${trellis.intelligence.poll-interval:5m}",
               delayed = "30s",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        LOG.fine("Intelligence sweep starting");
        try {
            worklogAdapter.emitSnapshots();
        } catch (Exception e) {
            LOG.warning("Worklog adapter sweep failed: " + e.getMessage());
        }
        try {
            for (var model : fileWatcherService.allModels()) {
                enrichmentAdapter.emitIssueEvents(model.root());
            }
        } catch (Exception e) {
            LOG.warning("Enrichment adapter sweep failed: " + e.getMessage());
        }
        try {
            var planFiles = findPlanFiles();
            deferredAdapter.emitDeferredItems(planFiles);
        } catch (Exception e) {
            LOG.warning("Deferred adapter sweep failed: " + e.getMessage());
        }
        LOG.fine("Intelligence sweep complete");
    }

    private java.util.List<Path> findPlanFiles() {
        var result = new ArrayList<Path>();
        var home = Path.of(System.getProperty("user.home"));
        var workspaces = home.resolve("claude");
        if (Files.isDirectory(workspaces)) {
            try (var stream = Files.walk(workspaces, 4)) {
                stream.filter(p -> p.getFileName().toString().equals(".plan"))
                      .filter(Files::isRegularFile)
                      .forEach(result::add);
            } catch (Exception ignored) {
            }
        }
        return result;
    }
}
