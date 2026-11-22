package dev.tkachuk.openbanking;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BankCoreStub.class)
class BankOpenApiIT {

    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String IBAN_A = "UA843999990000000000000000001";
    private static final String IBAN_B = "UA033999990000000000000000004";

    @LocalServerPort
    private int port;

    @Autowired
    private WireMockServer bankCore;

    private RestClient api;

    @BeforeEach
    void setUp() {
        bankCore.resetAll();
        api = RestClient.create("http://localhost:" + port);
    }

    @Test
    void shouldListBankCoreAccountsThroughTheApi() {
        bankCoreHasAccounts();

        ResponseEntity<String> response = api.get().uri("/v2/accounts").header("X-Request-ID", REQUEST_ID)
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst("X-Request-ID")).isEqualTo(REQUEST_ID);
        List<String> ibans = JsonPath.read(response.getBody(), "$.accounts[*].iban");
        assertThat(ibans).containsExactly(IBAN_A, IBAN_B);
        String currency = JsonPath.read(response.getBody(), "$.accounts[1].currency");
        assertThat(currency).isEqualTo("USD");
    }

    @Test
    void shouldContinueTheProvidersTraceAndPassItsRequestIdToBankCore() {
        bankCoreHasAccounts();

        api.get().uri("/v2/accounts").header("X-Request-ID", REQUEST_ID)
                .header("traceparent", "00-" + TRACE_ID + "-b7ad6b7169203331-01")
                .retrieve().toBodilessEntity();

        bankCore.verify(getRequestedFor(urlEqualTo("/api/v1/accounts"))
                .withHeader("X-Request-Id", equalTo(REQUEST_ID))
                .withHeader("traceparent", matching("00-" + TRACE_ID + "-[0-9a-f]{16}-01")));
    }

    @Test
    void shouldAnswer503WhenBankCoreFails() {
        bankCore.stubFor(get(urlEqualTo("/api/v1/accounts")).willReturn(serverError()));

        ResponseEntity<String> response = api.get().uri("/v2/accounts").header("X-Request-ID", REQUEST_ID)
                .retrieve().onStatus(HttpStatusCode::isError, (request, answer) -> { })
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        String code = JsonPath.read(response.getBody(), "$.code");
        assertThat(code).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    void shouldReportReadinessOnTheProbePath() {
        String body = api.get().uri("/actuator/health/readiness").retrieve().body(String.class);

        String status = JsonPath.read(body, "$.status");
        assertThat(status).isEqualTo("UP");
    }

    private void bankCoreHasAccounts() {
        bankCore.stubFor(get(urlEqualTo("/api/v1/accounts")).willReturn(okJson("""
                [{"iban": "%s", "holderName": "Test Holder", "currency": "UAH", "balance": 850.00,
                  "createdAt": "2026-11-20T08:00:00Z"},
                 {"iban": "%s", "holderName": "Test Holder", "currency": "USD", "balance": 10.00,
                  "createdAt": "2026-11-20T08:00:01Z"}]
                """.formatted(IBAN_A, IBAN_B))));
    }
}
