package io.github.centrifugal.centrifuge;

public abstract class SubscriptionStateGetter {
    public void getSubscriptionState(SubscriptionGetStateEvent event, StateCallback cb) {
        cb.Done(null, null);
    };
}
