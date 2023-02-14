package io.github.centrifugal.centrifuge;

public class SubscriptionErrorEvent extends Exception {
    SubscriptionErrorEvent(Exception t) {
        super(t);
    }
}
