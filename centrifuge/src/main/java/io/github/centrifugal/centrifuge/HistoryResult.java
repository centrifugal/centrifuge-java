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
}
