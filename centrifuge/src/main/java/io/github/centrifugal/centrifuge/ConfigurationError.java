package io.github.centrifugal.centrifuge;

public class ConfigurationError extends Throwable {
    private final Throwable error;

    ConfigurationError(Throwable error) {
        super(error);
        this.error = error;
    }

    public Throwable getError() {
        return error;
    }
}
