package io.hortora.trellis.agent;

public record ProcessEntry(long pid, long ppid, long rssBytes, String command) {}
