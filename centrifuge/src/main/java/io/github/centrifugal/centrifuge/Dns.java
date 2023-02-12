package io.github.centrifugal.centrifuge;

import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Represents DNS client used to resolve addresses of a host.
 */
public interface Dns {

    @NotNull
    List<InetAddress> resolve(String host) throws UnknownHostException;

}
