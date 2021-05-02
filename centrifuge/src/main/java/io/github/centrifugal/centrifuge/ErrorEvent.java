package io.github.centrifugal.centrifuge;

public class ErrorEvent {
    private final Throwable exception;

    ErrorEvent(Throwable t) {
        this.exception = t;
    }

    public Throwable getException() {
        return exception;
    }
}
