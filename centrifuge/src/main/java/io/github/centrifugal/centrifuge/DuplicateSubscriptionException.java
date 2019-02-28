package io.github.centrifugal.centrifuge;

/**
 * Used to indicate an attempt to subscribe to channel already used in
 * another subscription.
 *
 */
public class DuplicateSubscriptionException extends RuntimeException {

    public DuplicateSubscriptionException() {
        super();
    }

    public DuplicateSubscriptionException(final String msg) {
        super(msg);
    }

    public DuplicateSubscriptionException(final Exception cause) {
        super(cause);
    }

    public DuplicateSubscriptionException(final String msg, final Exception cause) {
        super(msg, cause);
    }
}
