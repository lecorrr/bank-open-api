package dev.tkachuk.openbanking.common.error;

import org.springframework.http.HttpStatus;

/**
 * Every error the API answers with: its HTTP status, the contract's code and the text a provider reads when the
 * exception carries none. The contract uses CONSENT_UNKNOWN twice: 400 for a Consent-ID header, 403 for a path.
 */
public enum ErrorCode {
    FORMAT_ERROR(HttpStatus.BAD_REQUEST, "FORMAT_ERROR", "The request has the wrong format"),
    PARAMETER_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "PARAMETER_NOT_SUPPORTED",
            "The request asks for something this bank does not offer"),
    CONSENT_TYPE_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "CONSENT_TYPE_NOT_SUPPORTED",
            "Only consents of type detailed are offered"),
    CONSENT_ID_UNKNOWN(HttpStatus.BAD_REQUEST, "CONSENT_UNKNOWN", "The Consent-ID is unknown"),
    CONSENT_INVALID(HttpStatus.UNAUTHORIZED, "CONSENT_INVALID", "The consent does not allow this request"),
    CONSENT_EXPIRED(HttpStatus.UNAUTHORIZED, "CONSENT_EXPIRED", "The consent has expired"),
    CONSENT_UNKNOWN(HttpStatus.FORBIDDEN, "CONSENT_UNKNOWN", "The consent is unknown"),
    RESOURCE_UNKNOWN(HttpStatus.NOT_FOUND, "RESOURCE_UNKNOWN", "The account is not part of the consent"),
    SERVICE_INVALID(HttpStatus.METHOD_NOT_ALLOWED, "SERVICE_INVALID", "This method is not offered for this path"),
    REQUESTED_FORMATS_INVALID(HttpStatus.NOT_ACCEPTABLE, "REQUESTED_FORMATS_INVALID",
            "The API answers with JSON only"),
    STATUS_INVALID(HttpStatus.CONFLICT, "STATUS_INVALID", "The consent changed at the same time; read its status"),
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
