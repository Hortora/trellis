package io.hortora.trellis.lifecycle;

public class StepFailedException extends Exception {
    public StepFailedException(String stepName) {
        super("Step failed: " + stepName);
    }
}
