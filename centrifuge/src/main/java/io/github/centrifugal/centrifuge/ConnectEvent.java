package io.github.centrifugal.centrifuge;

public class ConnectEvent {
    public String getClient() {
        return client;
    }

    void setClient(String client) {
        this.client = client;
    }

    public byte[] getData() {
        return data;
    }

    void setData(byte[] data) {
        this.data = data;
    }

    private String client;
    private byte[] data;
}
