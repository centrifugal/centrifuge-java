package io.github.centrifugal.centrifuge;

public class RefreshError extends Exception {
    RefreshError(Exception error) {
        super(error);
    }
}
