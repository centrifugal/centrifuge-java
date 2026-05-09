package io.github.centrifugal.centrifuge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helpers shared by integration tests. Synchronizes the SDK's callback API
 * with synchronous test flow via latches.
 */
abstract class IntegrationTestBase {

    static final long DEFAULT_TIMEOUT_MS = 5_000L;

    static String endpoint() {
        return System.getProperty("centrifuge.endpoint",
                "ws://localhost:8000/connection/websocket");
    }

    static String tokenSecret() {
        return System.getProperty("centrifuge.tokenSecret", "secret");
    }

    /**
     * Waits up to {@code timeoutMs} for {@code latch} to count down, throws a
     * descriptive TimeoutException tagged with {@code label} otherwise.
     */
    static void awaitLatch(CountDownLatch latch, String label, long timeoutMs)
            throws InterruptedException, TimeoutException {
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Timed out after " + timeoutMs + "ms waiting for: " + label);
        }
    }

    static void awaitLatch(CountDownLatch latch, String label)
            throws InterruptedException, TimeoutException {
        awaitLatch(latch, label, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Single-shot value holder set from a callback, retrieved after a latch.
     * Avoids the verbose AtomicReference + CountDownLatch pair in every test.
     */
    static final class Captured<T> {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<T> ref = new AtomicReference<>();

        void set(T value) {
            ref.set(value);
            latch.countDown();
        }

        T await(String label, long timeoutMs)
                throws InterruptedException, TimeoutException {
            awaitLatch(latch, label, timeoutMs);
            return ref.get();
        }

        T await(String label) throws InterruptedException, TimeoutException {
            return await(label, DEFAULT_TIMEOUT_MS);
        }

        boolean isSet() {
            return latch.getCount() == 0;
        }
    }

    static Client newClient(EventListener listener) {
        return new Client(endpoint(), new Options(), listener);
    }

    static Client newClientWithToken(String user, EventListener listener) {
        Options opts = new Options();
        opts.setToken(JwtUtil.connectionToken(user, tokenSecret()));
        return new Client(endpoint(), opts, listener);
    }
}
