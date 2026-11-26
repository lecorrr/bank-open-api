package dev.tkachuk.provider.bank;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;

/**
 * The provider's connections to the bank, as curl's --connect-to makes them: a connection to one of the bank's
 * addresses (host and port) goes to the edge's host and port instead. Only where the bytes go changes: HttpClient
 * still asks for the bank's name in the TLS handshake, checks the bank's certificate against it and sends it as the
 * Host header, which Traefik routes by. Every other name is looked up as usual.
 */
final class BankEdgeResolver implements DnsResolver {

    private final Set<String> bankAddresses;
    private final String edgeHost;
    private final int edgePort;
    private final DnsResolver system;

    /**
     * @param bankAddresses the bank's addresses, such as https://localhost:8443
     * @param connectTo the edge, as host:port
     */
    BankEdgeResolver(List<URI> bankAddresses, String connectTo) {
        this(bankAddresses, connectTo, SystemDefaultDnsResolver.INSTANCE);
    }

    BankEdgeResolver(List<URI> bankAddresses, String connectTo, DnsResolver system) {
        this.bankAddresses = bankAddresses.stream().map(BankEdgeResolver::address)
                .collect(Collectors.toUnmodifiableSet());
        int colon = connectTo.lastIndexOf(':');
        if (colon <= 0 || colon == connectTo.length() - 1) {
            throw new IllegalArgumentException("connect-to must be host:port, got " + connectTo);
        }
        this.edgeHost = connectTo.substring(0, colon);
        this.edgePort = Integer.parseInt(connectTo.substring(colon + 1));
        if (edgePort < 1 || edgePort > 65535) {
            throw new IllegalArgumentException("connect-to has no port in 1..65535: " + connectTo);
        }
        this.system = system;
    }

    @Override
    public List<InetSocketAddress> resolve(String host, int port) throws UnknownHostException {
        if (!bankAddresses.contains(address(host, port))) {
            return system.resolve(host, port);
        }
        return Arrays.stream(system.resolve(edgeHost)).map(ip -> new InetSocketAddress(ip, edgePort)).toList();
    }

    // HttpClient asks with the port; a lookup without one is never for the edge.
    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        return system.resolve(host);
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        return system.resolveCanonicalHostname(host);
    }

    private static String address(URI url) {
        if (url.getHost() == null || !"https".equals(url.getScheme())) {
            throw new IllegalArgumentException("not an https address of the bank: " + url);
        }
        return address(url.getHost(), url.getPort() == -1 ? 443 : url.getPort());
    }

    private static String address(String host, int port) {
        return host.toLowerCase(Locale.ROOT) + ":" + port;
    }
}
