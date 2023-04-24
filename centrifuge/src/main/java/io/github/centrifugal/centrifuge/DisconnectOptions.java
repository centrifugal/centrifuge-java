package io.github.centrifugal.centrifuge;

public class DisconnectOptions {

    private final boolean resetConnectionToken;

    private DisconnectOptions(Builder builder) {
        this.resetConnectionToken = builder.resetConnectionToken;
    }

    public boolean isResetConnectionToken() {
        return resetConnectionToken;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private boolean resetConnectionToken;

        private Builder() {
        }

        public Builder resetConnectionToken(boolean resetConnectionToken) {
            this.resetConnectionToken = resetConnectionToken;
            return this;
        }

        public DisconnectOptions build() {
            return new DisconnectOptions(this);
        }
    }
}
