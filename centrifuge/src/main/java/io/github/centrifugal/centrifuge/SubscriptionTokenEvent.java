package io.github.centrifugal.centrifuge;

public class SubscriptionTokenEvent {
    private String channel;

    public SubscriptionTokenEvent(String channel) {
        this.channel = channel;
    }

    public String getChannel() {
        return channel;
    }

    void setChannel(String channel) {
        this.channel = channel;
    }
}
