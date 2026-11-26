package dev.tkachuk.provider.web;

import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.jspecify.annotations.Nullable;

/**
 * What the provider remembers of one visitor between its pages, in the visitor's session: the account they let the
 * provider read, the consent for it, and the payment under way. Each trip to the bank's page gets a state of its
 * own, which the address the bank sends the browser back to carries; a return with another state is not this
 * visitor's (an attacker's link, an old tab) and changes nothing.
 */
public final class Journey {

    private static final String ATTRIBUTE = Journey.class.getName();
    private static final SecureRandom RANDOM = new SecureRandom();

    private @Nullable String iban;
    private @Nullable String consentId;
    private @Nullable String consentState;
    private boolean consentValid;
    private @Nullable String paymentId;
    private @Nullable String paymentState;

    /** The visitor's journey, a new one on their first visit. */
    public static Journey of(HttpSession session) {
        Object journey = session.getAttribute(ATTRIBUTE);
        if (journey instanceof Journey existing) {
            return existing;
        }
        Journey started = new Journey();
        session.setAttribute(ATTRIBUTE, started);
        return started;
    }

    /** A fresh state for one trip to the bank's page: 128 random bits, hex. */
    static String newState() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /** The visitor is on their way to approve a consent for this account; any earlier consent is forgotten. */
    void consentStarted(String iban, String consentId, String state) {
        this.iban = iban;
        this.consentId = consentId;
        this.consentState = state;
        this.consentValid = false;
    }

    /** Whether a return from the bank with this state ends the trip that consentStarted began. */
    boolean returnsFromConsent(@Nullable String state) {
        return consentId != null && sameState(consentState, state);
    }

    /** The trip is over: the bank says the consent is valid, or it is not and the visitor may ask again. */
    void consentDecided(boolean valid) {
        this.consentState = null;
        this.consentValid = valid;
        if (!valid) {
            this.consentId = null;
        }
    }

    void consentEnded() {
        this.consentId = null;
        this.consentState = null;
        this.consentValid = false;
    }

    void paymentStarted(String paymentId, String state) {
        this.paymentId = paymentId;
        this.paymentState = state;
    }

    boolean returnsFromPayment(@Nullable String state) {
        return paymentId != null && sameState(paymentState, state);
    }

    void paymentDecided() {
        this.paymentState = null;
    }

    public @Nullable String iban() {
        return iban;
    }

    public @Nullable String consentId() {
        return consentId;
    }

    /** A consent the bank says is valid: the provider may read the account. */
    public boolean hasValidConsent() {
        return consentValid && consentId != null;
    }

    public @Nullable String paymentId() {
        return paymentId;
    }

    // In constant time, as a secret is compared.
    private static boolean sameState(@Nullable String expected, @Nullable String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
