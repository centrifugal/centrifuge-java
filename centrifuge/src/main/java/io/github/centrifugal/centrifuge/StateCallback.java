package io.github.centrifugal.centrifuge;

public interface StateCallback {
    /* Call this with exception or the stream position to recover from. If called with
     * null error and null position then SDK considers state loading failed and retries. */
    void Done(Throwable e, StreamPosition position);
}
