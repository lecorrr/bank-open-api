package dev.tkachuk.provider.bank;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.tkachuk.provider.ProviderProperties;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The bank's API, as the provider uses it: consents to read an account's balances, payments from it, and their
 * status. Every call carries the provider's token and a request id of its own (X-Request-ID), which the bank logs
 * and answers with, so the provider and the bank can find the same call in their logs.
 */
@Component
public class BankApi {

    /** The bank's one payment product: credit transfers in UAH to accounts at banks in Ukraine. */
    static final String PAYMENTS = "/v2/payments/domestic-credit-transfers";
    // How long the provider asks to read, and how often a day without the customer present (the bank counts those).
    static final int CONSENT_DAYS = 90;
    static final int READS_PER_DAY = 4;

    private final RestClient api;
    private final BankTokens tokens;
    private final Clock clock;

    public BankApi(RestClient bankRestClient, BankTokens tokens, ProviderProperties properties, Clock clock) {
        this.api = bankRestClient.mutate()
                .baseUrl(properties.bank().apiUrl().toString())
                .requestInterceptor(this::authorize)
                .build();
        this.tokens = tokens;
        this.clock = clock;
    }

    /**
     * Asks the bank for a consent to read the balances of the account with this IBAN; the customer decides on the
     * bank's page, and the bank sends the browser back to redirectUri either way.
     */
    public Started startConsent(String iban, String psuIpAddress, URI redirectUri) {
        ConsentRequest request = new ConsentRequest(
                new Access(List.of(new AccountAccess(new AccountReference(iban), List.of("balances")))),
                "detailed", true, LocalDate.now(clock).plusDays(CONSENT_DAYS).toString(), READS_PER_DAY);
        ConsentResponse answer = call("creating the consent", () -> api.post().uri("/v2/consents/account-access")
                .header("PSU-IP-Address", psuIpAddress)
                .header("TPP-Redirect-URI", redirectUri.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ConsentResponse.class));
        return started(answer == null ? null : answer.consentId(), answer == null ? null : answer.links(), "consent");
    }

    /** The consent's status as the bank has it: received, valid, rejected, revokedByPsu, terminatedByTpp, expired. */
    public String consentStatus(String consentId) {
        ConsentStatus answer = call("reading the consent's status", () -> api.get()
                .uri("/v2/consents/account-access/{consentId}/status", consentId)
                .retrieve()
                .body(ConsentStatus.class));
        return require(answer == null ? null : answer.consentStatus(), "the consent's status");
    }

    /** Ends the consent at the provider's request: the bank lets nobody read under it again. */
    public void deleteConsent(String consentId) {
        call("ending the consent", () -> api.delete()
                .uri("/v2/consents/account-access/{consentId}", consentId)
                .retrieve()
                .toBodilessEntity());
    }

    /** The accounts the consent covers, each with its balances, read while the customer is on the provider's page. */
    public List<Account> accounts(String consentId, String psuIpAddress) {
        AccountList answer = call("reading the accounts", () -> api.get().uri("/v2/accounts")
                .header("Consent-ID", consentId)
                .header("PSU-IP-Address", psuIpAddress)
                .retrieve()
                .body(AccountList.class));
        List<AccountJson> accounts = answer == null || answer.accounts() == null ? List.of() : answer.accounts();
        return accounts.stream().map(account -> new Account(
                require(account.resourceId(), "an account's resourceId"), account.iban(),
                require(account.currency(), "an account's currency"),
                balances(consentId, account.resourceId(), psuIpAddress))).toList();
    }

    private List<Account.Balance> balances(String consentId, String resourceId, String psuIpAddress) {
        BalanceList answer = call("reading the balances", () -> api.get()
                .uri("/v2/accounts/{resourceId}/balances", resourceId)
                .header("Consent-ID", consentId)
                .header("PSU-IP-Address", psuIpAddress)
                .retrieve()
                .body(BalanceList.class));
        List<BalanceJson> balances = answer == null || answer.balances() == null ? List.of() : answer.balances();
        return balances.stream().map(balance -> {
            Amount amount = balance.balanceAmount();
            if (amount == null) {
                throw new BankApiException("the bank answered a balance without its amount", 200, null, null);
            }
            return new Account.Balance(require(balance.balanceType(), "a balance's type"),
                    require(amount.amount(), "a balance's amount"), require(amount.currency(), "a balance's currency"));
        }).toList();
    }

    /** Asks the bank to make the payment once the customer approves it on the bank's page. */
    public Started startPayment(PaymentOrder order, String psuIpAddress, URI redirectUri) {
        PaymentRequest request = new PaymentRequest(new AccountReference(order.debtorIban()),
                new Amount("UAH", order.amount()), new AccountReference(order.creditorIban()),
                new Creditor(order.creditorName()), order.remittance());
        PaymentResponse answer = call("starting the payment", () -> api.post().uri(PAYMENTS)
                .header("PSU-IP-Address", psuIpAddress)
                .header("TPP-Redirect-URI", redirectUri.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(PaymentResponse.class));
        return started(answer == null ? null : answer.paymentId(), answer == null ? null : answer.links(), "payment");
    }

    /** The payment's status as the bank has it, an ISO 20022 code: RCVD, ACCC, RJCT, CANC and the like. */
    public String paymentStatus(String paymentId) {
        PaymentStatus answer = call("reading the payment's status", () -> api.get()
                .uri(PAYMENTS + "/{paymentId}/status", paymentId)
                .retrieve()
                .body(PaymentStatus.class));
        return require(answer == null ? null : answer.transactionStatus(), "the payment's status");
    }

    private ClientHttpResponse authorize(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        request.getHeaders().setBearerAuth(tokens.accessToken());
        request.getHeaders().set("X-Request-ID", UUID.randomUUID().toString());
        return execution.execute(request, body);
    }

    // The bank's errors are problem details with the contract's code; anything else is reported by its status.
    private static <T> T call(String what, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            Problem problem = problemOf(e);
            throw new BankApiException("the bank refused " + what, e.getStatusCode().value(),
                    problem == null ? null : problem.code(), problem == null ? null : problem.detail());
        } catch (RestClientException e) {
            throw BankApiException.unreachable("the bank, " + what + ",", e);
        }
    }

    private static @Nullable Problem problemOf(RestClientResponseException e) {
        try {
            return e.getResponseBodyAs(Problem.class);
        } catch (RuntimeException notAProblem) {
            return null;
        }
    }

    // The customer's browser goes to the bank's page next, so it must be an https address.
    private static Started started(@Nullable String id, @Nullable Links links, String what) {
        String href = links == null || links.scaRedirect() == null ? null : links.scaRedirect().href();
        if (id == null || href == null || !href.startsWith("https://")) {
            throw new BankApiException("the bank took the " + what + " but named no https page for the customer", 201,
                    null, null);
        }
        return new Started(id, URI.create(href));
    }

    private static String require(@Nullable String value, String what) {
        if (value == null || value.isBlank()) {
            throw new BankApiException("the bank answered without " + what, 200, null, null);
        }
        return value;
    }

    record ConsentRequest(Access access, String consentType, boolean recurringIndicator, String validTo,
            int frequencyPerDay) {
    }

    record Access(List<AccountAccess> payments) {
    }

    record AccountAccess(AccountReference account, List<String> rights) {
    }

    record AccountReference(String iban) {
    }

    record PaymentRequest(AccountReference debtorAccount, Amount instructedAmount, AccountReference creditorAccount,
            Creditor creditor, String remittanceInformationUnstructured) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Amount(@Nullable String currency, @Nullable String amount) {
    }

    record Creditor(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConsentResponse(@Nullable String consentId, @Nullable String consentStatus,
            @JsonProperty("_links") @Nullable Links links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentResponse(@Nullable String paymentId, @Nullable String transactionStatus,
            @JsonProperty("_links") @Nullable Links links) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Links(@Nullable Href scaRedirect) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Href(@Nullable String href) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ConsentStatus(@Nullable String consentStatus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentStatus(@Nullable String transactionStatus) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountList(@Nullable List<AccountJson> accounts) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AccountJson(@Nullable String resourceId, @Nullable String iban, @Nullable String currency) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceList(@Nullable List<BalanceJson> balances) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record BalanceJson(@Nullable Amount balanceAmount, @Nullable String balanceType) {
    }

    /** The bank's problem details, of which the code and the detail matter here. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Problem(@Nullable String code, @Nullable String detail) {
    }
}
