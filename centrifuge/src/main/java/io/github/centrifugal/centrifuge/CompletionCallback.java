package io.github.centrifugal.centrifuge;

public interface CompletionCallback {
    /**
     * Called when operation done. Caller must check possible error (it's null in case of success).
     */
    void onDone(Throwable e);
}
