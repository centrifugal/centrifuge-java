package io.github.centrifugal.centrifuge;

public class ServerUnsubscribeEvent {
    public String getChannel() {
        return channel;
    }

    private String channel;

    ServerUnsubscribeEvent(String channel) {
        this.channel = channel;
    }
}
