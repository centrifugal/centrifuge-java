package io.github.centrifugal.centrifuge;

import java.util.List;

public class HistoryResult {
    public List<Publication> getPublications() {
        return publications;
    }

    public void setPublications(List<Publication> publications) {
        this.publications = publications;
    }

    private List<Publication> publications;

    public long getOffset() {
        return offset;
    }

    void setOffset(long offset) {
        this.offset = offset;
    }

    public String getEpoch() {
        return epoch;
    }

    void setEpoch(String epoch) {
        this.epoch = epoch;
    }

    private long offset;
    private String epoch;
}
