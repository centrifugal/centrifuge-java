package io.github.centrifugal.centrifuge;

public class ServerSubscribingEvent {
    public String getChannel() {
        return channel;
    }

    private final String channel;

    ServerSubscribingEvent(String channel) {
        this.channel = channel;
    }
}
