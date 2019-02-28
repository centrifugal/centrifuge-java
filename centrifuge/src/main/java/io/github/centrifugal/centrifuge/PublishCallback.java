package io.github.centrifugal.centrifuge;

public interface PublishCallback {
    /**
     * Called when the request could not be executed due to cancellation, a connectivity problem or
     * timeout. Because networks can fail during an exchange, it is possible that the remote server
     * accepted the request before the failure.
     */
    void onFailure(Throwable e);

    /**
     * Called when the publish reply was successfully returned by remote server.
     * Note that it still can contain Error.
     */
    void onDone(PublishResult reply);
}
