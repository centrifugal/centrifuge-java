package io.github.centrifugal.centrifuge;

public class SubscriptionSubscribeError extends Exception {
    SubscriptionSubscribeError(Exception error) {
        super(error);
    }
}
