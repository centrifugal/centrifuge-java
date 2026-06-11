package io.github.centrifugal.centrifuge;

public class SubscriptionGetStateError extends Throwable {
    private final Throwable error;

    SubscriptionGetStateError(Throwable error) {
        super(error);
        this.error = error;
    }

    public Throwable getError() {
        return error;
    }
}
