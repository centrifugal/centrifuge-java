package io.github.centrifugal.centrifuge;

public class ServerUnsubscribedEvent {
    public String getChannel() {
        return channel;
    }

    private final String channel;

    ServerUnsubscribedEvent(String channel) {
        this.channel = channel;
    }
}
