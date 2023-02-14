package io.github.centrifugal.centrifuge;

public class SubscriptionRefreshError extends Exception {
    SubscriptionRefreshError(Exception error) {
        super(error);
    }
}
