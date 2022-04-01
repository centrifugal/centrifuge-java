package io.github.centrifugal.centrifuge;

public class SubscribeEvent {

    public Boolean getRecovered() {
        return recovered;
    }

    private final Boolean recovered;

    SubscribeEvent(Boolean recovered) {
        this.recovered = recovered;
    }
}
