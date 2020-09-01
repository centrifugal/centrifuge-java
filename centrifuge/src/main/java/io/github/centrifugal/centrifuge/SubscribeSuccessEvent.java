package io.github.centrifugal.centrifuge;

public class SubscribeSuccessEvent {

    public Boolean getRecovered() {
        return recovered;
    }

    public void setRecovered(Boolean recovered) {
        this.recovered = recovered;
    }

    private Boolean recovered;

    public Boolean getResubscribe() {
        return isResubscribe;
    }

    public void setResubscribe(Boolean resubscribe) {
        isResubscribe = resubscribe;
    }

    private Boolean isResubscribe;

    SubscribeSuccessEvent(Boolean isResubscribe, Boolean recovered) {
        this.isResubscribe = isResubscribe;
        this.recovered = recovered;
    }
}
