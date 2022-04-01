package io.github.centrifugal.centrifuge;

public abstract class SubscriptionEventListener {

    public void onPublication(Subscription sub, PublicationEvent event) {

    };

    public void onJoin(Subscription sub, JoinEvent event) {

    };

    public void onLeave(Subscription sub, LeaveEvent event) {

    };

    public void onSubscribe(Subscription sub, SubscribeEvent event) {

    };

    public void onUnsubscribe(Subscription sub, UnsubscribeEvent event) {

    };

    public void onFail(Subscription sub, SubscriptionFailEvent event) {

    };

    public void onError(Subscription sub, SubscriptionErrorEvent event) {

    };
}
