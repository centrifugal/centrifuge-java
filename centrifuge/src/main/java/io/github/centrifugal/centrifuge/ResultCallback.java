package io.github.centrifugal.centrifuge;

import javax.annotation.Nullable;

public interface ResultCallback<T> {
    /**
     * onDone will be called when the operation completed. Either Throwable or T will be not null.
     */    
    void onDone(@Nullable Throwable e, @Nullable T result);
}
