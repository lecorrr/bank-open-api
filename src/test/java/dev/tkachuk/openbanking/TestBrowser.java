package dev.tkachuk.openbanking;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A customer's browser on the bank's pages, which need no JavaScript. It keeps cookies by name and follows no
 * redirects, so a test sees every step, and it signs in the way Keycloak's login ends: the browser comes back to
 * the bank with a code, which the bank redeems at KeycloakStub for the customer's ID token.
 */
public final class TestBrowser {

    public static final String CUSTOMER = "C1001";

    private static final Pattern CSRF_FIELD = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private final String baseUrl;

    public TestBrowser(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    /** Opens a page for a signed-in customer: the bank sends the browser to sign in, then back to the page. */
    public HttpResponse<String> openSignedIn(String path, WireMockServer keycloak, String customerId) {
        HttpResponse<String> toSignIn = get(path);
        assertThat(toSignIn.statusCode()).as("the bank asks the customer to sign in").isEqualTo(302);
        HttpResponse<String> toKeycloak = get(location(toSignIn));
        Map<String, String> authorization = parameters(URI.create(location(toKeycloak)).getRawQuery());
        KeycloakStub.signsIn(keycloak, TestTokens.idTokenClaims(customerId, authorization.get("nonce")));
        HttpResponse<String> back = get("/login/oauth2/code/keycloak?code=" + KeycloakStub.CODE + "&state="
                + URLEncoder.encode(authorization.get("state"), StandardCharsets.UTF_8));
        assertThat(location(back)).as("where the sign-in ends").doesNotContain("sign-in-failed");
        return get(location(back));
    }

    public HttpResponse<String> get(String pathOrUrl) {
        return send(HttpRequest.newBuilder(uri(pathOrUrl)).GET());
    }

    /** Submits a form of the page with the CSRF token the page carries, as a browser would. */
    public HttpResponse<String> submit(String path, HttpResponse<String> page, Map<String, String> fields) {
        Map<String, String> form = new LinkedHashMap<>(fields);
        form.put("_csrf", csrfToken(page.body()));
        return post(path, form);
    }

    public HttpResponse<String> post(String path, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(field.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    public static String location(HttpResponse<?> response) {
        return response.headers().firstValue("Location")
                .orElseThrow(() -> new AssertionError("no Location header in a " + response.statusCode()));
    }

    /** The parameters of a query string or of a form body, decoded. */
    public static Map<String, String> parameters(String encoded) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String value = equals < 0 ? "" : pair.substring(equals + 1);
            parameters.put(URLDecoder.decode(name, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private static String csrfToken(String page) {
        Matcher field = CSRF_FIELD.matcher(page);
        assertThat(field.find()).as("the page's form carries a CSRF token").isTrue();
        return field.group(1);
    }

    private URI uri(String pathOrUrl) {
        return URI.create(pathOrUrl.startsWith("http") ? pathOrUrl : baseUrl + pathOrUrl);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        if (!cookies.isEmpty()) {
            request.header("Cookie", cookies.entrySet().stream()
                    .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                    .collect(Collectors.joining("; ")));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().allValues("Set-Cookie").forEach(this::keep);
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the bank", e);
        }
    }

    // Only name and value matter here; a cookie the bank expires is dropped.
    private void keep(String setCookie) {
        String pair = setCookie.split(";", 2)[0];
        int equals = pair.indexOf('=');
        String name = pair.substring(0, equals).trim();
        String value = pair.substring(equals + 1).trim();
        if (value.isEmpty() || setCookie.toLowerCase(Locale.ROOT).contains("max-age=0")) {
            cookies.remove(name);
        } else {
            cookies.put(name, value);
        }
    }
}
