package io.github.centrifugal.centrifuge;

public class ConnectEvent {
    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        this.client = client;
    }

    private String client;
}
