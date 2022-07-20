package io.github.centrifugal.centrifuge;

import io.github.centrifugal.centrifuge.internal.protocol.Protocol;

public class ClientInfo {
    private String user;

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getClient() {
        return client;
    }

    public void setClient(String client) {
        this.client = client;
    }

    private String client;

    public byte[] getConnInfo() {
        return connInfo;
    }

    public void setConnInfo(byte[] connInfo) {
        this.connInfo = connInfo;
    }

    public byte[] getChanInfo() {
        return chanInfo;
    }

    public void setChanInfo(byte[] chanInfo) {
        this.chanInfo = chanInfo;
    }

    private byte[] connInfo;
    private byte[] chanInfo;

    /**
     * Parse ClientInfo from Protocol.ClientInfo. Can return null if the Protocol.ClientInfo is null
     *
     * @param protoClientInfo which is the ClientInfo from the protocol object
     * @return ClientInfo that can be null if the Protocol.ClientInfo passed is null
     */
    static ClientInfo fromProtocolClientInfo(Protocol.ClientInfo protoClientInfo) {
        if (protoClientInfo != null) {
            ClientInfo clientInfo = new ClientInfo();
            clientInfo.setUser(protoClientInfo.getUser());
            clientInfo.setClient(protoClientInfo.getClient());
            clientInfo.setConnInfo(protoClientInfo.getConnInfo().toByteArray());
            clientInfo.setChanInfo(protoClientInfo.getChanInfo().toByteArray());
            return clientInfo;
        } else {
            return null;
        }
    }
}
