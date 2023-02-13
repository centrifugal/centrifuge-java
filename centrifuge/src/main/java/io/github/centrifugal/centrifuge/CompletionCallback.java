package io.github.centrifugal.centrifuge;

import javax.annotation.Nullable;

public interface CompletionCallback {
    /**
     * Called when operation done. Caller must check possible error (it's null in case of success).
     */
    void onDone(@Nullable Throwable e);
}
