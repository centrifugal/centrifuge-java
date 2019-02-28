package io.github.centrifugal.centrifuge;

import java.util.Map;

public class PresenceResult {
    public Map<String, ClientInfo> getPresence() {
        return presence;
    }

    public void setPresence(Map<String, ClientInfo> presence) {
        this.presence = presence;
    }

    private Map<String, ClientInfo> presence;
}
