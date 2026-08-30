package io.hortora.trellis.dependencies;

public record IssueDependencyData(int issueNumber, String issueRepo,
                                  String title, String state, String body) {}
