package io.github.centrifugal.centrifuge;

public class HistoryOptions {
    private final int limit;
    private final StreamPosition since;

    private HistoryOptions(Builder builder) {
        this.limit = builder.limit;
        this.since = builder.since;
    }

    public int getLimit() {
        return limit;
    }

    public StreamPosition getSince() {
        return since;
    }

    @Override
    public String toString() {
        return "HistoryOptions: "+this.limit+", "+this.since.toString();
    }

    public static class Builder
    {
        private int limit = 0;
        private StreamPosition since = null;

        public Builder withLimit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder withSince(StreamPosition since) {
            this.since = since;
            return this;
        }

        public HistoryOptions build() {
            return new HistoryOptions(this);
        }
    }
}
