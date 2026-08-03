package io.hortora.trellis.agent;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

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
}
