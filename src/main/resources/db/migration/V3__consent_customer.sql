-- The customer who decided on a consent at the bank (step 4): who approved or rejected it, and who may withdraw it.
ALTER TABLE consent
    ADD COLUMN customer_id varchar(36),
    -- Only a consent still waiting for the customer, or one its provider ended while it waited, has none.
    -- NOT VALID: rows from 0.3.x are not checked, every new or changed row is.
    ADD CONSTRAINT consent_decided_by_customer
        CHECK (status IN ('received', 'terminatedByTpp') OR customer_id IS NOT NULL) NOT VALID;

-- The customer's own page lists the consents they gave.
CREATE INDEX consent_customer_idx ON consent (customer_id);
