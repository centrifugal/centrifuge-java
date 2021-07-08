package io.github.centrifugal.centrifuge;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

public class StreamPosition {
    private long offset;

    private String epoch;

    public StreamPosition() {}

    public StreamPosition(long offset, String epoch) {
        this.offset = offset;
        this.epoch = epoch;
    }

    long getOffset() {
        return offset;
    }

    void setOffset(long offset) {
        this.offset = offset;
    }

    String getEpoch() {
        return epoch;
    }

    void setEpoch(String epoch) {
        this.epoch = epoch;
    }

    Protocol.StreamPosition toProto () {
        return Protocol.StreamPosition.newBuilder().setEpoch(this.epoch).setOffset(this.offset).build();
    }
}
