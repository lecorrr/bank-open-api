package dev.tkachuk.openbanking.bankcore;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.tkachuk.openbanking.common.web.RequestIdFilter;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** bank-core's REST API. The RestClient comes from Spring Boot's builder, so every call carries the trace. */
@Component
public class BankCoreClient {

    // bank-core logs each request under this header's value, so one id finds the lines in both services.
    static final String REQUEST_ID_HEADER = "X-Request-Id";
    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final RestClient restClient;

    public BankCoreClient(RestClient.Builder builder, BankCoreProperties properties) {
        this.restClient = builder
                .baseUrl(properties.url())
                .requestInterceptor(BankCoreClient::addRequestId)
                .build();
    }

    /** The account with this IBAN, or empty when bank-core has none. */
    public Optional<BankCoreAccount> findAccount(String iban) {
        try {
            return Optional.ofNullable(
                    restClient.get().uri("/api/v1/accounts/{iban}", iban).retrieve().body(BankCoreAccount.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientResponseException e) {
            throw new BankCoreUnavailableException("bank-core answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /**
     * Makes the transfer, or returns the one made earlier under the same key: bank-core keeps one transfer per
     * Idempotency-Key and answers a repeat with it. TransferRefusedException when bank-core refused the transfer;
     * BankCoreUnavailableException when its outcome is unknown, and the same call may be made again.
     */
    public BankCoreTransfer transfer(TransferOrder order, String idempotencyKey) {
        try {
            return requireTransfer(restClient.post()
                    .uri("/api/v1/transfers")
                    .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                    .body(order)
                    .retrieve()
                    .body(BankCoreTransfer.class));
        } catch (RestClientResponseException e) {
            throw TransferFailures.of(e.getStatusCode().value(), codeOf(e), e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    /** The transfer bank-core keeps under this id, to learn what another bank answered. */
    public BankCoreTransfer findTransfer(UUID transferId) {
        try {
            return requireTransfer(restClient.get()
                    .uri("/api/v1/transfers/{transferId}", transferId)
                    .retrieve()
                    .body(BankCoreTransfer.class));
        } catch (RestClientResponseException e) {
            throw new BankCoreUnavailableException("bank-core answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw unreachable(e);
        }
    }

    // bank-core answers errors as problem details with its own code; a proxy in between may answer anything.
    private static @Nullable String codeOf(RestClientResponseException e) {
        try {
            BankCoreProblem problem = e.getResponseBodyAs(BankCoreProblem.class);
            return problem == null ? null : problem.code();
        } catch (RestClientException | HttpMessageConversionException notAProblem) {
            return null;
        }
    }

    private static BankCoreTransfer requireTransfer(@Nullable BankCoreTransfer transfer) {
        if (transfer == null) {
            throw new BankCoreUnavailableException("bank-core answered without a transfer", null);
        }
        return transfer;
    }

    private static BankCoreUnavailableException unreachable(RestClientException e) {
        return new BankCoreUnavailableException(
                "bank-core could not be reached (" + e.getClass().getSimpleName() + ")", e);
    }

    private static ClientHttpResponse addRequestId(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            request.getHeaders().set(REQUEST_ID_HEADER, requestId);
        }
        return execution.execute(request, body);
    }

    /** bank-core's problem details, of which only the code matters here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record BankCoreProblem(@Nullable String code) {
    }
}
