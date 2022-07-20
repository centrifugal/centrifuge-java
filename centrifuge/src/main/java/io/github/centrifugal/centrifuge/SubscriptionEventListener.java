package io.github.centrifugal.centrifuge;

public abstract class SubscriptionEventListener {

    public void onPublication(Subscription sub, PublicationEvent event) {

    };

    public void onJoin(Subscription sub, JoinEvent event) {

    };

    public void onLeave(Subscription sub, LeaveEvent event) {

    };

    public void onSubscribed(Subscription sub, SubscribedEvent event) {

    };

    public void onUnsubscribed(Subscription sub, UnsubscribedEvent event) {

    };

    public void onSubscribing(Subscription sub, SubscribingEvent event) {

    };

    public void onError(Subscription sub, SubscriptionErrorEvent event) {

    };
}
