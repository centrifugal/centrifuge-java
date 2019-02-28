package io.github.centrifugal.centrifuge;

public interface ReplyCallback<T> {
    /**
     * Called when the request could not be executed due to cancellation, a connectivity problem or
     * timeout. Because networks can fail during an exchange, it is possible that the remote server
     * accepted the request before the failure.
     */
    void onFailure(Throwable e);

    /**
     * Called when reply was successfully returned by server.
     * Note that reply still can contain protocol level Error.
     */
    void onDone(ReplyError error, T result);
}
