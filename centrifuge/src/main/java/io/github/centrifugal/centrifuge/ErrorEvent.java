package io.github.centrifugal.centrifuge;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import okhttp3.Response;

public class ErrorEvent {
    private String mMessage;
    private Throwable mException;
    private Response mResponse;

    ErrorEvent(@Nonnull Throwable t, @Nullable String message, @Nullable Response response) {
        mException = t;
        mMessage = message;
        mResponse = response;
    }

    @Nullable
    public Response getResponse() {
        return mResponse;
    }

    @Nullable
    public String getMessage() {
        return mMessage;
    }

    @Nonnull
    public Throwable getException() {
        return mException;
    }
}
