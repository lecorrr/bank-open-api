-- Payments a provider initiates and the customer decides on at the bank; an approved one becomes a bank-core
-- transfer. status is the contract's ISO 20022 transaction status.

CREATE TABLE payment (
    id                uuid          PRIMARY KEY,
    provider_id       varchar(255)  NOT NULL,
    debtor_iban       varchar(34)   NOT NULL,
    creditor_iban     varchar(34)   NOT NULL,
    creditor_name     varchar(140)  NOT NULL,
    -- Up to bank-core's limit of 1 000 000 000.00.
    amount            numeric(12,2) NOT NULL,
    currency          char(3)       NOT NULL,
    remittance        varchar(140)  NOT NULL,
    end_to_end_id     varchar(35),
    tpp_redirect_uri  varchar(2048),
    created_at        timestamptz   NOT NULL,
    status            char(4)       NOT NULL,
    -- AM04 or DS04 when the bank rejected an approved payment; none when the customer rejected it.
    reason_code       char(4),
    customer_id       varchar(36),
    transfer_id       uuid,
    status_changed_at timestamptz   NOT NULL,
    -- When the bank last started making the transfer of an approved payment; the retry job goes by it.
    attempted_at      timestamptz,
    CONSTRAINT payment_status_known CHECK (status IN ('RCVD', 'CANC', 'RJCT', 'ACTC', 'ACCC', 'ACSP', 'ACSC')),
    CONSTRAINT payment_amount_positive CHECK (amount > 0),
    -- Only a payment still waiting for the customer, or one its provider cancelled while it waited, has none.
    CONSTRAINT payment_decided_by_customer CHECK (status IN ('RCVD', 'CANC') OR customer_id IS NOT NULL),
    CONSTRAINT payment_reason_when_rejected CHECK (reason_code IS NULL OR status = 'RJCT'),
    CONSTRAINT payment_transfer_when_made CHECK (status NOT IN ('ACCC', 'ACSP', 'ACSC') OR transfer_id IS NOT NULL),
    -- Without it the retry job would never find the payment.
    CONSTRAINT payment_attempted_when_approved CHECK (status <> 'ACTC' OR attempted_at IS NOT NULL)
);

-- The retry job's query: approved payments by their last attempt.
CREATE INDEX payment_waiting_idx ON payment (attempted_at) WHERE status = 'ACTC';
