package io.github.centrifugal.centrifuge;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** A call future completes once and notifies its listener once. */
public class CallFutureTest {

    @Test
    public void testCompleteNotifiesListener() {
        CallFuture<String> f = new CallFuture<>();
        List<Object> calls = new CopyOnWriteArrayList<>();
        f.whenComplete((result, error) -> {
            calls.add(result);
            calls.add(error);
        });
        assertFalse(f.isDone());
        assertTrue(f.complete("ok"));
        assertTrue(f.isDone());
        assertEquals(2, calls.size());
        assertEquals("ok", calls.get(0));
        assertNull(calls.get(1));
    }

    @Test
    public void testCompleteExceptionallyNotifiesListenerWithSameError() {
        CallFuture<String> f = new CallFuture<>();
        Throwable error = new IOException();
        List<Object> calls = new CopyOnWriteArrayList<>();
        f.whenComplete((result, e) -> {
            calls.add(result);
            calls.add(e);
        });
        assertTrue(f.completeExceptionally(error));
        assertTrue(f.isDone());
        assertEquals(2, calls.size());
        assertNull(calls.get(0));
        assertSame(error, calls.get(1));
    }

    /** A listener added after completion is called at once, on the caller's thread. */
    @Test
    public void testListenerAddedAfterCompletionIsCalledAtOnce() {
        CallFuture<String> f = new CallFuture<>();
        Throwable error = new TimeoutException();
        f.completeExceptionally(error);
        List<Object> calls = new CopyOnWriteArrayList<>();
        Thread caller = Thread.currentThread();
        f.whenComplete((result, e) -> {
            calls.add(e);
            calls.add(Thread.currentThread());
        });
        assertEquals(2, calls.size());
        assertSame(error, calls.get(0));
        assertSame(caller, calls.get(1));
    }

    /** A listener added before completion runs on the thread that completes the future. */
    @Test
    public void testListenerRunsOnCompletingThread() throws Exception {
        CallFuture<String> f = new CallFuture<>();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        f.whenComplete((result, error) -> threads.add(Thread.currentThread()));
        Thread completer = new Thread(() -> f.complete("ok"));
        completer.start();
        completer.join(5000);
        assertEquals(1, threads.size());
        assertSame(completer, threads.get(0));
    }

    @Test
    public void testSecondCompletionIsIgnored() {
        CallFuture<String> f = new CallFuture<>();
        AtomicInteger calls = new AtomicInteger();
        List<Object> results = new CopyOnWriteArrayList<>();
        f.whenComplete((result, error) -> {
            calls.incrementAndGet();
            results.add(result);
        });
        assertTrue(f.complete("first"));
        assertFalse(f.complete("second"));
        assertFalse(f.completeExceptionally(new IOException()));
        assertEquals(1, calls.get());
        assertEquals("first", results.get(0));
    }

    /** A second listener is rejected whether the future was pending or done. */
    @Test
    public void testSecondListenerRejected() {
        CallFuture<String> pending = new CallFuture<>();
        pending.whenComplete((result, error) -> {
        });
        pending.complete("ok");
        assertRejectsListener(pending);

        CallFuture<String> done = new CallFuture<>();
        done.complete("ok");
        AtomicInteger calls = new AtomicInteger();
        done.whenComplete((result, error) -> calls.incrementAndGet());
        assertEquals(1, calls.get());
        assertRejectsListener(done);
        assertEquals(1, calls.get());
    }

    private static void assertRejectsListener(CallFuture<String> f) {
        try {
            f.whenComplete((result, error) -> {
                throw new AssertionError("second listener ran");
            });
            throw new AssertionError("second listener accepted");
        } catch (IllegalStateException expected) {
        }
    }

    /** Concurrent complete and completeExceptionally settle the future exactly once. */
    @Test
    public void testConcurrentCompletionSettlesOnce() throws Exception {
        for (int i = 0; i < 1000; i++) {
            CallFuture<String> f = new CallFuture<>();
            AtomicInteger calls = new AtomicInteger();
            f.whenComplete((result, error) -> calls.incrementAndGet());
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger wins = new AtomicInteger();
            Thread a = new Thread(() -> {
                awaitQuietly(start);
                if (f.complete("ok")) {
                    wins.incrementAndGet();
                }
            });
            Thread b = new Thread(() -> {
                awaitQuietly(start);
                if (f.completeExceptionally(new IOException())) {
                    wins.incrementAndGet();
                }
            });
            a.start();
            b.start();
            start.countDown();
            a.join(5000);
            b.join(5000);
            assertEquals(1, wins.get());
            assertEquals(1, calls.get());
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }
}
