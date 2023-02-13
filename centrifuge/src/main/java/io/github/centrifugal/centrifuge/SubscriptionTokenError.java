package io.github.centrifugal.centrifuge;

public class SubscriptionTokenError extends Throwable {
    private final Throwable error;

    SubscriptionTokenError(Throwable error) {
        this.error = error;
        this.initCause(error);
    }

    public Throwable getError() {
        return error;
    }
}
