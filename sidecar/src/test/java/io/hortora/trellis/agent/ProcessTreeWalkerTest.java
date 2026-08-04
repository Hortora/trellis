package io.hortora.trellis.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessTreeWalkerTest {

    @Test
    void findsClaudeInSimpleTree() {
        String psOutput = """
              100     1  1024 /bin/zsh
              101   100 204800 /usr/local/bin/node /Users/user/.claude/local/claude
              """;
        var tree = ProcessTreeWalker.fromPsOutput(psOutput, 100);
        assertTrue(tree.isPresent());
        assertEquals(101, tree.get().claudePid());
        assertEquals(204800L * 1024, tree.get().totalRssBytes());
    }

    @Test
    void sumsChildProcessRss() {
        String psOutput = """
              100     1  1024 /bin/zsh
              101   100 204800 /usr/local/bin/node /Users/user/.claude/local/claude
              102   101 51200 /usr/local/bin/node mcp-server
              103   101 25600 /usr/local/bin/node subagent
              """;
        var tree = ProcessTreeWalker.fromPsOutput(psOutput, 100);
        assertTrue(tree.isPresent());
        assertEquals(101, tree.get().claudePid());
        assertEquals((204800L + 51200 + 25600) * 1024, tree.get().totalRssBytes());
        assertEquals(3, tree.get().allPids().size());
    }

    @Test
    void returnsEmptyWhenNoClaudeFound() {
        String psOutput = """
              100     1  1024 /bin/zsh
              """;
        var tree = ProcessTreeWalker.fromPsOutput(psOutput, 100);
        assertTrue(tree.isEmpty());
    }

    @Test
    void handlesDeepTree() {
        String psOutput = """
              100     1  1024 /bin/zsh
              101   100 102400 /usr/local/bin/node /Users/user/.claude/local/claude
              102   101 20480 node child
              103   102 10240 node grandchild
              """;
        var tree = ProcessTreeWalker.fromPsOutput(psOutput, 100);
        assertTrue(tree.isPresent());
        assertEquals((102400L + 20480 + 10240) * 1024, tree.get().totalRssBytes());
        assertEquals(3, tree.get().allPids().size());
    }

    @Test
    void ignoresNonClaudeNodeProcess() {
        String psOutput = """
              100     1  1024 /bin/zsh
              101   100 102400 /usr/local/bin/node /some/other/app.js
              """;
        var tree = ProcessTreeWalker.fromPsOutput(psOutput, 100);
        assertTrue(tree.isEmpty());
    }

    @Test
    void fromPsOutputReturnsProcessEntries() {
        String psOutput = """
                          1234   100  51200 /bin/zsh
                          1235  1234 262144 /usr/local/bin/node /Users/user/.claude/local/claude --resume
                          1236  1235  46080 node playwright-mcp
                          1237  1235  35840 node intellij-mcp
                          """;
        var tree = ProcessTreeWalker.fromPsOutput(psOutput, 1234);

        assertTrue(tree.isPresent());
        var entries = tree.get().entries();
        assertEquals(3, entries.size());

        var claude = entries.stream().filter(e -> e.pid() == 1235).findFirst().orElseThrow();
        assertEquals(1234, claude.ppid());
        assertEquals(262144L * 1024, claude.rssBytes());
        assertTrue(claude.command().contains("claude"));

        var playwright = entries.stream().filter(e -> e.pid() == 1236).findFirst().orElseThrow();
        assertEquals(1235, playwright.ppid());
        assertTrue(playwright.command().contains("playwright"));
    }

}
