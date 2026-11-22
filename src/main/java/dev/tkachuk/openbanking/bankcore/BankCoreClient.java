package dev.tkachuk.openbanking.bankcore;

import dev.tkachuk.openbanking.common.web.RequestIdFilter;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** bank-core's REST API. The RestClient comes from Spring Boot's builder, so every call carries the trace. */
@Component
public class BankCoreClient {

    // bank-core logs each request under this header's value, so one id finds the lines in both services.
    static final String REQUEST_ID_HEADER = "X-Request-Id";

    private final RestClient restClient;

    public BankCoreClient(RestClient.Builder builder, BankCoreProperties properties) {
        this.restClient = builder
                .baseUrl(properties.url())
                .requestInterceptor(BankCoreClient::addRequestId)
                .build();
    }

    public List<BankCoreAccount> listAccounts() {
        BankCoreAccount[] accounts;
        try {
            accounts = restClient.get().uri("/api/v1/accounts").retrieve().body(BankCoreAccount[].class);
        } catch (RestClientResponseException e) {
            throw new BankCoreUnavailableException("bank-core answered " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new BankCoreUnavailableException(
                    "bank-core could not be reached (" + e.getClass().getSimpleName() + ")", e);
        }
        return accounts == null ? List.of() : List.of(accounts);
    }

    private static ClientHttpResponse addRequestId(HttpRequest request, byte[] body,
            ClientHttpRequestExecution execution) throws IOException {
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId != null) {
            request.getHeaders().set(REQUEST_ID_HEADER, requestId);
        }
        return execution.execute(request, body);
    }
}
