package io.github.centrifugal.centrifuge;

public class FailEvent {
    private final ClientFailReason reason;

    FailEvent(ClientFailReason reason) {
        this.reason = reason;
    }

    public ClientFailReason getReason() {
        return reason;
    }
}
