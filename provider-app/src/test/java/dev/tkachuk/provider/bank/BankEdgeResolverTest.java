package dev.tkachuk.provider.bank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BankEdgeResolverTest {

    private static final List<URI> BANK = List.of(URI.create("https://localhost:8443/realms/bank"),
            URI.create("https://api.localhost:8443"));

    // Names resolve to fixed addresses, so the test needs no DNS: the edge is 10.43.0.10.
    private static final DnsResolver NAMES = new DnsResolver() {
        @Override
        public InetAddress[] resolve(String host) throws UnknownHostException {
            return switch (host) {
                case "traefik.kube-system.svc.cluster.local" -> new InetAddress[] {address(10, 43, 0, 10)};
                case "trace-collector.logging.svc.cluster.local" -> new InetAddress[] {address(10, 43, 0, 20)};
                case "localhost", "api.localhost" -> new InetAddress[] {address(127, 0, 0, 1)};
                default -> throw new UnknownHostException(host);
            };
        }

        @Override
        public String resolveCanonicalHostname(String host) {
            return host;
        }
    };

    private final BankEdgeResolver resolver =
            new BankEdgeResolver(BANK, "traefik.kube-system.svc.cluster.local:443", NAMES);

    @Test
    void shouldSendTheBanksAddressesToTheEdge() throws UnknownHostException {
        assertThat(resolver.resolve("localhost", 8443))
                .containsExactly(new InetSocketAddress(address(10, 43, 0, 10), 443));
        assertThat(resolver.resolve("API.localhost", 8443))
                .containsExactly(new InetSocketAddress(address(10, 43, 0, 10), 443));
    }

    @Test
    void shouldLeaveTheBanksNamesOnOtherPortsAndOtherNamesAlone() throws UnknownHostException {
        assertThat(resolver.resolve("localhost", 443))
                .containsExactly(new InetSocketAddress(address(127, 0, 0, 1), 443));
        assertThat(resolver.resolve("trace-collector.logging.svc.cluster.local", 4318))
                .containsExactly(new InetSocketAddress(address(10, 43, 0, 20), 4318));
        assertThat(resolver.resolve("localhost")).containsExactly(address(127, 0, 0, 1));
    }

    @Test
    void shouldTakeHttpsDefaultPortForAnAddressWithoutOne() throws UnknownHostException {
        BankEdgeResolver onDefaultPort = new BankEdgeResolver(List.of(URI.create("https://api.localhost")),
                "traefik.kube-system.svc.cluster.local:443", NAMES);

        assertThat(onDefaultPort.resolve("api.localhost", 443))
                .containsExactly(new InetSocketAddress(address(10, 43, 0, 10), 443));
    }

    @ParameterizedTest
    @ValueSource(strings = {"traefik", "traefik:", ":443", "traefik:0", "traefik:65536", "traefik:https"})
    void shouldRefuseAnEdgeThatIsNotHostAndPort(String connectTo) {
        assertThatThrownBy(() -> new BankEdgeResolver(BANK, connectTo, NAMES))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRefuseABankAddressThatIsNotHttps() {
        assertThatThrownBy(() -> new BankEdgeResolver(List.of(URI.create("http://localhost:8443")),
                "traefik:443", NAMES)).isInstanceOf(IllegalArgumentException.class);
    }

    private static InetAddress address(int a, int b, int c, int d) {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) a, (byte) b, (byte) c, (byte) d});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
