package io.github.centrifugal.centrifuge.internal.backoff;

public class Backoff {
    public Backoff() {}

    public long duration(int step, int minDelay, int maxDelay) {
        // Full jitter technique.
        // https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
        double currentStep = step;
        if (currentStep > 31) { currentStep = 31; }
        double min = Math.min(maxDelay, minDelay * Math.pow(2, currentStep));
        int val = (int)(Math.random() * (min + 1));
        return Math.min(maxDelay, minDelay + val);
    }
}
