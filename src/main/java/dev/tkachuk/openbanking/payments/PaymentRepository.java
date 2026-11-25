package dev.tkachuk.openbanking.payments;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Payments in PostgreSQL, through plain SQL. */
@Repository
class PaymentRepository {

    private final JdbcClient jdbc;

    PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Payment payment) {
        PaymentOrder order = payment.order();
        jdbc.sql("""
                INSERT INTO payment (id, provider_id, debtor_iban, creditor_iban, creditor_name, amount, currency,
                                     remittance, end_to_end_id, tpp_redirect_uri, created_at, status, reason_code,
                                     customer_id, transfer_id, status_changed_at, attempted_at)
                VALUES (:id, :providerId, :debtorIban, :creditorIban, :creditorName, :amount, :currency, :remittance,
                        :endToEndId, :tppRedirectUri, :createdAt, :status, :reasonCode, :customerId, :transferId,
                        :statusChangedAt, :attemptedAt)
                """)
                .param("id", payment.id())
                .param("providerId", payment.providerId())
                .param("debtorIban", order.debtorIban())
                .param("creditorIban", order.creditorIban())
                .param("creditorName", order.creditorName())
                .param("amount", order.amount())
                .param("currency", order.currency())
                .param("remittance", order.remittance())
                .param("endToEndId", order.endToEndId())
                .param("tppRedirectUri", payment.tppRedirectUri() == null ? null : payment.tppRedirectUri().toString())
                .param("createdAt", utc(payment.createdAt()))
                .param("status", payment.status().code())
                .param("reasonCode", payment.reason() == null ? null : payment.reason().code())
                .param("customerId", payment.customerId())
                .param("transferId", payment.transferId())
                .param("statusChangedAt", utc(payment.statusChangedAt()))
                .param("attemptedAt", payment.attemptedAt() == null ? null : utc(payment.attemptedAt()))
                .update();
    }

    Optional<Payment> findById(UUID id) {
        return jdbc.sql("""
                SELECT id, provider_id, debtor_iban, creditor_iban, creditor_name, amount, currency, remittance,
                       end_to_end_id, tpp_redirect_uri, created_at, status, reason_code, customer_id, transfer_id,
                       status_changed_at, attempted_at
                FROM payment
                WHERE id = :id
                """)
                .param("id", id)
                .query(PaymentRepository::row)
                .optional();
    }

    /**
     * Saves a status change and what came with it, unless someone else changed the status since the payment was
     * read: two attempts at the same payment store one outcome.
     */
    boolean updateStatus(Payment payment, PaymentStatus statusAsRead) {
        return jdbc.sql("""
                UPDATE payment
                SET status = :status, reason_code = :reasonCode, customer_id = :customerId, transfer_id = :transferId,
                    status_changed_at = :statusChangedAt, attempted_at = :attemptedAt
                WHERE id = :id AND status = :statusAsRead
                """)
                .param("status", payment.status().code())
                .param("reasonCode", payment.reason() == null ? null : payment.reason().code())
                .param("customerId", payment.customerId())
                .param("transferId", payment.transferId())
                .param("statusChangedAt", utc(payment.statusChangedAt()))
                .param("attemptedAt", payment.attemptedAt() == null ? null : utc(payment.attemptedAt()))
                .param("id", payment.id())
                .param("statusAsRead", statusAsRead.code())
                .update() == 1;
    }

    /** Approved payments whose last attempt at the transfer started before the given time, oldest first. */
    List<Payment> findWaitingForTransfer(Instant lastAttemptBefore, int limit) {
        return jdbc.sql("""
                SELECT id, provider_id, debtor_iban, creditor_iban, creditor_name, amount, currency, remittance,
                       end_to_end_id, tpp_redirect_uri, created_at, status, reason_code, customer_id, transfer_id,
                       status_changed_at, attempted_at
                FROM payment
                WHERE status = 'ACTC' AND attempted_at < :lastAttemptBefore
                ORDER BY attempted_at, id
                LIMIT :limit
                """)
                .param("lastAttemptBefore", utc(lastAttemptBefore))
                .param("limit", limit)
                .query(PaymentRepository::row)
                .list();
    }

    /**
     * Takes an approved payment for a new attempt at its transfer, unless another attempt has started since the
     * given time. One statement, so of two jobs only one gets it.
     */
    boolean claimAttempt(UUID paymentId, Instant lastAttemptBefore, Instant now) {
        return jdbc.sql("""
                UPDATE payment
                SET attempted_at = :now
                WHERE id = :id AND status = 'ACTC' AND attempted_at < :lastAttemptBefore
                """)
                .param("now", utc(now))
                .param("id", paymentId)
                .param("lastAttemptBefore", utc(lastAttemptBefore))
                .update() == 1;
    }

    private static Payment row(ResultSet rs, int rowNum) throws SQLException {
        String redirectUri = rs.getString("tpp_redirect_uri");
        String reasonCode = rs.getString("reason_code");
        PaymentOrder order = new PaymentOrder(
                rs.getString("debtor_iban"),
                rs.getString("creditor_iban"),
                rs.getString("creditor_name"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getString("remittance"),
                rs.getString("end_to_end_id"));
        return new Payment(
                rs.getObject("id", UUID.class),
                rs.getString("provider_id"),
                order,
                redirectUri == null ? null : URI.create(redirectUri),
                instant(rs, "created_at"),
                PaymentStatus.fromCode(rs.getString("status")),
                reasonCode == null ? null : RejectionReason.fromCode(reasonCode),
                rs.getString("customer_id"),
                rs.getObject("transfer_id", UUID.class),
                instant(rs, "status_changed_at"),
                instantOrNull(rs, "attempted_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant instantOrNull(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    // The PostgreSQL driver takes OffsetDateTime for timestamptz, not Instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
