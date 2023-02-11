package io.github.centrifugal.centrifuge;

import javax.annotation.Nullable;

public interface ResultCallback<T> {
    void onDone(@Nullable Throwable e, @Nullable T result);
}
