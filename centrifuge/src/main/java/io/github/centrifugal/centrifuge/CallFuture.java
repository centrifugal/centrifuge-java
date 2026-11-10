package io.github.centrifugal.centrifuge;

/**
 * The outcome of one call: completed once, with a result or with an error. A single
 * listener is notified on the thread that completes the future, or at once if the
 * future is already done.
 */
final class CallFuture<T> {

    interface Consumer<T> {
        void accept(T value);
    }

    interface Listener<T> {
        void onDone(T result, Throwable error);
    }

    private boolean done;
    private T result;
    private Throwable error;
    private Listener<T> listener;
    private boolean listenerSet;

    boolean complete(T result) {
        return this.settle(result, null);
    }

    boolean completeExceptionally(Throwable error) {
        if (error == null) {
            throw new NullPointerException();
        }
        return this.settle(null, error);
    }

    synchronized boolean isDone() {
        return this.done;
    }

    void whenComplete(Listener<T> listener) {
        synchronized (this) {
            if (this.listenerSet) {
                throw new IllegalStateException("listener already set");
            }
            this.listenerSet = true;
            if (!this.done) {
                this.listener = listener;
                return;
            }
        }
        listener.onDone(this.result, this.error);
    }

    private boolean settle(T result, Throwable error) {
        Listener<T> l;
        synchronized (this) {
            if (this.done) {
                return false;
            }
            this.done = true;
            this.result = result;
            this.error = error;
            l = this.listener;
            this.listener = null;
        }
        if (l != null) {
            l.onDone(result, error);
        }
        return true;
    }
}
