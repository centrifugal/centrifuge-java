package io.github.centrifugal.centrifuge;

public class SubscriptionRefreshError extends Throwable {
    private final Throwable error;

    SubscriptionRefreshError(Throwable error) {
        this.error = error;
        this.initCause(error);
    }

    public Throwable getError() {
        return error;
    }
}
