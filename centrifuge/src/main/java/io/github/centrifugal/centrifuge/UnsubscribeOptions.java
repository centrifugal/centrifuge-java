package io.github.centrifugal.centrifuge;

public class UnsubscribeOptions {

    private final boolean permanent;

    private UnsubscribeOptions(boolean permanent) {
        this.permanent = permanent;
    }

    boolean isPermanent() {
        return permanent;
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {

        private boolean permanent;

        public Builder setPermanent(boolean permanent) {
            this.permanent = permanent;
            return this;
        }

        public UnsubscribeOptions build() {
            return new UnsubscribeOptions(permanent);
        }

    }

}
