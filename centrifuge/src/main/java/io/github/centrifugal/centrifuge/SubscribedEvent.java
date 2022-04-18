package io.github.centrifugal.centrifuge;

public class SubscribedEvent {
    SubscribedEvent(Boolean wasRecovering, Boolean recovered, byte[] data) {
        this.wasRecovering = wasRecovering;
        this.recovered = recovered;
        this.data = data;
    }

    public Boolean getRecovered() {
        return recovered;
    }

    private final boolean recovered;

    public byte[] getData() {
        return data;
    }

    private final byte[] data;

    public Boolean wasRecovering() {
        return wasRecovering;
    }

    private final boolean wasRecovering;
}
