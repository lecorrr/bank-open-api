package dev.tkachuk.openbanking.payments;

import dev.tkachuk.openbanking.api.model.AccountReference1;
import dev.tkachuk.openbanking.api.model.PaymentInitiationJson;
import dev.tkachuk.openbanking.common.TppRedirectUris;
import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** Turns the contract's payment initiation into a NewPayment, refusing what this bank does not offer. */
final class PaymentRequests {

    private PaymentRequests() {
    }

    static NewPayment from(PaymentInitiationJson body, @Nullable URI tppRedirectUri) {
        refuseIfSet("chargeBearer", body.getChargeBearer());
        refuseIfSet("chargesAccount", body.getChargesAccount());
        refuseIfSet("intermediaryAgent1", body.getIntermediaryAgent1());
        refuseIfSet("intermediaryAgent2", body.getIntermediaryAgent2());
        // The creditor's IBAN names its bank, so a domestic transfer needs no agent.
        refuseIfSet("creditorAgent", body.getCreditorAgent());
        refuseIfSet("ultimateCreditor", body.getUltimateCreditor());
        AccountReference1 debtor = body.getDebtorAccount();
        AccountReference1 creditor = body.getCreditorAccount();
        TppRedirectUris.check(tppRedirectUri);
        return new NewPayment(
                iban("debtorAccount", debtor), debtor.getCurrency(),
                iban("creditorAccount", creditor), creditor.getCurrency(),
                body.getCreditor().getName(),
                body.getInstructedAmount().getAmount(),
                body.getInstructedAmount().getCurrency(),
                body.getRemittanceInformationUnstructured(),
                body.getPaymentIdentification() == null ? null : body.getPaymentIdentification().getEndToEndId(),
                tppRedirectUri);
    }

    private static void refuseIfSet(String field, @Nullable Object value) {
        if (value != null) {
            throw new ApiException(ErrorCode.PARAMETER_NOT_SUPPORTED, field + " is not offered by this bank");
        }
    }

    private static String iban(String field, AccountReference1 reference) {
        if (reference.getIban() == null) {
            throw new ApiException(ErrorCode.PARAMETER_NOT_SUPPORTED, field + " must name the account by iban");
        }
        if (reference.getBban() != null || reference.getPan() != null || reference.getMaskedPan() != null) {
            throw new ApiException(ErrorCode.PARAMETER_NOT_SUPPORTED, field + " may name an iban only");
        }
        return reference.getIban();
    }
}
