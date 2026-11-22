package dev.tkachuk.openbanking.common.web;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Errors in the RFC 7807 form of the contract: type, title, status, detail and a Berlin Group code such as
 * FORMAT_ERROR. Details name fields and rules; they never repeat what the caller sent and never name the bank's
 * own systems.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        String detail = ex.providerDetail().orElse(ex.code().text());
        if (ex.providerDetail().isEmpty()) {
            // The message may name internals, such as what bank-core answered, so it goes to the log only.
            log.warn("Answering {}: {}", ex.code().code(), ex.getMessage());
        } else {
            logRefusal(ex.code(), detail);
        }
        return ResponseEntity.status(ex.code().status()).body(problem(ex.code(), detail));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.status())
                .body(problem(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.text()));
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleServletRequestBindingException(
            ServletRequestBindingException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex instanceof MissingRequestHeaderException missing
                ? "Header " + missing.getHeaderName() + " is missing"
                : "The request is incomplete";
        return formatError(ex, detail, headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName() : "A value";
        return formatError(ex, name + " has the wrong format", headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return formatError(ex, "The request body is not JSON in the form the contract describes", headers, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        FieldError error = ex.getBindingResult().getFieldError();
        String detail = error != null ? describe(error) : ErrorCode.FORMAT_ERROR.text();
        return formatError(ex, detail, headers, request);
    }

    // Spring MVC's remaining errors (unknown path, wrong method, wrong media type) get the contract's codes too.
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem && !hasCode(problem)) {
            ErrorCode code = codeFor(statusCode);
            String detail = switch (statusCode.value()) {
                case 404 -> "There is no API resource at this path";
                case 415 -> "The API reads JSON only";
                default -> code.text();
            };
            logRefusal(code, detail);
            body = problem(statusCode, code, detail);
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private @Nullable ResponseEntity<Object> formatError(
            Exception ex, String detail, HttpHeaders headers, WebRequest request) {
        logRefusal(ErrorCode.FORMAT_ERROR, detail);
        return handleExceptionInternal(ex, problem(ErrorCode.FORMAT_ERROR, detail), headers,
                ErrorCode.FORMAT_ERROR.status(), request);
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 404 -> ErrorCode.RESOURCE_UNKNOWN;
            case 405 -> ErrorCode.SERVICE_INVALID;
            case 406 -> ErrorCode.REQUESTED_FORMATS_INVALID;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            default -> status.is4xxClientError() ? ErrorCode.FORMAT_ERROR : ErrorCode.INTERNAL_ERROR;
        };
    }

    private static boolean hasCode(ProblemDetail problem) {
        return problem.getProperties() != null && problem.getProperties().containsKey("code");
    }

    // The field's path and the rule it breaks; the rejected value stays out.
    private static String describe(FieldError error) {
        String rule = error.getDefaultMessage();
        return error.getField() + " " + (rule != null ? rule : "is invalid");
    }

    private static void logRefusal(ErrorCode code, String detail) {
        if (code.status().is5xxServerError()) {
            log.warn("Answering {}: {}", code.code(), detail);
        } else {
            log.info("Refused with {}: {}", code.code(), detail);
        }
    }

    static ProblemDetail problem(ErrorCode code, String detail) {
        return problem(code.status(), code, detail);
    }

    // Spring's own errors keep their status, 415 for example, and get the nearest code.
    private static ProblemDetail problem(HttpStatusCode status, ErrorCode code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        String slug = code.code().toLowerCase(Locale.ROOT).replace('_', '-');
        problem.setType(URI.create("urn:bank-open-api:error:" + slug));
        // "consent-type-not-supported" becomes "Consent type not supported".
        String words = slug.replace('-', ' ');
        problem.setTitle(Character.toUpperCase(words.charAt(0)) + words.substring(1));
        problem.setProperty("code", code.code());
        return problem;
    }
}
