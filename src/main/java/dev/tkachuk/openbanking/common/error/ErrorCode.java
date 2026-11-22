package dev.tkachuk.openbanking.common.error;

/** The codes the API answers errors with, from the contract; ApiExceptionHandler gives each its HTTP status. */
public enum ErrorCode {
    FORMAT_ERROR,
    SERVICE_UNAVAILABLE,
    INTERNAL_ERROR
}
