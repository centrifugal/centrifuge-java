package io.github.centrifugal.centrifuge;

public abstract class ConnectionTokenGetter {
    public void getConnectionToken(ConnectionTokenEvent event, TokenCallback cb) {
        cb.Done(null, "");
    };
}
