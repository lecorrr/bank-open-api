package dev.tkachuk.openbanking.payments;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Makes the transfers of approved payments that bank-core could not be asked for when the customer approved. The
 * payment table is the queue: one job per instance, and an attempt claims its payment (PaymentRepository).
 */
@Component
class PaymentRetryJob {

    private final PaymentService payments;

    PaymentRetryJob(PaymentService payments) {
        this.payments = payments;
    }

    // Spring's scheduler logs a failed run and keeps the schedule, so the job does not die with one.
    @Scheduled(fixedDelayString = "${bank-open-api.payments.retry-delay}",
            initialDelayString = "${bank-open-api.payments.retry-delay}")
    void retry() {
        payments.retryApproved();
    }
}
