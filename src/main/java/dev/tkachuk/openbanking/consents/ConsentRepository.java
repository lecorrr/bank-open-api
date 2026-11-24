package dev.tkachuk.openbanking.consents;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Consents in PostgreSQL, through plain SQL. */
@Repository
class ConsentRepository {

    private final JdbcClient jdbc;

    ConsentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(Consent consent) {
        jdbc.sql("""
                INSERT INTO consent (id, provider_id, customer_id, status, recurring, valid_to, frequency_per_day,
                                     tpp_redirect_uri, created_at, status_changed_at)
                VALUES (:id, :providerId, :customerId, :status, :recurring, :validTo, :frequencyPerDay,
                        :tppRedirectUri, :createdAt, :statusChangedAt)
                """)
                .param("id", consent.id())
                .param("providerId", consent.providerId())
                .param("customerId", consent.customerId())
                .param("status", consent.storedStatus().code())
                .param("recurring", consent.recurring())
                .param("validTo", consent.validTo())
                .param("frequencyPerDay", consent.frequencyPerDay())
                .param("tppRedirectUri", consent.tppRedirectUri() == null ? null : consent.tppRedirectUri().toString())
                .param("createdAt", utc(consent.createdAt()))
                .param("statusChangedAt", utc(consent.statusChangedAt()))
                .update();
        int position = 0;
        for (ConsentAccount account : consent.accounts()) {
            jdbc.sql("""
                    INSERT INTO consent_account (resource_id, consent_id, position, iban, currency,
                                                 account_details, balances)
                    VALUES (:resourceId, :consentId, :position, :iban, :currency, :accountDetails, :balances)
                    """)
                    .param("resourceId", account.resourceId())
                    .param("consentId", consent.id())
                    .param("position", position++)
                    .param("iban", account.iban())
                    .param("currency", account.currency())
                    .param("accountDetails", account.allows(AccessRight.ACCOUNT_DETAILS))
                    .param("balances", account.allows(AccessRight.BALANCES))
                    .update();
        }
    }

    Optional<Consent> findById(UUID id) {
        // The consent first: its accounts were inserted in the same transaction, so once it is there they are too.
        return jdbc.sql("""
                SELECT id, provider_id, customer_id, status, recurring, valid_to, frequency_per_day, tpp_redirect_uri,
                       created_at, status_changed_at
                FROM consent
                WHERE id = :id
                """)
                .param("id", id)
                .query(ConsentRepository::row)
                .optional()
                .map(row -> row.toConsent(accountsOf(List.of(id)).getOrDefault(id, List.of())));
    }

    /**
     * The consents this customer approved that are valid on the given day, newest approval first. Two queries
     * whatever their number: the consents, then all their accounts.
     */
    List<Consent> findValidOf(String customerId, LocalDate today) {
        List<ConsentRow> rows = jdbc.sql("""
                SELECT id, provider_id, customer_id, status, recurring, valid_to, frequency_per_day, tpp_redirect_uri,
                       created_at, status_changed_at
                FROM consent
                WHERE customer_id = :customerId AND status = 'valid' AND valid_to >= :today
                ORDER BY status_changed_at DESC, id
                """)
                .param("customerId", customerId)
                .param("today", today)
                .query(ConsentRepository::row)
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ConsentAccount>> accounts = accountsOf(rows.stream().map(ConsentRow::id).toList());
        return rows.stream().map(row -> row.toConsent(accounts.getOrDefault(row.id(), List.of()))).toList();
    }

    /**
     * Saves a status change and the customer who made it, unless someone else changed the status since the consent
     * was read.
     */
    boolean updateStatus(Consent consent, ConsentStatus statusAsRead) {
        return jdbc.sql("""
                UPDATE consent
                SET status = :status, status_changed_at = :statusChangedAt, customer_id = :customerId
                WHERE id = :id AND status = :statusAsRead
                """)
                .param("status", consent.storedStatus().code())
                .param("statusChangedAt", utc(consent.statusChangedAt()))
                .param("customerId", consent.customerId())
                .param("id", consent.id())
                .param("statusAsRead", statusAsRead.code())
                .update() == 1;
    }

    /**
     * Counts one read without the customer. Returns false, and counts nothing, once the day's limit is reached.
     * One statement, so two reads at the same moment cannot both take the last one.
     */
    boolean countRead(UUID consentId, String target, LocalDate day, int limit) {
        return jdbc.sql("""
                INSERT INTO consent_read (consent_id, target, read_day, reads)
                VALUES (:consentId, :target, :day, 1)
                ON CONFLICT (consent_id, target, read_day)
                DO UPDATE SET reads = consent_read.reads + 1
                WHERE consent_read.reads < :limit
                """)
                .param("consentId", consentId)
                .param("target", target)
                .param("day", day)
                .param("limit", limit)
                .update() == 1;
    }

    // The accounts of each consent, in the order the provider named them.
    private Map<UUID, List<ConsentAccount>> accountsOf(List<UUID> consentIds) {
        Map<UUID, List<ConsentAccount>> accounts = new HashMap<>();
        jdbc.sql("""
                SELECT consent_id, resource_id, iban, currency, account_details, balances
                FROM consent_account
                WHERE consent_id IN (:consentIds)
                ORDER BY consent_id, position
                """)
                .param("consentIds", consentIds)
                .query((rs, rowNum) -> Map.entry(rs.getObject("consent_id", UUID.class), account(rs, rowNum)))
                .list()
                .forEach(entry -> accounts.computeIfAbsent(entry.getKey(), id -> new ArrayList<>())
                        .add(entry.getValue()));
        return accounts;
    }

    private static ConsentAccount account(ResultSet rs, int rowNum) throws SQLException {
        Set<AccessRight> rights = EnumSet.noneOf(AccessRight.class);
        if (rs.getBoolean("account_details")) {
            rights.add(AccessRight.ACCOUNT_DETAILS);
        }
        if (rs.getBoolean("balances")) {
            rights.add(AccessRight.BALANCES);
        }
        return new ConsentAccount(rs.getObject("resource_id", UUID.class), rs.getString("iban"),
                rs.getString("currency"), rights);
    }

    private static ConsentRow row(ResultSet rs, int rowNum) throws SQLException {
        String redirectUri = rs.getString("tpp_redirect_uri");
        return new ConsentRow(
                rs.getObject("id", UUID.class),
                rs.getString("provider_id"),
                rs.getString("customer_id"),
                ConsentStatus.fromCode(rs.getString("status")),
                rs.getBoolean("recurring"),
                rs.getObject("valid_to", LocalDate.class),
                rs.getInt("frequency_per_day"),
                redirectUri == null ? null : URI.create(redirectUri),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("status_changed_at", OffsetDateTime.class).toInstant());
    }

    // The PostgreSQL driver takes OffsetDateTime for timestamptz, not Instant.
    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private record ConsentRow(UUID id, @Nullable String providerId, @Nullable String customerId,
            ConsentStatus status, boolean recurring, LocalDate validTo, int frequencyPerDay,
            @Nullable URI tppRedirectUri, Instant createdAt, Instant statusChangedAt) {

        Consent toConsent(List<ConsentAccount> accounts) {
            return new Consent(id, providerId, customerId, status, accounts, recurring, validTo, frequencyPerDay,
                    tppRedirectUri, createdAt, statusChangedAt);
        }
    }
}
