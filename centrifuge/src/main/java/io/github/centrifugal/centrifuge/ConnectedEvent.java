package io.github.centrifugal.centrifuge;

public class ConnectedEvent {
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

    private byte[] data;

    private String client;
}
