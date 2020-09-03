package io.github.centrifugal.centrifuge;

public class ErrorEvent {
    private String message;

    public String getMessage() {
        return message;
    }

    public Throwable getException() {
        return exception;
    }

    private Throwable exception;
}
