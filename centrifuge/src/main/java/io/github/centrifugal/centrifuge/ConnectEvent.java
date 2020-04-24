package io.github.centrifugal.centrifuge;

public class ConnectEvent {
    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        this.client = client;
    }

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    private String client;
    private byte[] data;
}
