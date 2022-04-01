package io.github.centrifugal.centrifuge;

/**
 * Used to indicate an attempt to subscribe to channel already used in
 * another subscription.
 *
 */
public class DuplicateSubscriptionException extends Exception {
    public DuplicateSubscriptionException() {
        super();
    }
}
