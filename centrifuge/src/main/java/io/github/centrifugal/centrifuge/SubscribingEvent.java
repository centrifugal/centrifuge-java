package io.github.centrifugal.centrifuge;

public class SubscribingEvent {
    public SubscribingEvent(int code, String reason) {
        this.code = code;
        this.reason = reason;
    }

    private final int code;
    private final String reason;

    public String getReason() {
        return reason;
    }

    public int getCode() {
        return code;
    }
}
