package io.github.centrifugal.centrifuge;

public class HistoryOptions {
    private final int limit;
    private final StreamPosition since;
    private final boolean reverse;

    private HistoryOptions(Builder builder) {
        this.limit = builder.limit;
        this.since = builder.since;
        this.reverse = builder.reverse;
    }

    public int getLimit() {
        return limit;
    }

    public StreamPosition getSince() {
        return since;
    }

    public boolean getReverse() {
        return reverse;
    }

    @Override
    public String toString() {
        return "HistoryOptions: "+this.limit+", "+this.since.toString() +", reverse "+this.reverse;
    }

    public static class Builder
    {
        private int limit = 0;
        private StreamPosition since = null;
        private boolean reverse = false;

        public Builder withLimit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder withSince(StreamPosition since) {
            this.since = since;
            return this;
        }

        public Builder withReverse(boolean reverse) {
            this.reverse = reverse;
            return this;
        }

        public HistoryOptions build() {
            return new HistoryOptions(this);
        }
    }
}
