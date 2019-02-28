package io.github.centrifugal.centrifuge;

public class PublishEvent {
    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    private byte[] data;
}
