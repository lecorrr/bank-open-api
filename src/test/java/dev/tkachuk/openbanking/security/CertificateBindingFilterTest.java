package dev.tkachuk.openbanking.security;

import static dev.tkachuk.openbanking.TestProviders.PROVIDER_A;
import static dev.tkachuk.openbanking.TestProviders.PROVIDER_B;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tkachuk.openbanking.TestProviders;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.ModelAndView;

class CertificateBindingFilterTest {

    // The test certificates are valid from 2025 to 2125.
    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");

    private final List<Exception> answered = new ArrayList<>();
    private final SecurityRefusals refusals = new SecurityRefusals((request, response, handler, exception) -> {
        answered.add(exception);
        return new ModelAndView();
    }, "https://bank.test/.well-known/oauth-protected-resource");
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v2/accounts");
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final MockFilterChain chain = new MockFilterChain();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldLetThroughTokenWithTheCertificateItIsBoundTo() throws Exception {
        signIn(tokenBoundTo(PROVIDER_A));
        request.addHeader(ClientCertificates.HEADER, TestProviders.certificateHeader(PROVIDER_A));

        filterAt(NOW);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(answered).isEmpty();
        X509Certificate[] certificates =
                (X509Certificate[]) request.getAttribute(CertificateBindingFilter.CERTIFICATE_ATTRIBUTE);
        assertThat(certificates).hasSize(1);
    }

    @Test
    void shouldLeaveRequestWithoutProviderTokenToTheAuthorizationRules() throws Exception {
        filterAt(NOW);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(answered).isEmpty();
    }

    @Test
    void shouldRefuseTokenWithoutCertificate() throws Exception {
        signIn(tokenBoundTo(PROVIDER_A));

        filterAt(NOW);

        assertRefusedWith(ErrorCode.CERTIFICATE_MISSING);
    }

    @Test
    void shouldRefuseCertificateThatCannotBeRead() throws Exception {
        signIn(tokenBoundTo(PROVIDER_A));
        request.addHeader(ClientCertificates.HEADER, "bm90IGEgY2VydGlmaWNhdGU=");

        filterAt(NOW);

        assertRefusedWith(ErrorCode.CERTIFICATE_INVALID);
    }

    @Test
    void shouldRefuseExpiredCertificate() throws Exception {
        signIn(tokenBoundTo(PROVIDER_A));
        request.addHeader(ClientCertificates.HEADER, TestProviders.certificateHeader(PROVIDER_A));

        filterAt(Instant.parse("2125-06-01T00:00:00Z"));

        assertRefusedWith(ErrorCode.CERTIFICATE_EXPIRED);
    }

    @Test
    void shouldRefuseCertificateNotValidYet() throws Exception {
        signIn(tokenBoundTo(PROVIDER_A));
        request.addHeader(ClientCertificates.HEADER, TestProviders.certificateHeader(PROVIDER_A));

        filterAt(Instant.parse("2024-06-01T00:00:00Z"));

        assertRefusedWith(ErrorCode.CERTIFICATE_INVALID);
    }

    // A stolen token is of no use with the thief's own certificate.
    @Test
    void shouldRefuseTokenBoundToAnotherCertificate() throws Exception {
        signIn(tokenBoundTo(PROVIDER_A));
        request.addHeader(ClientCertificates.HEADER, TestProviders.certificateHeader(PROVIDER_B));

        filterAt(NOW);

        assertRefusedWith(ErrorCode.TOKEN_INVALID);
        assertThat(((ApiException) answered.get(0)).providerDetail())
                .contains("The access token is bound to another certificate");
    }

    private void filterAt(Instant now) throws Exception {
        new CertificateBindingFilter(refusals, Clock.fixed(now, ZoneOffset.UTC)).doFilter(request, response, chain);
    }

    private void assertRefusedWith(ErrorCode code) {
        assertThat(answered).hasSize(1);
        assertThat(((ApiException) answered.get(0)).code()).isEqualTo(code);
        assertThat(chain.getRequest()).as("the request went on").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static void signIn(Jwt token) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    private static Jwt tokenBoundTo(String provider) {
        return Jwt.withTokenValue("token")
                .header("alg", "PS256")
                .claim("azp", provider)
                .claim("cnf", Map.of("x5t#S256", TestProviders.thumbprint(provider)))
                .build();
    }
}
