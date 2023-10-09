package io.github.centrifugal.centrifuge;

import javax.annotation.Nullable;

public interface TokenCallback {
    /* Call this with exception or token. If called with null error and empty token then client considers access unauthorized */
    void Done(@Nullable Throwable e, @Nullable String token);
}
