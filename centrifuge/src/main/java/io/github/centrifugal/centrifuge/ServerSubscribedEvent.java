package io.github.centrifugal.centrifuge;

public class ServerSubscribedEvent {
    ServerSubscribedEvent(String channel, boolean wasRecovering, boolean recovered) {
        this.channel = channel;
        this.wasRecovering = wasRecovering;
        this.recovered = recovered;
    }

    public boolean getRecovered() {
        return recovered;
    }

    private final Boolean recovered;

    public String getChannel() {
        return channel;
    }

    private final String channel;

    public boolean wasRecovering() {
        return wasRecovering;
    }

    private final boolean wasRecovering;
}
