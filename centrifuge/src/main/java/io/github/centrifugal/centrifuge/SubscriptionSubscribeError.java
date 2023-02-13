package io.github.centrifugal.centrifuge;

public class SubscriptionSubscribeError extends Throwable {
    private final Throwable error;

    SubscriptionSubscribeError(Throwable error) {
        this.error = error;
        this.initCause(error);
    }

    public Throwable getError() {
        return error;
    }
}
