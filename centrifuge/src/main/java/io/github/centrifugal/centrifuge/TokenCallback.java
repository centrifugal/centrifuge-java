package io.github.centrifugal.centrifuge;

public interface TokenCallback {
    /* Call this with exception or token. If called with null error and empty token then client considers access unauthorized */
    void Done(Throwable e, String token);
}
