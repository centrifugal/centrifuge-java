package io.github.centrifugal.centrifuge;

public class SubscriptionRefreshError extends Throwable {
    private final Throwable error;

    SubscriptionRefreshError(Throwable error) {
        super(error);
        this.error = error;
    }

    public Throwable getError() {
        return error;
    }
}
