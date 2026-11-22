package dev.tkachuk.openbanking.consents;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** How long a consent may run at most, in days from the day it is created. */
@ConfigurationProperties("bank-open-api.consents")
record ConsentProperties(int maxValidityDays) {

    ConsentProperties {
        if (maxValidityDays < 1 || maxValidityDays > 3650) {
            throw new IllegalArgumentException(
                    "bank-open-api.consents.max-validity-days must be 1 to 3650, got " + maxValidityDays);
        }
    }
}
