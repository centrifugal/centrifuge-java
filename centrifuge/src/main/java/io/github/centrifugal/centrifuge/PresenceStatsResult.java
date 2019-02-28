package io.github.centrifugal.centrifuge;

public class PresenceStatsResult {
    private Integer numClients;

    public Integer getNumClients() {
        return numClients;
    }

    void setNumClients(Integer numClients) {
        this.numClients = numClients;
    }

    public Integer getNumUsers() {
        return numUsers;
    }

    void setNumUsers(Integer numUsers) {
        this.numUsers = numUsers;
    }

    private Integer numUsers;
}
