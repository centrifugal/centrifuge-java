package io.github.centrifugal.centrifuge;

public class RefreshError extends Throwable {
    private final Throwable error;

    RefreshError(Throwable error) {
        this.error = error;
    }

    public Throwable getError() {
        return error;
    }
}
