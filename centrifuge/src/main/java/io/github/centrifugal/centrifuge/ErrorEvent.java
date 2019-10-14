package io.github.centrifugal.centrifuge;

import com.sun.istack.internal.NotNull;
import com.sun.istack.internal.Nullable;

import okhttp3.Response;

public class ErrorEvent {
    private String mMessage;
    private Throwable mException;
    private Response mResponse;

    ErrorEvent(@NotNull Throwable t, @Nullable String message, @Nullable Response response) {
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

    @NotNull
    public Throwable getException() {
        return mException;
    }
}
