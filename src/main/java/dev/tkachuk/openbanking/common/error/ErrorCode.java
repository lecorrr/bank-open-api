package dev.tkachuk.openbanking.common.error;

import org.springframework.http.HttpStatus;

/**
 * Every error the API answers with: its HTTP status, the contract's code and the text a provider reads when the
 * exception carries none. The contract uses some codes with two statuses: CONSENT_UNKNOWN is 400 for a Consent-ID
 * header and 403 for a path, RESOURCE_UNKNOWN 404 for an account and 403 for a payment.
 */
public enum ErrorCode {
    FORMAT_ERROR(HttpStatus.BAD_REQUEST, "FORMAT_ERROR", "The request has the wrong format"),
    PARAMETER_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "PARAMETER_NOT_SUPPORTED",
            "The request asks for something this bank does not offer"),
    CONSENT_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "CONSENT_TYPE_NOT_SUPPORTED",
            "Only consents of type detailed are offered"),
    CONSENT_ID_UNKNOWN(HttpStatus.BAD_REQUEST, "CONSENT_UNKNOWN", "The Consent-ID is unknown"),
    TOKEN_UNKNOWN(HttpStatus.UNAUTHORIZED, "TOKEN_UNKNOWN", "The request has no access token the bank issued"),
    TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "TOKEN_INVALID", "The access token is not valid for this request"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED", "The access token has expired; get a new one"),
    CERTIFICATE_MISSING(HttpStatus.UNAUTHORIZED, "CERTIFICATE_MISSING",
            "The request came without the provider's client certificate"),
    CERTIFICATE_INVALID(HttpStatus.UNAUTHORIZED, "CERTIFICATE_INVALID", "The client certificate cannot be used"),
    CERTIFICATE_EXPIRED(HttpStatus.UNAUTHORIZED, "CERTIFICATE_EXPIRED", "The client certificate has expired"),
    ROLE_INVALID(HttpStatus.UNAUTHORIZED, "ROLE_INVALID", "The provider's token does not allow this service"),
    CONSENT_INVALID(HttpStatus.UNAUTHORIZED, "CONSENT_INVALID", "The consent does not allow this request"),
    CONSENT_EXPIRED(HttpStatus.UNAUTHORIZED, "CONSENT_EXPIRED", "The consent has expired"),
    CONSENT_UNKNOWN(HttpStatus.FORBIDDEN, "CONSENT_UNKNOWN", "The consent is unknown"),
    RESOURCE_UNKNOWN(HttpStatus.NOT_FOUND, "RESOURCE_UNKNOWN", "The account is not part of the consent"),
    PAYMENT_UNKNOWN(HttpStatus.FORBIDDEN, "RESOURCE_UNKNOWN", "The payment is unknown"),
    PRODUCT_UNKNOWN(HttpStatus.NOT_FOUND, "PRODUCT_UNKNOWN",
            "The bank does not offer this payment product; it offers domestic-credit-transfers"),
    SERVICE_INVALID(HttpStatus.METHOD_NOT_ALLOWED, "SERVICE_INVALID", "This method is not offered for this path"),
    CANCELLATION_INVALID(HttpStatus.METHOD_NOT_ALLOWED, "CANCELLATION_INVALID",
            "Only a payment the customer has not decided on yet can be cancelled"),
    REQUESTED_FORMATS_INVALID(HttpStatus.NOT_ACCEPTABLE, "REQUESTED_FORMATS_INVALID",
            "The API answers with JSON only"),
    STATUS_INVALID(HttpStatus.CONFLICT, "STATUS_INVALID", "It changed at the same time; read its status"),
    ACCESS_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "ACCESS_EXCEEDED",
            "The consent's reads without the customer are used up for today"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Unexpected error; quote the X-Request-ID of the request when reporting it"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
            "The service is not available at the moment; retry later");

    private final HttpStatus status;
    private final String code;
    private final String text;

    ErrorCode(HttpStatus status, String code, String text) {
        this.status = status;
        this.code = code;
        this.text = text;
    }

    public HttpStatus status() {
        return status;
    }

    /** The code as the contract spells it, the "code" of the problem details. */
    public String code() {
        return code;
    }

    public String text() {
        return text;
    }
}
