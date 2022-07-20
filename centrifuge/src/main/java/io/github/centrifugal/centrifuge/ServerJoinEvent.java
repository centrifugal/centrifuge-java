package io.github.centrifugal.centrifuge;

public class ServerJoinEvent {
    public String getChannel() {
        return channel;
    }

    private final String channel;

    public ClientInfo getInfo() {
        return info;
    }

    private final ClientInfo info;

    ServerJoinEvent(String channel, ClientInfo info) {
        this.channel = channel;
        this.info = info;
    }
}
