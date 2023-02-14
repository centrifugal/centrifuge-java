package io.github.centrifugal.centrifuge;

public interface ResultCallback<T> {
    /**
     * onDone will be called when the operation completed. Either Throwable or T will be not null.
     */    
    void onDone(Throwable e, T result);
}
