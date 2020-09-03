package io.github.centrifugal.centrifuge;

class StreamPosition {
    private long Offset;

    private String Epoch;

    StreamPosition() {
    }

    long getOffset() {
        return Offset;
    }

    void setOffset(long offset) {
        Offset = offset;
    }

    String getEpoch() {
        return Epoch;
    }

    void setEpoch(String epoch) {
        Epoch = epoch;
    }
}
