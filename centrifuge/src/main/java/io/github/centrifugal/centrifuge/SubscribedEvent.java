package io.github.centrifugal.centrifuge;

public class SubscribedEvent {
    SubscribedEvent(boolean wasRecovering, boolean recovered, byte[] data) {
        this.wasRecovering = wasRecovering;
        this.recovered = recovered;
        this.data = data;
    }

    public boolean getRecovered() {
        return recovered;
    }

    private final boolean recovered;

    public byte[] getData() {
        return data;
    }

    private final byte[] data;

    public boolean wasRecovering() {
        return wasRecovering;
    }

    private final boolean wasRecovering;
}
