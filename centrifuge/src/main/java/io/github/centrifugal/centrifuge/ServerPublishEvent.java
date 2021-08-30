package io.github.centrifugal.centrifuge;

public class ServerPublishEvent {
    public byte[] getData() {
        return data;
    }

    void setData(byte[] data) {
        this.data = data;
    }

    private byte[] data;

    public ClientInfo getInfo() {
        return info;
    }

    void setInfo(ClientInfo info) {
        this.info = info;
    }

    private ClientInfo info;

    public long getOffset() {
        return offset;
    }

    void setOffset(long offset) {
        this.offset = offset;
    }

    private long offset;

    void setChannel(String channel) {
        this.channel = channel;
    }

    public String getChannel() {
        return channel;
    }

    private String channel;
}
