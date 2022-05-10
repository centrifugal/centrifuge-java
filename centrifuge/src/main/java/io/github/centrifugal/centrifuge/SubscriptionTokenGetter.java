package io.github.centrifugal.centrifuge;

public abstract class SubscriptionTokenGetter {
    public void getSubscriptionToken(SubscriptionTokenEvent event, TokenCallback cb) {
        cb.Done(null, "");
    };
}
