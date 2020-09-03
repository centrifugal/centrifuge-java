package io.github.centrifugal.centrifuge;

public class SubscribeSuccessEvent {

    public Boolean getRecovered() {
        return recovered;
    }

    private Boolean recovered;

    public Boolean getIsResubscribe() {
        return isResubscribe;
    }

    private Boolean isResubscribe;

    SubscribeSuccessEvent(Boolean isResubscribe, Boolean recovered) {
        this.isResubscribe = isResubscribe;
        this.recovered = recovered;
    }
}
