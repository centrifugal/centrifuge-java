package io.github.centrifugal.centrifuge.internal.backoff;

import java.math.BigDecimal;
import java.math.BigInteger;

public class Backoff {

    private long ms;
    private long max;
    private int factor;
    private double jitter;
    private int attempts;

    private Backoff(long baseMs, int factor, double jitter, long maxMs) {
        ms = baseMs;
        max = maxMs;
        this.factor = factor;
        this.jitter = jitter;
    }

    public long duration() {
        BigInteger ms = BigInteger.valueOf(this.ms)
                .multiply(BigInteger.valueOf(this.factor).pow(this.attempts++));
        if (jitter != 0.0) {
            double rand = Math.random();
            BigInteger deviation = BigDecimal.valueOf(rand)
                    .multiply(BigDecimal.valueOf(jitter))
                    .multiply(new BigDecimal(ms)).toBigInteger();
            ms = (((int) Math.floor(rand * 10)) & 1) == 0 ? ms.subtract(deviation) : ms.add(deviation);
        }
        return ms.min(BigInteger.valueOf(this.max)).longValue();
    }

    public void reset() {
        this.attempts = 0;
    }

    public int getAttempts() {
        return this.attempts;
    }

    public static class Builder {
        private long mBaseMs = 100;
        private long mMaxMs = 20000;
        private int mFactor = 4;
        private double mJitter;

        public Builder setBaseMs(long ms) {
            mBaseMs = ms;
            return this;
        }

        public Builder setMaxMs(long max) {
            mMaxMs = max;
            return this;
        }

        public Builder setFactor(int factor) {
            mFactor = factor;
            return this;
        }

        public Builder setJitter(double jitter) {
            mJitter = jitter;
            return this;
        }

        public Backoff build() {
            return new Backoff(mBaseMs, mFactor, mJitter, mMaxMs);
        }
    }
}
