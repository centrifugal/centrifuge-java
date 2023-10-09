package io.github.centrifugal.centrifuge;

import javax.annotation.Nullable;

public class SubscribedEvent {
    SubscribedEvent(Boolean wasRecovering, Boolean recovered, Boolean positioned, Boolean recoverable, @Nullable StreamPosition streamPosition, @Nullable byte[] data) {
        this.wasRecovering = wasRecovering;
        this.recovered = recovered;
        this.data = data;
        this.positioned = positioned;
        this.recoverable = recoverable;
        this.streamPosition = streamPosition;
    }

    public Boolean getRecovered() {
        return recovered;
    }

    private final Boolean recovered;

    public byte[] getData() {
        return data;
    }

    private final byte[] data;

    public Boolean wasRecovering() {
        return wasRecovering;
    }

    private final Boolean wasRecovering;

    public Boolean getPositioned() {
        return positioned;
    }

    public Boolean getRecoverable() {
        return recoverable;
    }

    public StreamPosition getStreamPosition() {
        return streamPosition;
    }

    private final Boolean positioned;

    private final Boolean recoverable;

    private final StreamPosition streamPosition;
}
