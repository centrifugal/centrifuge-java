package io.github.centrifugal.centrifuge;

public class SubscriptionStateError extends Exception {
    private final SubscriptionState state;

    SubscriptionStateError(SubscriptionState state) {
        this.state = state;
    }

    public SubscriptionState getState() {
        return state;
    }
}
