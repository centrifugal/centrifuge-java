package io.github.centrifugal.centrifuge;

public class ServerSubscribedEvent {
    ServerSubscribedEvent(String channel, Boolean wasRecovering, Boolean recovered) {
        this.channel = channel;
        this.wasRecovering = wasRecovering;
        this.recovered = recovered;
    }

    public Boolean getRecovered() {
        return recovered;
    }

    private final Boolean recovered;

    public String getChannel() {
        return channel;
    }

    private final String channel;

    public Boolean wasRecovering() {
        return wasRecovering;
    }

    private final Boolean wasRecovering;
}
