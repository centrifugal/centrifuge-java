package io.github.centrifugal.centrifuge;

public class DisconnectEvent {
    public DisconnectEvent(int code, String reason, Boolean reconnect) {
        this.code = code;
        this.reason = reason;
        this.reconnect = reconnect;
    }

    private final int code;
    private final String reason;
    private final Boolean reconnect;

    public String getReason() {
        return reason;
    }

    public Boolean getReconnect() {
        return reconnect;
    }

    public int getCode() {
        return code;
    }
}
