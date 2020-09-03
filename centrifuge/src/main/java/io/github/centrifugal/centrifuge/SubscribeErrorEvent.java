package io.github.centrifugal.centrifuge;

public class SubscribeErrorEvent {
    private Integer code;

    public Integer getCode() {
        return code;
    }

    void setCode(Integer code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    void setMessage(String message) {
        this.message = message;
    }

    private String message;
}
