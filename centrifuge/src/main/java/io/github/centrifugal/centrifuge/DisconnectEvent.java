package io.github.centrifugal.centrifuge;

public class DisconnectEvent {
    void setReason(String reason) {
        this.reason = reason;
    }

    void setReconnect(Boolean reconnect) {
        this.reconnect = reconnect;
    }

    private String reason;
    private Boolean reconnect;

    public String getReason() {
        return reason;
    }

    public Boolean getReconnect() {
        return reconnect;
    }
}
