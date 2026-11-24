-- The provider that asked for a consent: the client id in its access token ("azp"). Only that provider sees the
-- consent. Consents made before providers signed in (0.2.x) have none, and no provider sees those.

ALTER TABLE consent ADD COLUMN provider_id varchar(255);
