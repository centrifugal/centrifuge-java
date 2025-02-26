package io.github.centrifugal.centrifuge;

public interface DataCallback {
    void Done(Throwable e, byte[] data);
}