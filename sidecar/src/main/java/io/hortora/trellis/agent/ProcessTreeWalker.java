package io.hortora.trellis.agent;

import java.io.IOException;
import java.util.*;

public class ProcessTreeWalker {

    public record ProcessTree(long claudePid, long totalRssBytes, List<Long> allPids) {}

    public static Optional<ProcessTree> fromPsOutput(String psOutput, long rootPid) {
        var children = new HashMap<Long, List<long[]>>();
        var entries = new HashMap<Long, String[]>();

        for (String line : psOutput.lines().toList()) {
            var trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            var parts = trimmed.split("\\s+", 4);
            if (parts.length < 4) continue;
            try {
                long pid = Long.parseLong(parts[0]);
                long ppid = Long.parseLong(parts[1]);
                long rss = Long.parseLong(parts[2]);
                String args = parts[3];
                children.computeIfAbsent(ppid, k -> new ArrayList<>()).add(new long[]{pid, rss});
                entries.put(pid, new String[]{args, String.valueOf(rss)});
            } catch (NumberFormatException ignored) {}
        }

        Long claudePid = findClaude(rootPid, children, entries);
        if (claudePid == null) return Optional.empty();

        var allPids = new ArrayList<Long>();
        long totalRss = collectTree(claudePid, children, entries, allPids);

        return Optional.of(new ProcessTree(claudePid, totalRss * 1024, List.copyOf(allPids)));
    }

    public static Optional<ProcessTree> walk(long rootPid) throws IOException, InterruptedException {
        var p = new ProcessBuilder("ps", "-eo", "pid=,ppid=,rss=,args=")
                .redirectErrorStream(false).start();
        var output = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return fromPsOutput(output, rootPid);
    }

    private static Long findClaude(long pid, Map<Long, List<long[]>> children,
                                    Map<Long, String[]> entries) {
        var entry = entries.get(pid);
        if (entry != null && entry[0].contains("claude")) return pid;
        var kids = children.get(pid);
        if (kids == null) return null;
        for (long[] kid : kids) {
            var found = findClaude(kid[0], children, entries);
            if (found != null) return found;
        }
        return null;
    }

    private static long collectTree(long pid, Map<Long, List<long[]>> children,
                                     Map<Long, String[]> entries, List<Long> allPids) {
        allPids.add(pid);
        long rss = Long.parseLong(entries.get(pid)[1]);
        var kids = children.get(pid);
        if (kids != null) {
            for (long[] kid : kids) {
                rss += collectTree(kid[0], children, entries, allPids);
            }
        }
        return rss;
    }
}
