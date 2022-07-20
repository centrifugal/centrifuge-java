package io.github.centrifugal.centrifuge;

public class SubscriptionErrorEvent {
    private final Throwable error;

    SubscriptionErrorEvent(Throwable t) {
        this.error = t;
    }

    public Throwable getError() {
        return error;
    }
}
