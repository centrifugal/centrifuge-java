package io.github.centrifugal.centrifuge;

public class SubscriptionGetStateEvent {
    final private String channel;

    public SubscriptionGetStateEvent(String channel) {
        this.channel = channel;
    }

    public String getChannel() {
        return channel;
    }
}
