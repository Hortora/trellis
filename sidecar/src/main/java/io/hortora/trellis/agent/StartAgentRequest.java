package io.hortora.trellis.agent;

public record StartAgentRequest(boolean resume, String prompt) {
    public void validate() {
        if (resume && prompt != null) {
            throw new IllegalArgumentException("resume and prompt are mutually exclusive");
        }
    }
}
