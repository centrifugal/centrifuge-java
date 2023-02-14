package io.github.centrifugal.centrifuge;

public class UnclassifiedError extends Throwable {
    private final Throwable error;

    UnclassifiedError(Throwable error) {
        super(error);
        this.error = error;
    }

    public Throwable getError() {
        return error;
    }
}
