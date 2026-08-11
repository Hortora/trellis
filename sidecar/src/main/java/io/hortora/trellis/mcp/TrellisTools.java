package io.hortora.trellis.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.pages.push.EventBroadcaster;
import io.hortora.trellis.agent.AgentProcessManager;
import io.hortora.trellis.agent.ProcessTreeWalker;
import io.hortora.trellis.agent.StartAgentRequest;
import io.hortora.trellis.lifecycle.LifecycleManager;
import io.hortora.trellis.lifecycle.SlotAgentCoordinator;
import io.hortora.trellis.scanner.FileWatcherService;
import io.hortora.trellis.terminal.SessionLogger;
import io.hortora.trellis.terminal.TerminalRegistry;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class TrellisTools {

    @Inject
    @Any
    Instance<ModelProvider> providers;

    @Inject
    GenerationCounter generation;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    TerminalRegistry registry;

    @Inject
    AgentProcessManager processManager;

    @Inject
    SessionLogger sessionLogger;

    @Inject
    LifecycleManager lifecycleManager;

    @Inject
    SlotAgentCoordinator coordinator;

    @Inject
    FileWatcherService fileWatcher;

    @Inject
    UIStateStore uiStateStore;

    @Inject
    EventBroadcaster broadcaster;


    private ToolResponse dispatchFrontendCommand(String topic, Map<String, Object> payload) {
        try {
            if (!uiStateStore.hasFrontend()) {
                return ToolResponse.error("no frontend connected");
            }
            var correlationId = UUID.randomUUID().toString();
            var future        = uiStateStore.registerNavigation(correlationId);
            var eventPayload  = new LinkedHashMap<>(payload);
            eventPayload.put("correlationId", correlationId);
            broadcaster.broadcast(topic, eventPayload);
            try {
                var postState = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
                return ToolResponse.success(objectMapper.writeValueAsString(postState));
            } catch (java.util.concurrent.TimeoutException e) {
                uiStateStore.cleanupNavigation(correlationId);
                return ToolResponse.error("timeout: " + topic);
            }
        } catch (Exception e) {
            return ToolResponse.error("command failed: " + e.getMessage());
        }
    }

    @Tool(name = "trellis_model", description = "Query application state and discover available actions")
    public ToolResponse trellisModel(
            @ToolArg(name = "path", description = "Model path, e.g. 'terminals' or 'terminals/engine'", required = false) String path) {
        try {
            long gen = generation.current();
            Object result;
            if (path == null || path.isEmpty()) {
                var tree = new LinkedHashMap<String, Object>();
                for (var provider : providers) {
                    tree.put(provider.domain(), provider.summary());
                }
                tree.put("generation", gen);
                result = tree;
            } else {
                var segments = path.split("/", 2);
                var domain = segments[0];
                var subpath = segments.length > 1 ? segments[1] : null;
                ModelProvider target = null;
                for (var provider : providers) {
                    if (provider.domain().equals(domain)) {
                        target = provider;
                        break;
                    }
                }
                if (target == null) {
                    return ToolResponse.error("not_found: no domain '" + domain + "'");
                }
                var resolved = target.resolve(subpath);
                if (resolved == null) {
                    return ToolResponse.error("not_found: " + path);
                }
                var wrapper = new LinkedHashMap<String, Object>();
                wrapper.put(domain, resolved);
                wrapper.put("generation", gen);
                result = wrapper;
            }
            return ToolResponse.success(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            return ToolResponse.error("model assembly failed: " + e.getMessage());
        }
    }

    @Tool(name = "trellis_navigate", description = "Activate a UI element (panel, frame, tab)")
    public ToolResponse trellisNavigate(
            @ToolArg(name = "target", description = "Target model path") String target) {
        return dispatchFrontendCommand("control:navigate", Map.of("target", target));
    }

    @Tool(name = "trellis_terminal", description = "Terminal I/O (read log, send input, create, destroy)")
    public ToolResponse trellisTerminal(
            @ToolArg(name = "name", description = "Terminal name") String name,
            @ToolArg(name = "operation", description = "Operation: read-log, send-input, create, destroy, resize") String operation,
            @ToolArg(name = "params", description = "Operation parameters as JSON", required = false) String params) {
        try {
            var p = params != null ? objectMapper.readValue(params, Map.class) : Map.of();
            return switch (operation) {
                case "read-log" -> {
                    int lines = p.containsKey("lines") ? ((Number) p.get("lines")).intValue() : 50;
                    int offset = p.containsKey("offset") ? ((Number) p.get("offset")).intValue() : 0;
                    var content = offset > 0
                            ? sessionLogger.tailLinesWithOffset(name, lines, offset)
                            : sessionLogger.tailLines(name, lines);
                    yield ToolResponse.success(content);
                }
                case "send-input" -> {
                    var text = (String) p.get("text");
                    if (text == null) yield ToolResponse.error("missing required param: text");
                    sessionLogger.appendMarker(name, text);
                    registry.sendKeys(name, text);
                    yield ToolResponse.success("sent");
                }
                case "create" -> {
                    var workingDir = (String) p.get("workingDir");
                    var slot = (String) p.get("slot");
                    var repo = (String) p.get("repo");
                    var issue = (String) p.get("issue");
                    registry.createSession(name, workingDir, slot, repo, issue);
                    yield ToolResponse.success("created: " + name);
                }
                case "destroy" -> {
                    if (registry.get(name).isEmpty()) {
                        yield ToolResponse.error("not found: " + name);
                    }
                    registry.destroySession(name);
                    yield ToolResponse.success("destroyed: " + name);
                }
                case "resize" -> {
                    int cols = p.containsKey("cols") ? ((Number) p.get("cols")).intValue() : 200;
                    int rows = p.containsKey("rows") ? ((Number) p.get("rows")).intValue() : 50;
                    registry.resize(name, cols, rows);
                    yield ToolResponse.success("resized: " + name);
                }
                default -> ToolResponse.error("invalid operation: " + operation);
            };
        } catch (IllegalStateException e) {
            return ToolResponse.error(e.getMessage());
        } catch (Exception e) {
            return ToolResponse.error("operation failed: " + e.getMessage());
        }
    }

    @Tool(name = "trellis_agent", description = "Agent lifecycle (start, stop, pause, resume)")
    public ToolResponse trellisAgent(
            @ToolArg(name = "terminal", description = "Terminal name") String terminal,
            @ToolArg(name = "operation", description = "Operation: start, stop, graceful-shutdown, pause, resume, refresh, stats, tree") String operation,
            @ToolArg(name = "params", description = "Operation parameters as JSON", required = false) String params) {
        try {
            var termInfo = registry.get(terminal);
            if (termInfo.isEmpty()) {
                return ToolResponse.error("not found: " + terminal);
            }
            var lock = processManager.lockFor(terminal);
            if (!lock.tryLock()) {
                return ToolResponse.error("concurrent operation: " + terminal);
            }
            try {
                return switch (operation) {
                    case "start" -> {
                        var p = params != null ? objectMapper.readValue(params, Map.class) : Map.of();
                        boolean resume = Boolean.TRUE.equals(p.get("resume"));
                        String prompt = (String) p.get("prompt");
                        var request = new StartAgentRequest(resume, prompt);
                        request.validate();
                        processManager.startAgent(terminal, request);
                        yield ToolResponse.success(objectMapper.writeValueAsString(
                                processManager.getSnapshot(terminal, termInfo.get())));
                    }
                    case "stop" -> {
                        processManager.stopAgent(terminal);
                        yield ToolResponse.success(objectMapper.writeValueAsString(
                                processManager.getSnapshot(terminal, termInfo.get())));
                    }
                    case "graceful-shutdown" -> {
                        processManager.gracefulShutdown(terminal);
                        yield ToolResponse.success(objectMapper.writeValueAsString(
                                processManager.getSnapshot(terminal, termInfo.get())));
                    }
                    case "pause" -> {
                        processManager.pauseAgent(terminal);
                        yield ToolResponse.success(objectMapper.writeValueAsString(
                                processManager.getSnapshot(terminal, termInfo.get())));
                    }
                    case "resume" -> {
                        processManager.resumeAgent(terminal);
                        yield ToolResponse.success(objectMapper.writeValueAsString(
                                processManager.getSnapshot(terminal, termInfo.get())));
                    }
                    case "refresh" -> {
                        processManager.refreshAgent(terminal);
                        yield ToolResponse.success(objectMapper.writeValueAsString(
                                processManager.getSnapshot(terminal, termInfo.get())));
                    }
                    case "stats" -> ToolResponse.success(objectMapper.writeValueAsString(
                            processManager.getSnapshot(terminal, termInfo.get())));
                    case "tree" -> {
                        var snapshot = processManager.getSnapshot(terminal, termInfo.get());
                        if (snapshot.process() == null || snapshot.process().pid() <= 0) {
                            yield ToolResponse.success(objectMapper.writeValueAsString(
                                    Map.of("rootPid", 0, "totalBytes", 0, "processes", List.of())));
                        }
                        var treeOpt = ProcessTreeWalker.walk(snapshot.process().pid());
                        if (treeOpt.isEmpty()) {
                            yield ToolResponse.success(objectMapper.writeValueAsString(
                                    Map.of("rootPid", 0, "totalBytes", 0, "processes", List.of())));
                        }
                        var tree = treeOpt.get();
                        var processes = tree.entries().stream()
                                .map(e -> Map.of(
                                        "pid", e.pid(),
                                        "ppid", e.ppid(),
                                        "rssBytes", e.rssBytes(),
                                        "command", e.command()))
                                .toList();
                        yield ToolResponse.success(objectMapper.writeValueAsString(
                                Map.of("rootPid", tree.claudePid(),
                                        "totalBytes", tree.totalRssBytes(),
                                        "processes", processes)));
                    }
                    default -> ToolResponse.error("invalid operation: " + operation);
                };
            } finally {
                lock.unlock();
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ToolResponse.error(e.getMessage());
        } catch (Exception e) {
            return ToolResponse.error("operation failed: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    @Tool(name = "trellis_lifecycle", description = "Slot and workspace lifecycle operations")
    public ToolResponse trellisLifecycle(
            @ToolArg(name = "operation", description = "Operation: start, end, pause, resume, slot-create, slot-merge, epic-setup, epic-next") String operation,
            @ToolArg(name = "params", description = "Operation parameters as JSON", required = false) String params) {
        try {
            var p = params != null ? objectMapper.readValue(params, Map.class) : Map.<String, Object>of();
            var opResult = switch (operation) {
                case "start" -> {
                    var root = Path.of((String) p.get("workspaceRoot"));
                    var branch = (String) p.get("branch");
                    var issue = (String) p.get("issue");
                    yield lifecycleManager.start(root, branch, issue);
                }
                case "end" -> {
                    var slotId = (String) p.get("slotId");
                    var root = Path.of((String) p.get("workspaceRoot"));
                    yield coordinator.coordinatedEnd(slotId, root);
                }
                case "pause" -> {
                    var slotId = (String) p.get("slotId");
                    var root = Path.of((String) p.get("workspaceRoot"));
                    yield coordinator.coordinatedPause(slotId, root);
                }
                case "resume" -> {
                    var slotId = (String) p.get("slotId");
                    var root = Path.of((String) p.get("workspaceRoot"));
                    yield coordinator.coordinatedResume(slotId, root);
                }
                case "slot-create" -> {
                    var root = Path.of((String) p.get("workspaceRoot"));
                    var args = p.containsKey("args") ? (List<String>) p.get("args") : List.<String>of();
                    yield lifecycleManager.slotCreate(root, args);
                }
                case "slot-merge" -> {
                    var slotId = (String) p.get("slotId");
                    var root = Path.of((String) p.get("workspaceRoot"));
                    yield lifecycleManager.slotMerge(slotId, root);
                }
                case "epic-setup" -> {
                    var root = Path.of((String) p.get("workspaceRoot"));
                    var args = p.containsKey("args") ? (List<String>) p.get("args") : List.<String>of();
                    yield lifecycleManager.epicSetup(root, args);
                }
                case "epic-next" -> {
                    var epicPath = (String) p.get("epicPath");
                    yield lifecycleManager.epicNext(epicPath);
                }
                default -> throw new IllegalArgumentException("invalid operation: " + operation);
            };
            return ToolResponse.success(objectMapper.writeValueAsString(opResult));
        } catch (IllegalArgumentException e) {
            return ToolResponse.error(e.getMessage());
        } catch (Exception e) {
            return ToolResponse.error("operation failed: " + e.getMessage());
        }
    }

    @Tool(name = "trellis_workspace", description = "Workspace queries and frame/tab management. Query: path + refresh. Mutate: operation + params.")
    public ToolResponse trellisWorkspace(
            @ToolArg(name = "path", description = "Workspace subpath", required = false) String path,
            @ToolArg(name = "refresh", description = "Force fresh scan", required = false) Boolean refresh,
            @ToolArg(name = "operation", description = "Frame/tab operation: frame-create, frame-remove, frame-move, frame-resize, frame-pin, frame-unpin, frame-detach, frame-attach, tab-add, tab-remove, group-save, group-update, group-delete, organiser-apply, scan-root", required = false) String operation,
            @ToolArg(name = "params", description = "JSON parameters for the operation", required = false) String params) {
        if (operation != null) {
            try {
                @SuppressWarnings("unchecked")
                var parsedParams = params != null
                                   ? (Map<String, Object>) objectMapper.readValue(params, Map.class)
                                   : Map.<String, Object>of();
                if ("scan-root".equals(operation)) {
                    var root = (String) parsedParams.get("root");
                    if (root == null || root.isBlank()) {
                        return ToolResponse.error("scan-root requires params.root");
                    }
                    var rootPath = io.hortora.trellis.util.PathUtil.resolveRoot(root);
                    if (!java.nio.file.Files.isDirectory(rootPath)) {
                        return ToolResponse.error("directory not found: " + root);
                    }
                    fileWatcher.watch(rootPath);
                    var model = fileWatcher.currentModel(rootPath);
                    return ToolResponse.success(objectMapper.writeValueAsString(model));
                }
                return dispatchFrontendCommand("control:workspace",
                                               Map.of("command", operation, "params", parsedParams));
            } catch (Exception e) {
                return ToolResponse.error("invalid params: " + e.getMessage());
            }
        }
        try {
            var models = fileWatcher.allModels();
            if (models.isEmpty()) {
                return ToolResponse.success(objectMapper.writeValueAsString(List.of()));
            }
            var model = models.getFirst();
            if (Boolean.TRUE.equals(refresh)) {
                fileWatcher.onWorkspaceChanged(model.root());
                model = fileWatcher.currentModel(model.root());
                if (model == null) {
                    return ToolResponse.success(objectMapper.writeValueAsString(List.of()));
                }
            }
            if (path == null || path.isEmpty()) {
                return ToolResponse.success(objectMapper.writeValueAsString(model));
            }
            var result = switch (path.split("/")[0]) {
                case "repos" -> (Object) model.repos();
                case "slots" -> model.slots();
                case "epics" -> model.epics();
                case "pauses" -> model.pauses();
                default -> null;
            };
            if (result == null) {
                return ToolResponse.error("not_found: " + path);
            }
            return ToolResponse.success(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            return ToolResponse.error("workspace query failed: " + e.getMessage());
        }
    }
}
