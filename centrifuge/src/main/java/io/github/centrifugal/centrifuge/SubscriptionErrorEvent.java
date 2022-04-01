package io.github.centrifugal.centrifuge;

public class SubscriptionErrorEvent {
    private final Throwable exception;

    SubscriptionErrorEvent(Throwable t) {
        this.exception = t;
    }

    public Throwable getException() {
        return exception;
    }
}
