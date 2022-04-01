package io.github.centrifugal.centrifuge;

public class ServerSubscribeEvent {
    public Boolean getRecovered() {
        return recovered;
    }

    private final Boolean recovered;

    public String getChannel() {
        return channel;
    }

    private final String channel;

    ServerSubscribeEvent(String channel, Boolean recovered) {
        this.channel = channel;
        this.recovered = recovered;
    }
}
