package io.github.centrifugal.centrifuge;

public class SubscribeSuccessEvent {

    public Boolean getRecovered() {
        return recovered;
    }

    public void setRecovered(Boolean recovered) {
        this.recovered = recovered;
    }

    private Boolean recovered;

    public Boolean getIsResubscribe() {
        return isResubscribe;
    }

    public void setIsResubscribe(Boolean isResubscribe) {
        isResubscribe = isResubscribe;
    }

    private Boolean isResubscribe;

    SubscribeSuccessEvent(Boolean isResubscribe, Boolean recovered) {
        this.isResubscribe = isResubscribe;
        this.recovered = recovered;
    }
}
