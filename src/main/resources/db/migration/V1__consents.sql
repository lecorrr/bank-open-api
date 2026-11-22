-- Consents that let a provider read account data, and the reads made under them without the customer.

CREATE TABLE consent (
    id                uuid          PRIMARY KEY,
    status            varchar(20)   NOT NULL,
    recurring         boolean       NOT NULL,
    valid_to          date          NOT NULL,
    frequency_per_day integer       NOT NULL,
    tpp_redirect_uri  varchar(2048),
    created_at        timestamptz   NOT NULL,
    status_changed_at timestamptz   NOT NULL,
    -- "expired" is not stored: it follows from valid_to on every read.
    CONSTRAINT consent_status_known
        CHECK (status IN ('received', 'valid', 'rejected', 'revokedByPsu', 'terminatedByTpp')),
    CONSTRAINT consent_frequency_in_range CHECK (frequency_per_day BETWEEN 1 AND 4)
);

-- One row per account a consent names; resource_id is the account's id in the API's paths.
CREATE TABLE consent_account (
    resource_id     uuid        PRIMARY KEY,
    consent_id      uuid        NOT NULL REFERENCES consent (id),
    position        integer     NOT NULL,
    iban            varchar(34) NOT NULL,
    currency        char(3),
    account_details boolean     NOT NULL,
    balances        boolean     NOT NULL,
    CONSTRAINT consent_account_once UNIQUE (consent_id, iban),
    CONSTRAINT consent_account_some_right CHECK (account_details OR balances)
);

-- Reads without the customer, per consent, per data read and per Kyiv day; frequency_per_day limits them.
CREATE TABLE consent_read (
    consent_id uuid        NOT NULL REFERENCES consent (id),
    target     varchar(60) NOT NULL,
    read_day   date        NOT NULL,
    reads      integer     NOT NULL,
    PRIMARY KEY (consent_id, target, read_day)
);
