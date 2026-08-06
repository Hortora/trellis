package io.hortora.trellis.agent;

import io.casehub.pages.push.EventBroadcaster;
import io.hortora.trellis.terminal.TerminalInfo;
import io.hortora.trellis.terminal.TmuxManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentProcessManagerTest {

    TmuxManager tmux;
    EventBroadcaster broadcaster;
    AgentProcessManager manager;

    @BeforeEach
    void setUp() {
        tmux = mock(TmuxManager.class);
        broadcaster = mock(EventBroadcaster.class);
        manager = new AgentProcessManager(tmux, broadcaster, new io.hortora.trellis.mcp.GenerationCounter());
    }

    @Test
    void idleWhenShellIsForegrounded() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");
        when(tmux.displayMessage("t1", "#{pane_pid}")).thenReturn("100");

        var terminal = new TerminalInfo("t1", "/tmp", null, null, null);
        manager.pollTerminal(terminal);

        var snapshot = manager.getSnapshot("t1", terminal);
        assertNull(snapshot.process());
    }

    @Test
    void runningWhenClaudeDetected() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("node");
        when(tmux.displayMessage("t1", "#{pane_pid}")).thenReturn("100");

        var terminal = new TerminalInfo("t1", "/tmp", null, null, null);
        String fakePsOutput = """
              100     1  1024 /bin/zsh
              101   100 204800 /usr/local/bin/node /Users/user/.claude/local/claude
              """;
        manager.pollTerminalWithPsOutput(terminal, fakePsOutput);

        var snapshot = manager.getSnapshot("t1", terminal);
        assertNotNull(snapshot.process());
        assertEquals(AgentState.RUNNING, snapshot.process().state());
        assertEquals(101, snapshot.process().pid());
    }

    @Test
    void pausedPreservedAcrossMonitorCycles() throws Exception {
        var terminal = new TerminalInfo("t1", "/tmp", null, null, null);
        manager.setPaused("t1", "claude");

        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");
        when(tmux.displayMessage("t1", "#{pane_pid}")).thenReturn("100");

        manager.pollTerminal(terminal);

        var snapshot = manager.getSnapshot("t1", terminal);
        assertNotNull(snapshot.process());
        assertEquals(AgentState.PAUSED, snapshot.process().state());
    }

    @Test
    void bootstrapRestoresPausedState() throws Exception {
        when(tmux.getOption("t1", "@trellis_agent_state")).thenReturn(Optional.of("PAUSED"));

        var terminals = List.of(new TerminalInfo("t1", "/tmp", null, null, null));
        manager.initializeFromBootstrap(terminals);

        var snapshot = manager.getSnapshot("t1", terminals.get(0));
        assertNotNull(snapshot.process());
        assertEquals(AgentState.PAUSED, snapshot.process().state());
    }

    @Test
    void runningToIdleSetsLastError() throws Exception {
        var terminal = new TerminalInfo("t1", "/tmp", null, null, null);
        String runningPsOutput = """
              100     1  1024 /bin/zsh
              101   100 204800 /usr/local/bin/node /Users/user/.claude/local/claude
              """;
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("node");
        when(tmux.displayMessage("t1", "#{pane_pid}")).thenReturn("100");
        manager.pollTerminalWithPsOutput(terminal, runningPsOutput);
        assertEquals(AgentState.RUNNING, manager.getSnapshot("t1", terminal).process().state());

        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");
        manager.pollTerminal(terminal);

        var snapshot = manager.getSnapshot("t1", terminal);
        assertNull(snapshot.process());
        assertNotNull(snapshot.lastError());
        assertTrue(snapshot.lastError().contains("ended"));
    }

    @Test
    void nonClaudeNodeProcessStaysIdle() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("node");
        when(tmux.displayMessage("t1", "#{pane_pid}")).thenReturn("100");

        var terminal = new TerminalInfo("t1", "/tmp", null, null, null);
        String fakePsOutput = """
              100     1  1024 /bin/zsh
              101   100 102400 /usr/local/bin/node /some/other/app.js
              """;
        manager.pollTerminalWithPsOutput(terminal, fakePsOutput);

        var snapshot = manager.getSnapshot("t1", terminal);
        assertNull(snapshot.process());
    }

    @Test
    void startAgentSendsClaudeCommand() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");

        manager.startAgent("t1", new StartAgentRequest(false, null));

        verify(tmux).sendKeys(eq("t1"), eq("claude\n"));
        var snapshot = manager.getSnapshot("t1", new TerminalInfo("t1", "/tmp", null, null, null));
        assertEquals(AgentState.STARTING, snapshot.process().state());
    }

    @Test
    void startAgentWithPromptEscapesSingleQuotes() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");

        manager.startAgent("t1", new StartAgentRequest(false, "Fix the 'auth' flow"));

        verify(tmux).sendKeys(eq("t1"), eq("claude -p 'Fix the '\\''auth'\\'' flow'\n"));
    }

    @Test
    void startAgentResumeSendsClaudeC() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");

        manager.startAgent("t1", new StartAgentRequest(true, null));

        verify(tmux).sendKeys(eq("t1"), eq("claude -c\n"));
    }

    @Test
    void startAgentRejectsNonShellForeground() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("node");

        assertThrows(IllegalStateException.class,
                     () -> manager.startAgent("t1", new StartAgentRequest(false, null)));
    }

    @Test
    void pauseAgentPersistsTmuxOption() throws Exception {
        agents().put("t1", new AgentProcess(101, AgentState.RUNNING, 204800, java.time.Instant.now(), "claude"));

        manager.pauseAgent("t1");

        verify(tmux).setOption("t1", "@trellis_agent_state", "PAUSED");
        assertEquals(AgentState.PAUSED, agents().get("t1").state());
        assertEquals(0, agents().get("t1").pid());
    }

    @Test
    void resumeAgentClearsTmuxOption() throws Exception {
        agents().put("t1", AgentProcess.paused("claude"));
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");

        manager.resumeAgent("t1");

        verify(tmux).setOption("t1", "@trellis_agent_state", "");
        verify(tmux).sendKeys(eq("t1"), eq("claude -c\n"));
        assertEquals(AgentState.STARTING, agents().get("t1").state());
    }

    @Test
    void stopAgentFromPausedSkipsKill() throws Exception {
        agents().put("t1", AgentProcess.paused("claude"));

        manager.stopAgent("t1");

        verify(tmux).setOption("t1", "@trellis_agent_state", "");
        assertNull(agents().get("t1"));
    }


    @Test
    void bootstrapRecoversPausedByCoordinatorState() throws Exception {
        when(tmux.getOption("t1", "@trellis_agent_state"))
                .thenReturn(Optional.of("PAUSED_BY_COORDINATOR"));
        var terminal = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        manager.initializeFromBootstrap(List.of(terminal));
        var snapshot = manager.getSnapshot("t1", terminal);
        assertNotNull(snapshot.process());
        assertEquals(AgentState.PAUSED_BY_COORDINATOR, snapshot.process().state());
    }

    @Test
    void pausedByCoordinatorPreservedAcrossMonitorCycles() throws Exception {
        var terminal = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        agents().put("t1", AgentProcess.pausedByCoordinator("claude"));

        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");
        when(tmux.displayMessage("t1", "#{pane_pid}")).thenReturn("100");

        manager.pollTerminal(terminal);

        var snapshot = manager.getSnapshot("t1", terminal);
        assertNotNull(snapshot.process());
        assertEquals(AgentState.PAUSED_BY_COORDINATOR, snapshot.process().state());
    }


    @Test
    void gracefulShutdownSendsEscapeThenExit() throws Exception {
        var    terminal = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        String psOutput = "  100     1  1024 /bin/zsh\n  101   100 204800 /usr/local/bin/node /Users/user/.claude/local/claude\n";
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("node");
        when(tmux.displayMessage("t1", "#{pane_pid}")).thenReturn("100");
        manager.pollTerminalWithPsOutput(terminal, psOutput);
        assertEquals(AgentState.RUNNING, manager.getSnapshot("t1", terminal).process().state());

        org.mockito.Mockito.reset(tmux);
        when(tmux.displayMessage("t1", "#{pane_current_command}"))
                .thenReturn("node")
                .thenReturn("zsh");

        manager.gracefulShutdown("t1");

        var inOrder = org.mockito.Mockito.inOrder(tmux);
        inOrder.verify(tmux).sendKeys("t1", "Escape");
        inOrder.verify(tmux).sendKeys("t1", "/exit\n");
        var snapshot = manager.getSnapshot("t1", terminal);
        assertNotNull(snapshot.process());
        assertEquals(AgentState.PAUSED_BY_COORDINATOR, snapshot.process().state());
    }

    @Test
    void gracefulShutdownSkipsExitIfShellAppearsAfterEscape() throws Exception {
        var    terminal = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        String psOutput = "  100     1  1024 /bin/zsh\n  101   100 204800 /usr/local/bin/node /Users/user/.claude/local/claude\n";
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("node");
        when(tmux.displayMessage("t1", "#{pane_pid}")).thenReturn("100");
        manager.pollTerminalWithPsOutput(terminal, psOutput);

        org.mockito.Mockito.reset(tmux);
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");

        manager.gracefulShutdown("t1");

        verify(tmux).sendKeys("t1", "Escape");
        verify(tmux, org.mockito.Mockito.never()).sendKeys(eq("t1"), eq("/exit\n"));
        assertEquals(AgentState.PAUSED_BY_COORDINATOR,
                     manager.getSnapshot("t1", terminal).process().state());
    }

    @Test
    void gracefulShutdownIsNoOpForIdleAgent() throws Exception {
        var terminal = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        manager.gracefulShutdown("t1");
        assertNull(manager.getSnapshot("t1", terminal).process());
    }

    @Test
    void gracefulShutdownUsesTreeKillForStartingAgent() throws Exception {
        manager.setStarting("t1", "claude");
        var terminal = new TerminalInfo("t1", "/tmp", "slot-1", null, null);
        manager.gracefulShutdown("t1");
        verify(tmux, org.mockito.Mockito.never()).sendKeys(eq("t1"), eq("Escape"));
        verify(tmux, org.mockito.Mockito.never()).sendKeys(eq("t1"), eq("/exit\n"));
        assertEquals(AgentState.PAUSED_BY_COORDINATOR,
                     manager.getSnapshot("t1", terminal).process().state());
    }


    @Test
    void pauseAgentRejectsIdleState() {
        assertThrows(IllegalStateException.class, () -> manager.pauseAgent("t1"));
    }

    @Test
    void resumeAgentRejectsRunningState() {
        agents().put("t1", new AgentProcess(101, AgentState.RUNNING, 204800, java.time.Instant.now(), "claude"));
        assertThrows(IllegalStateException.class, () -> manager.resumeAgent("t1"));
    }

    @Test
    void startAgentRejectsClaudeVersionAsForeground() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("2.1.221");

        var ex = assertThrows(IllegalStateException.class,
                              () -> manager.startAgent("t1", new StartAgentRequest(false, null)));
        assertTrue(ex.getMessage().contains("already running"),
                   "Expected 'already running' message, got: " + ex.getMessage());
    }

    @Test
    void startAgentRetriesWhenForegroundIsEmpty() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}"))
                .thenReturn("")
                .thenReturn("")
                .thenReturn("zsh");

        manager.startAgent("t1", new StartAgentRequest(false, null));

        verify(tmux).sendKeys(eq("t1"), eq("claude\n"));
    }

    @Test
    void startAgentTimesOutWhenForegroundStaysEmpty() throws Exception {
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("");

        var ex = assertThrows(IllegalStateException.class,
                              () -> manager.startAgent("t1", new StartAgentRequest(false, null)));
        assertTrue(ex.getMessage().contains("did not start"),
                   "Expected timeout message, got: " + ex.getMessage());
    }

    @Test
    void pauseSetsPausedStateBeforeKillingProcess() throws Exception {
        var terminal = new TerminalInfo("t1", "/tmp", null, null, null);
        String psOutput = """
                          100     1  1024 /bin/zsh
                          101   100 204800 /usr/local/bin/node /Users/user/.claude/local/claude
                          """;
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("node");
        when(tmux.displayMessage("t1", "#{pane_pid}")).thenReturn("100");
        manager.pollTerminalWithPsOutput(terminal, psOutput);
        assertEquals(AgentState.RUNNING, manager.getSnapshot("t1", terminal).process().state());

        manager.pauseAgent("t1");

        // Simulate a poll cycle arriving AFTER pause (process killed, shell visible)
        when(tmux.displayMessage("t1", "#{pane_current_command}")).thenReturn("zsh");
        manager.pollTerminal(terminal);

        // Agent must still be PAUSED — the poll must NOT have removed it
        var snapshot = manager.getSnapshot("t1", terminal);
        assertNotNull(snapshot.process(), "Paused agent should not be removed by poll");
        assertEquals(AgentState.PAUSED, snapshot.process().state(),
                     "Agent should remain PAUSED after poll sees shell foreground");
    }


    @SuppressWarnings("unchecked")
    private java.util.concurrent.ConcurrentHashMap<String, AgentProcess> agents() {
        try {
            var field = AgentProcessManager.class.getDeclaredField("agents");
            field.setAccessible(true);
            return (java.util.concurrent.ConcurrentHashMap<String, AgentProcess>) field.get(manager);
        } catch (Exception e) {throw new RuntimeException(e);}
    }
}
