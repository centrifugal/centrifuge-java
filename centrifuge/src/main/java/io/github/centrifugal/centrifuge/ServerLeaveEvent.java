package io.github.centrifugal.centrifuge;

public class ServerLeaveEvent {
    public String getChannel() {
        return channel;
    }

    private String channel;

    public ClientInfo getInfo() {
        return info;
    }

    private ClientInfo info;

    ServerLeaveEvent(String channel, ClientInfo info) {
        this.channel = channel;
        this.info = info;
    }
}
