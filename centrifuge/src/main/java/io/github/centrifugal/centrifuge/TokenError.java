package io.github.centrifugal.centrifuge;

public class TokenError extends Exception {
    TokenError(Exception error) {
        super(error);
    }
}
