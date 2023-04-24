package io.github.centrifugal.centrifuge;

public class UnauthorizedException extends Exception {
    public UnauthorizedException() {
        super("unauthorized");
    }
}
