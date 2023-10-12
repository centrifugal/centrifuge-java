package io.github.centrifugal.centrifuge;

public class ErrorEvent {
    private final Throwable error;
    private final Integer httpResponseCode;

    ErrorEvent(Throwable t) {
        this(t, null);
    }

    ErrorEvent(Throwable t, Integer httpResponseCode) {
        this.error = t;
        this.httpResponseCode = httpResponseCode;
    }

    public Throwable getError() {
        return error;
    }

    /**
     * @return http response code or null if not an http error
     */
    public Integer getHttpResponseCode() {
        return httpResponseCode;
    }
}
