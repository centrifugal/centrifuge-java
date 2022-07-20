package io.github.centrifugal.centrifuge;

public class ReplyError extends Throwable {
    private int code;

    public ReplyError(int code, String message, boolean temporary) {
        this.code = code;
        this.message = message;
        this.temporary = temporary;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    private String message;

    public boolean isTemporary() {
        return temporary;
    }

    public void setTemporary(boolean temporary) {
        this.temporary = temporary;
    }

    private boolean temporary;
}
