package io.github.centrifugal.centrifuge;

class StreamPosition {
    private long Offset;

    private String Epoch;

    StreamPosition() {
    }

    public long getOffset() {
        return Offset;
    }

    public void setOffset(long offset) {
        Offset = offset;
    }

    public String getEpoch() {
        return Epoch;
    }

    public void setEpoch(String epoch) {
        Epoch = epoch;
    }

}
