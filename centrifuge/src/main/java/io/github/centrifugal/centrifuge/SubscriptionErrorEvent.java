package io.github.centrifugal.centrifuge;

public class SubscriptionErrorEvent extends Throwable {
    private final Throwable error;

    SubscriptionErrorEvent(Throwable t) {
        super(t);
        this.error = t;
    }

    public Throwable getError() {
        return error;
    }
}
