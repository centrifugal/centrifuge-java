package io.github.centrifugal.centrifuge;

public class JoinEvent {
    public ClientInfo getInfo() {
        return info;
    }

    void setInfo(ClientInfo info) {
        this.info = info;
    }

    private ClientInfo info;
}
