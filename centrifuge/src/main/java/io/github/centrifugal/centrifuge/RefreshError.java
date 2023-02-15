package io.github.centrifugal.centrifuge;

import javax.annotation.Nullable;

public class RefreshError extends Throwable {
    private final Throwable error;

    RefreshError(Throwable error) {
        super(error);
        this.error = error;
    }

    public @Nullable Throwable getError() {
        return error;
    }
}
