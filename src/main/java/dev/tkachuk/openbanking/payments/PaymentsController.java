package dev.tkachuk.openbanking.payments;

import dev.tkachuk.openbanking.api.PaymentsApi;
import dev.tkachuk.openbanking.api.model.AccountReference1;
import dev.tkachuk.openbanking.api.model.Amount;
import dev.tkachuk.openbanking.api.model.HrefType;
import dev.tkachuk.openbanking.api.model.LinksPaymentInitiation;
import dev.tkachuk.openbanking.api.model.PartyDescription1;
import dev.tkachuk.openbanking.api.model.PaymentIdentification1;
import dev.tkachuk.openbanking.api.model.PaymentInitiationJson;
import dev.tkachuk.openbanking.api.model.PaymentInitiationRequestResponse201;
import dev.tkachuk.openbanking.api.model.PaymentInitiationStatusResponse200Json;
import dev.tkachuk.openbanking.api.model.PaymentInitiationWithStatusResponse;
import dev.tkachuk.openbanking.api.model.StatusReasonCode;
import dev.tkachuk.openbanking.api.model.TransactionStatus;
import dev.tkachuk.openbanking.common.PublicUrlProperties;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.security.AuthenticatedProvider;
import java.net.URI;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The contract's payment endpoints for the bank's one product: initiate, read, status and cancel. */
@RestController
class PaymentsController implements PaymentsApi {

    /** Credit transfers in hryvnias to any account at a bank in Ukraine. */
    static final String PRODUCT = "domestic-credit-transfers";
    /** The customer's page for a payment, served by the approval package. */
    static final String CUSTOMER_PAYMENTS = "/customer/payments/";
    private static final String PAYMENT_PATH = "/v2/payments/" + PRODUCT + "/";

    private final PaymentService payments;
    private final AuthenticatedProvider provider;
    private final PublicUrlProperties publicUrl;

    PaymentsController(PaymentService payments, AuthenticatedProvider provider, PublicUrlProperties publicUrl) {
        this.payments = payments;
        this.provider = provider;
        this.publicUrl = publicUrl;
    }

    @Override
    public ResponseEntity<PaymentInitiationRequestResponse201> initiatePayment(String paymentProduct, UUID requestId,
            String psuIpAddress, PaymentInitiationJson body, @Nullable URI tppRedirectUri) {
        requireProduct(paymentProduct);
        Payment payment = payments.create(PaymentRequests.from(body, tppRedirectUri), provider.id());
        String self = PAYMENT_PATH + payment.id();
        // The customer approves on the bank's own page, which the provider sends them to (the redirect approach).
        // The link is absolute, since the customer's browser opens it.
        LinksPaymentInitiation links = new LinksPaymentInitiation()
                .scaRedirect(new HrefType(publicUrl.page(CUSTOMER_PAYMENTS + payment.id())))
                .self(new HrefType(self))
                .status(new HrefType(self + "/status"));
        PaymentInitiationRequestResponse201 response = new PaymentInitiationRequestResponse201(
                transactionStatus(payment), payment.id().toString(), links);
        return ResponseEntity.created(URI.create(self)).header("ASPSP-SCA-Approach", "REDIRECT").body(response);
    }

    @Override
    public ResponseEntity<PaymentInitiationWithStatusResponse> getPaymentInformation(String paymentProduct,
            String paymentId, UUID requestId) {
        requireProduct(paymentProduct);
        Payment payment = payments.get(paymentId, provider.id());
        PaymentOrder order = payment.order();
        PaymentInitiationWithStatusResponse response = new PaymentInitiationWithStatusResponse(
                new AccountReference1().iban(order.debtorIban()),
                new Amount(order.currency(), order.amount().toPlainString()),
                new AccountReference1().iban(order.creditorIban()),
                new PartyDescription1().name(order.creditorName()))
                .remittanceInformationUnstructured(order.remittance())
                .transactionStatus(transactionStatus(payment));
        if (order.endToEndId() != null) {
            response.paymentIdentification(new PaymentIdentification1().endToEndId(order.endToEndId()));
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<PaymentInitiationStatusResponse200Json> getPaymentInitiationStatus(String paymentProduct,
            String paymentId, UUID requestId) {
        requireProduct(paymentProduct);
        Payment payment = payments.get(paymentId, provider.id());
        PaymentInitiationStatusResponse200Json response =
                new PaymentInitiationStatusResponse200Json(transactionStatus(payment));
        if (payment.reason() != null) {
            response.reasonCode(StatusReasonCode.fromValue(payment.reason().code()));
        }
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> cancelPayment(String paymentProduct, String paymentId, UUID requestId) {
        requireProduct(paymentProduct);
        payments.cancel(paymentId, provider.id());
        return ResponseEntity.noContent().build();
    }

    private static void requireProduct(String paymentProduct) {
        if (!PRODUCT.equals(paymentProduct)) {
            throw new ApiException(ErrorCode.PRODUCT_UNKNOWN, ErrorCode.PRODUCT_UNKNOWN.text());
        }
    }

    private static TransactionStatus transactionStatus(Payment payment) {
        return TransactionStatus.fromValue(payment.status().code());
    }
}
