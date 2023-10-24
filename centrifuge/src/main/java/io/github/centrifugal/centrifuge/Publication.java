package io.github.centrifugal.centrifuge;

public class Publication {
    public byte[] getData() {
        return data;
    }

    void setData(byte[] data) {
        this.data = data;
    }

    private byte[] data;

    public long getOffset() {
        return offset;
    }

    void setOffset(long offset) {
        this.offset = offset;
    }

    private long offset;

    private ClientInfo info;

    public ClientInfo getInfo() {
        return info;
    }

    void setInfo(ClientInfo info) {
        this.info = info;
    }
}
