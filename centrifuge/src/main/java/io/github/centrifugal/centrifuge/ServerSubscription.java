package io.github.centrifugal.centrifuge;

class ServerSubscription {
    private long offset;
    private String epoch;
    private boolean recoverable;

    ServerSubscription(Boolean recoverable, long offset, String epoch) {
        this.recoverable = recoverable;
        this.offset = offset;
        this.epoch = epoch;
    }

    void setLastOffset(long lastOffset) {
        this.offset = lastOffset;
    }

    void setLastEpoch(String lastEpoch) {
        this.epoch = lastEpoch;
    }
}
