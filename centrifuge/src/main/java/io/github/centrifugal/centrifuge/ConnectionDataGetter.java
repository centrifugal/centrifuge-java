package io.github.centrifugal.centrifuge;

public abstract class ConnectionDataGetter {
    public void getConnectionData(ConnectionDataEvent event, DataCallback cb) {
        cb.Done(null, null);
    }
}