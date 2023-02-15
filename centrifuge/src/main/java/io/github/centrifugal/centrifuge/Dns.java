package io.github.centrifugal.centrifuge;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import javax.annotation.Nonnull;

/**
 * Represents DNS client used to resolve addresses of a host.
 */
public interface Dns {

    @Nonnull
    List<InetAddress> resolve(String host) throws UnknownHostException;

}
