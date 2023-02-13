package io.github.centrifugal.centrifuge;

public class ConfigurationError extends Throwable {
    private final Throwable error;

    ConfigurationError(Throwable error) {
        this.error = error;
        this.initCause(error);
    }

    public Throwable getError() {
        return error;
    }
}
