package io.github.centrifugal.centrifuge;

public class LeaveEvent {
    public ClientInfo getInfo() {
        return info;
    }

    void setInfo(ClientInfo info) {
        this.info = info;
    }

    private ClientInfo info;
}
