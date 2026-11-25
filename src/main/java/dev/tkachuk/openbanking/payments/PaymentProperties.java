package dev.tkachuk.openbanking.payments;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long the bank waits before it tries again to make the transfer of an approved payment that bank-core did not
 * confirm. An attempt holds its payment that long, so it must outlast a call to bank-core.
 */
@ConfigurationProperties("bank-open-api.payments")
record PaymentProperties(Duration retryDelay) {

    // Well above a call to bank-core (spring.http.clients: 2 s to connect, 5 s to answer): two attempts at once
    // could cross a refusal and a transfer that went through.
    static final Duration MIN_RETRY_DELAY = Duration.ofSeconds(30);

    PaymentProperties {
        if (retryDelay == null || retryDelay.compareTo(MIN_RETRY_DELAY) < 0) {
            throw new IllegalArgumentException(
                    "bank-open-api.payments.retry-delay must be at least 30s, got " + retryDelay);
        }
    }
}
