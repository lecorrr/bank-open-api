package dev.tkachuk.openbanking.common;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** The rule for a provider's TPP-Redirect-URI, where the customer's browser goes after a decision at the bank. */
public final class TppRedirectUris {

    /** The longest the bank stores, as its tables do. */
    public static final int MAX_LENGTH = 2048;

    private TppRedirectUris() {
    }

    /** Refuses a URI the bank would not send a customer to; no URI is fine, the customer then stays at the bank. */
    public static void check(@Nullable URI uri) {
        if (uri == null) {
            return;
        }
        // The bank sends the customer's browser there after the decision, so only over TLS, as FAPI 2.0 asks.
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            throw new ApiException(ErrorCode.FORMAT_ERROR, "TPP-Redirect-URI must be an absolute https URI");
        }
        if (uri.toString().length() > MAX_LENGTH) {
            throw new ApiException(ErrorCode.FORMAT_ERROR,
                    "TPP-Redirect-URI must be at most " + MAX_LENGTH + " characters");
        }
    }
}
