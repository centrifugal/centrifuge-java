package io.github.centrifugal.centrifuge;

public class ServerSubscribedEvent {
    ServerSubscribedEvent(String channel, Boolean wasRecovering, Boolean recovered, Boolean positioned, Boolean recoverable, StreamPosition streamPosition, byte[] data) {
        this.channel = channel;
        this.wasRecovering = wasRecovering;
        this.recovered = recovered;
        this.positioned = positioned;
        this.recoverable = recoverable;
        this.streamPosition = streamPosition;
        this.data = data;
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

    public byte[] getData() {
        return data;
    }

    private final byte[] data;

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
