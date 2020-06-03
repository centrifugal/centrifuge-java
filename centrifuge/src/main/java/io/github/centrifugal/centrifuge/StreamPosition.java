package io.github.centrifugal.centrifuge;

class StreamPosition {
    private long Offset;

    private String Epoch;

    private int Seq;
    private int Gen;

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

    public int getSeq() {
        return Seq;
    }

    public void setSeq(int seq) {
        Seq = seq;
    }

    public int getGen() {
        return Gen;
    }

    public void setGen(int gen) {
        Gen = gen;
    }
}
