package io.github.centrifugal.centrifuge;

public class ErrorEvent extends Throwable {
    ErrorEvent(Throwable t) {
        super(t);
    }
}
