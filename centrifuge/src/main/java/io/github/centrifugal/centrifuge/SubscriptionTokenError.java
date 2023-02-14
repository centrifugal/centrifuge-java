package io.github.centrifugal.centrifuge;

public class SubscriptionTokenError extends Exception {
    SubscriptionTokenError(Exception error) {
        super(error);
    }
}
