package dev.tkachuk.provider.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * What the provider's forms suggest, so a visitor can go through with a click: bank-core's demo accounts, which the
 * bank's test customer owns the first of.
 */
@ConfigurationProperties("provider-app.form")
public record FormDefaults(String iban, String payeeIban, String payeeName, String amount, String remittance) {
}
