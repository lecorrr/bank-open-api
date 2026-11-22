package dev.tkachuk.openbanking.common.web;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Errors in the RFC 7807 form of the contract: type, title, status, detail and a Berlin Group code such as
 * FORMAT_ERROR. Details never repeat what the caller sent and never name the bank's own systems.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
        // The message may name internals, such as what bank-core answered, so it goes to the log only.
        log.warn("Answering {}: {}", ex.code(), ex.getMessage());
        HttpStatusCode status = statusOf(ex.code());
        return ResponseEntity.status(status).body(problem(status, ex.code(), detailOf(ex.code())));
    }

    // A required header is missing, such as X-Request-ID.
    @Override
    protected ResponseEntity<Object> handleServletRequestBindingException(ServletRequestBindingException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = ex instanceof MissingRequestHeaderException missing
                ? "Header " + missing.getHeaderName() + " is missing"
                : "The request is incomplete";
        return ResponseEntity.status(status).headers(headers).body(problem(status, ErrorCode.FORMAT_ERROR, detail));
    }

    // A header or parameter in the wrong format, such as an X-Request-ID that is not a UUID.
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String name = ex instanceof MethodArgumentTypeMismatchException mismatch ? mismatch.getName()
                : ex.getPropertyName();
        return ResponseEntity.status(status).headers(headers)
                .body(problem(status, ErrorCode.FORMAT_ERROR, name + " has the wrong format"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        HttpStatusCode status = statusOf(ErrorCode.INTERNAL_ERROR);
        return ResponseEntity.status(status)
                .body(problem(status, ErrorCode.INTERNAL_ERROR, detailOf(ErrorCode.INTERNAL_ERROR)));
    }

    // The one place where a code meets HTTP. No default: a new code does not compile until it has a status.
    private static HttpStatusCode statusOf(ErrorCode code) {
        return switch (code) {
            case FORMAT_ERROR -> HttpStatus.BAD_REQUEST;
            case SERVICE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }

    private static String detailOf(ErrorCode code) {
        return switch (code) {
            case FORMAT_ERROR -> "The request has the wrong format";
            case SERVICE_UNAVAILABLE -> "The service is not available at the moment; retry later";
            case INTERNAL_ERROR -> "Unexpected error; quote the X-Request-ID of the request when reporting it";
        };
    }

    private static ProblemDetail problem(HttpStatusCode status, ErrorCode code, String detail) {
        String slug = code.name().toLowerCase(Locale.ROOT).replace('_', '-');
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:bank-open-api:error:" + slug));
        problem.setTitle(titleOf(slug));
        problem.setProperty("code", code.name());
        return problem;
    }

    private static String titleOf(String slug) {
        String title = slug.replace('-', ' ');
        return Character.toUpperCase(title.charAt(0)) + title.substring(1);
    }
}
