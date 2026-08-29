package io.github.centrifugal.centrifuge;

import org.junit.Test;

import static org.junit.Assert.*;

public class HistoryOptionsTest {

    @Test
    public void testToStringWithoutSince() {
        // withSince() is optional; most callers only set limit/reverse, leaving
        // since null. toString() must not throw a NullPointerException in that case.
        HistoryOptions opts = new HistoryOptions.Builder().withLimit(10).build();
        assertEquals("HistoryOptions: 10, null, reverse false", opts.toString());
    }

    @Test
    public void testToStringWithSince() {
        StreamPosition since = new StreamPosition(5, "epoch1");
        HistoryOptions opts = new HistoryOptions.Builder()
                .withLimit(10)
                .withSince(since)
                .withReverse(true)
                .build();
        assertEquals("HistoryOptions: 10, " + since + ", reverse true", opts.toString());
    }
}
