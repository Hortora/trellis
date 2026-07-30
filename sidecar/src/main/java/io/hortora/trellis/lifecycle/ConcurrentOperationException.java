package io.hortora.trellis.lifecycle;

public class ConcurrentOperationException extends Exception {
    public ConcurrentOperationException(String message) {
        super(message);
    }
}
