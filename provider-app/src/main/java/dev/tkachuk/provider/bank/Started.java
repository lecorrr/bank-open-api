package dev.tkachuk.provider.bank;

import java.net.URI;

/**
 * A consent or payment the bank has taken, waiting for the customer's decision on the bank's page.
 *
 * @param id the consent's or payment's id at the bank
 * @param scaRedirect the bank's page where the customer decides; the provider sends the browser there
 */
public record Started(String id, URI scaRedirect) {
}
