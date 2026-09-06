package io.hortora.trellis.scanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceReposTest {

    @Test
    void extractOwnerRepoFromHttps() {
        assertEquals("mdproctor/blocks", WorkspaceRepos.extractOwnerRepo("https://github.com/mdproctor/blocks.git"));
    }

    @Test
    void extractOwnerRepoFromSsh() {
        assertEquals("mdproctor/blocks", WorkspaceRepos.extractOwnerRepo("git@github.com:mdproctor/blocks.git"));
    }



    @Test
    void exactMatchWhenOrgUrlsPresent() {
        // Hortora workspace uses org URLs — exact match, no casehubio leakage
        var wsRepos = java.util.List.of("Hortora/engine", "Hortora/trellis");
        var issueRepos = java.util.Set.of("Hortora/engine", "Hortora/trellis", "casehubio/engine");

        var matched = WorkspaceRepos.matchIssueRepos(wsRepos, issueRepos);
        assertEquals(java.util.Set.of("Hortora/engine", "Hortora/trellis"), matched);
    }

    @Test
    void nameMatchFallbackWhenAllForks() {
        // Casehub workspace uses fork URLs — zero exact matches, fall back to name match
        var wsRepos = java.util.List.of("mdproctor/engine", "mdproctor/blocks");
        var issueRepos = java.util.Set.of("casehubio/engine", "casehubio/blocks", "Hortora/trellis");

        var matched = WorkspaceRepos.matchIssueRepos(wsRepos, issueRepos);
        assertEquals(java.util.Set.of("casehubio/engine", "casehubio/blocks"), matched);
    }

    @Test
    void noNameLeakageWhenSomeExactMatchesExist() {
        // Mixed: Hortora/trellis matches exactly, so entire workspace uses exact mode
        // mdproctor/engine should NOT fall back to name-match casehubio/engine
        var wsRepos = java.util.List.of("Hortora/trellis", "Hortora/engine");
        var issueRepos = java.util.Set.of("Hortora/trellis", "casehubio/engine");

        var matched = WorkspaceRepos.matchIssueRepos(wsRepos, issueRepos);
        assertEquals(java.util.Set.of("Hortora/trellis"), matched);
    }
}
