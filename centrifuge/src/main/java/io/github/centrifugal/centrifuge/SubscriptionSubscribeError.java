package io.github.centrifugal.centrifuge;

public class SubscriptionSubscribeError extends Throwable {
    private final Throwable error;

    SubscriptionSubscribeError(Throwable error) {
        super(error);
        this.error = error;
    }

    public Throwable getError() {
        return error;
    }
}
