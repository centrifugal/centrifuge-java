package io.github.centrifugal.centrifuge;

public interface ResultCallback<T> {
    void onDone(Throwable e, T result);
}
