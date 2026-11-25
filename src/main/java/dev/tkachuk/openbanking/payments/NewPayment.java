package dev.tkachuk.openbanking.payments;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/** A provider's payment initiation as it sent it, before the payment rules have checked it. */
record NewPayment(
        String debtorIban,
        @Nullable String debtorCurrency,
        String creditorIban,
        @Nullable String creditorCurrency,
        @Nullable String creditorName,
        String amount,
        String currency,
        @Nullable String remittance,
        @Nullable String endToEndId,
        @Nullable URI tppRedirectUri) {
}
