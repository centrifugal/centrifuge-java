package io.github.centrifugal.centrifuge;

public class ServerSubscribeEvent {
    public Boolean getRecovered() {
        return recovered;
    }

    private Boolean recovered;

    public Boolean getIsResubscribe() {
        return isResubscribe;
    }

    private Boolean isResubscribe;

    public String getChannel() {
        return channel;
    }

    private String channel;

    ServerSubscribeEvent(String channel, Boolean isResubscribe, Boolean recovered) {
        this.channel = channel;
        this.isResubscribe = isResubscribe;
        this.recovered = recovered;
    }
}
