package dev.tkachuk.provider.bank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tkachuk.provider.ProviderProperties;
import java.net.URI;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The provider's access token at the bank: the client credentials grant at Keycloak's token endpoint, signed in
 * with a client assertion over mutual TLS, so the token comes bound to the provider's certificate (RFC 8705). The
 * token endpoint comes from the issuer's discovery document, read once. A token serves until shortly before it
 * expires; then the next call gets a new one.
 */
@Component
public class BankTokens {

    // A token this close to its end is not handed out: a call may take that long to reach the API.
    static final Duration RENEW_BEFORE = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final ProviderProperties properties;
    private final PrivateKey key;
    private final Clock clock;
    private @Nullable URI tokenEndpoint;
    private @Nullable Token token;

    public BankTokens(RestClient restClient, ProviderProperties properties, PrivateKey key, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.key = key;
        this.clock = clock;
    }

    /** A token for the bank's API, valid for RENEW_BEFORE at least. */
    public synchronized String accessToken() {
        Instant now = clock.instant();
        if (token == null || !now.isBefore(token.renewAt())) {
            token = requestToken(now);
        }
        return token.value();
    }

    private Token requestToken(Instant now) {
        String issuer = properties.bank().issuer().toString();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        form.add("client_assertion",
                ClientAssertion.sign(properties.clientId(), issuer, UUID.randomUUID().toString(), now, key));
        TokenResponse answer;
        try {
            answer = restClient.post().uri(tokenEndpoint()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(TokenResponse.class);
        } catch (RestClientResponseException e) {
            throw new BankApiException("the bank's sign-in refused the provider", e.getStatusCode().value(),
                    errorOf(e), null);
        } catch (RestClientException e) {
            throw BankApiException.unreachable("the bank's sign-in", e);
        }
        if (answer == null || answer.accessToken() == null || answer.accessToken().isBlank()
                || !"Bearer".equalsIgnoreCase(answer.tokenType()) || answer.expiresIn() <= RENEW_BEFORE.toSeconds()) {
            throw new BankApiException("the bank's sign-in answered without a usable Bearer token", 200, null, null);
        }
        return new Token(answer.accessToken(), now.plusSeconds(answer.expiresIn()).minus(RENEW_BEFORE));
    }

    // OpenID Connect Discovery: the document at the issuer's well-known address must name that same issuer.
    private URI tokenEndpoint() {
        if (tokenEndpoint == null) {
            String issuer = properties.bank().issuer().toString();
            Discovery discovery;
            try {
                discovery = restClient.get().uri(issuer + "/.well-known/openid-configuration").retrieve()
                        .body(Discovery.class);
            } catch (RestClientResponseException e) {
                throw new BankApiException("the bank's sign-in has no discovery document", e.getStatusCode().value(),
                        null, null);
            } catch (RestClientException e) {
                throw BankApiException.unreachable("the bank's sign-in", e);
            }
            if (discovery == null || !issuer.equals(discovery.issuer()) || discovery.tokenEndpoint() == null
                    || !discovery.tokenEndpoint().startsWith("https://")) {
                throw new BankApiException("the bank's discovery document names another issuer or no https token "
                        + "endpoint", 200, null, null);
            }
            tokenEndpoint = URI.create(discovery.tokenEndpoint());
        }
        return tokenEndpoint;
    }

    private static @Nullable String errorOf(RestClientResponseException e) {
        try {
            OAuthError error = e.getResponseBodyAs(OAuthError.class);
            return error == null ? null : error.error();
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private record Token(String value, Instant renewAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Discovery(@Nullable String issuer, @JsonProperty("token_endpoint") @Nullable String tokenEndpoint) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenResponse(@JsonProperty("access_token") @Nullable String accessToken,
            @JsonProperty("token_type") @Nullable String tokenType,
            @JsonProperty("expires_in") long expiresIn) {
    }

    /** RFC 6749's error answer: an error code such as invalid_client. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record OAuthError(@Nullable String error) {
    }
}
