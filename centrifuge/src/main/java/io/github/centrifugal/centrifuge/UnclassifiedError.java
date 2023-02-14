package io.github.centrifugal.centrifuge;

public class UnclassifiedError extends Exception {
    UnclassifiedError(Exception error) {
        super(error);
    }
}
