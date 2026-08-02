package io.hortora.trellis.garden;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EntryRefCount(String geId, int referenceCount) {}
