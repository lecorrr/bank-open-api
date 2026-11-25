package dev.tkachuk.openbanking.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tkachuk.openbanking.api.model.AccountReference1;
import dev.tkachuk.openbanking.api.model.AccountReference3;
import dev.tkachuk.openbanking.api.model.AgentDescription1;
import dev.tkachuk.openbanking.api.model.Amount;
import dev.tkachuk.openbanking.api.model.ChargeBearer;
import dev.tkachuk.openbanking.api.model.PartyDescription1;
import dev.tkachuk.openbanking.api.model.PaymentIdentification1;
import dev.tkachuk.openbanking.api.model.PaymentInitiationJson;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PaymentRequestsTest {

    private static final String OWN_IBAN = "UA843999990000000000000000001";
    private static final String PAYEE_IBAN = "UA573999990000000000000000002";
    private static final URI TPP_REDIRECT = URI.create("https://tpp.example/payments/done");

    @Test
    void shouldTurnInitiationIntoNewPayment() {
        PaymentInitiationJson body = body()
                .paymentIdentification(new PaymentIdentification1().endToEndId("E2E-42"))
                .remittanceInformationUnstructured("Rent for November");
        body.getDebtorAccount().currency("UAH");

        NewPayment request = PaymentRequests.from(body, TPP_REDIRECT);

        assertThat(request).isEqualTo(new NewPayment(OWN_IBAN, "UAH", PAYEE_IBAN, null, "Olena Kovalenko", "150.50",
                "UAH", "Rent for November", "E2E-42", TPP_REDIRECT));
    }

    // The rules of the product (a purpose, a payee's name) are Payment's; here only what the bank does not offer.
    @Test
    void shouldLeaveRequiredTextsToThePaymentRules() {
        NewPayment request = PaymentRequests.from(body().creditor(new PartyDescription1()), null);

        assertThat(request.creditorName()).isNull();
        assertThat(request.remittance()).isNull();
        assertThat(request.endToEndId()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fieldsTheBankDoesNotOffer")
    void shouldRefuseFieldTheBankDoesNotOffer(String field, Consumer<PaymentInitiationJson> setField) {
        PaymentInitiationJson body = body();
        setField.accept(body);

        assertRefused(body, null, ErrorCode.PARAMETER_NOT_SUPPORTED, field);
    }

    static Stream<Arguments> fieldsTheBankDoesNotOffer() {
        return Stream.of(
                offered("chargeBearer", body -> body.chargeBearer(ChargeBearer.SLEV)),
                offered("chargesAccount", body -> body.chargesAccount(new AccountReference3().iban(OWN_IBAN))),
                offered("intermediaryAgent1", body -> body.intermediaryAgent1(new AgentDescription1())),
                offered("intermediaryAgent2", body -> body.intermediaryAgent2(new AgentDescription1())),
                offered("creditorAgent", body -> body.creditorAgent(new AgentDescription1())),
                offered("ultimateCreditor", body -> body.ultimateCreditor(Map.of("name", "Landlord Ltd"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("accountsNotNamedByIbanOnly")
    void shouldRefuseAccountNotNamedByIbanOnly(String field, Consumer<PaymentInitiationJson> changeAccount) {
        PaymentInitiationJson body = body();
        changeAccount.accept(body);

        assertRefused(body, null, ErrorCode.PARAMETER_NOT_SUPPORTED, field);
    }

    static Stream<Arguments> accountsNotNamedByIbanOnly() {
        return Stream.of(
                offered("debtorAccount", body -> body.debtorAccount(new AccountReference1().bban("3999990000000001"))),
                offered("debtorAccount", body -> body.getDebtorAccount().maskedPan("123456******1234")),
                offered("creditorAccount", body -> body.getCreditorAccount().pan("1234567890123456")),
                offered("creditorAccount", body -> body.getCreditorAccount().bban("3999990000000002")));
    }

    @Test
    void shouldRefuseRedirectUriThatIsNotHttps() {
        assertRefused(body(), URI.create("http://tpp.example/payments/done"), ErrorCode.FORMAT_ERROR,
                "TPP-Redirect-URI");
    }

    private static void assertRefused(PaymentInitiationJson body, @Nullable URI redirectUri, ErrorCode code,
            String field) {
        assertThatThrownBy(() -> PaymentRequests.from(body, redirectUri))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code))
                .hasMessageStartingWith(field);
    }

    private static Arguments offered(String field, Consumer<PaymentInitiationJson> change) {
        return Arguments.of(field, change);
    }

    private static PaymentInitiationJson body() {
        return new PaymentInitiationJson(new AccountReference1().iban(OWN_IBAN), new Amount("UAH", "150.50"),
                new AccountReference1().iban(PAYEE_IBAN), new PartyDescription1().name("Olena Kovalenko"));
    }
}
