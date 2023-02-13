package io.github.centrifugal.centrifuge;

import javax.annotation.Nullable;

public class RefreshError extends Throwable {
    private final Throwable error;

    RefreshError(@Nullable Throwable error) {
        this.error = error;
    }

    public @Nullable Throwable getError() {
        return error;
    }
}
