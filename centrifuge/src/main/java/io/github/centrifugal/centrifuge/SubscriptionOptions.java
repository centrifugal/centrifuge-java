package io.github.centrifugal.centrifuge;

/**
 * Configuration for a {@link Subscription} instance.
 */
public class SubscriptionOptions {
    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    /* Connection token. This is a token you have to receive from your application backend. */
    private String token = "";

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    /* Connect data to send to a server inside Connect command. */
    private byte[] data;

    public int getMinResubscribeDelay() {
        return minResubscribeDelay;
    }

    public void setMinResubscribeDelay(int minResubscribeDelay) {
        this.minResubscribeDelay = minResubscribeDelay;
    }

    private int minResubscribeDelay = 500;

    public int getMaxResubscribeDelay() {
        return maxResubscribeDelay;
    }

    public void setMaxResubscribeDelay(int maxResubscribeDelay) {
        this.maxResubscribeDelay = maxResubscribeDelay;
    }

    private int maxResubscribeDelay = 20000;
}
