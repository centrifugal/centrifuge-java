package io.github.centrifugal.centrifuge;

public class SubscriptionFailEvent {
    private final SubscriptionFailReason reason;

    SubscriptionFailEvent(SubscriptionFailReason reason) {
        this.reason = reason;
    }

    public SubscriptionFailReason getReason() {
        return reason;
    }
}
