package dev.tkachuk.openbanking.common;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** The ids of consents and payments in the API's paths and the customer pages' links. */
public final class ResourceIds {

    // The form the bank hands its ids out in. UUID.fromString alone would also take forms such as "1-1-1-1-1".
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private ResourceIds() {
    }

    /** The id a path names; empty when the text is no id the bank could have handed out. */
    public static Optional<UUID> parse(String text) {
        return UUID_TEXT.matcher(text).matches() ? Optional.of(UUID.fromString(text)) : Optional.empty();
    }
}
