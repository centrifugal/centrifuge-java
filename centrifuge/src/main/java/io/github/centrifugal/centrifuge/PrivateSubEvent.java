package io.github.centrifugal.centrifuge;

public class PrivateSubEvent {
    private String channel;

    public String getChannel() {
        return channel;
    }

    void setChannel(String channel) {
        this.channel = channel;
    }

    public String getClient() {
        return client;
    }

    void setClient(String client) {
        this.client = client;
    }

    private String client;
}
