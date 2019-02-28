package io.github.centrifugal.centrifuge;

public abstract class SubscriptionEventListener {

    public void onPublish(Subscription sub, PublishEvent event) {

    };

    public void onJoin(Subscription sub, JoinEvent event) {

    };

    public void onLeave(Subscription sub, LeaveEvent event) {

    };

    public void onSubscribeSuccess(Subscription sub, SubscribeSuccessEvent event) {

    };

    public void onSubscribeError(Subscription sub, SubscribeErrorEvent event) {

    };

    public void onUnsubscribe(Subscription sub, UnsubscribeEvent event) {

    };
}
