package io.github.centrifugal.centrifuge;

public class ServerLeaveEvent {
    public String getChannel() {
        return channel;
    }

    private final String channel;

    public ClientInfo getInfo() {
        return info;
    }

    private final ClientInfo info;

    ServerLeaveEvent(String channel, ClientInfo info) {
        this.channel = channel;
        this.info = info;
    }
}
