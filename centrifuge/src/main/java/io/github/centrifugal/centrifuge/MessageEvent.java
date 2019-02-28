package io.github.centrifugal.centrifuge;

public class MessageEvent {
    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    private byte[] data;
}
