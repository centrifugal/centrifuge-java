package io.github.centrifugal.centrifuge;

import java.util.Map;

public class PresenceResult {
    public PresenceResult(Map<String, ClientInfo> clients) {
        this.clients = clients;
    }

    public Map<String, ClientInfo> getClients() {
        return clients;
    }

    private final Map<String, ClientInfo> clients;
}
