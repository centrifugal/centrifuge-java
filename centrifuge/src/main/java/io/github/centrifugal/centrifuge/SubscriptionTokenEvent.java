package io.github.centrifugal.centrifuge;

public class SubscriptionTokenEvent {
    final private String channel;

    public SubscriptionTokenEvent(String channel) {
        this.channel = channel;
    }

    public String getChannel() {
        return channel;
    }
}
