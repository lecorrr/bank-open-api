package dev.tkachuk.openbanking.approval;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.payments.AccountNotHeldException;
import dev.tkachuk.openbanking.security.NotACustomerException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Errors on the customer pages, the controllers of this package, are pages too. The API's handler applies to every
 * controller and would answer a browser with JSON, so this one comes first.
 */
@ControllerAdvice(basePackageClasses = CustomerPageErrors.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class CustomerPageErrors {

    private static final Logger log = LoggerFactory.getLogger(CustomerPageErrors.class);

    @ExceptionHandler(ApiException.class)
    String refused(ApiException exception, HttpServletResponse response, Model model) {
        Refusal refusal = Refusal.of(exception.code());
        if (refusal.status().is5xxServerError()) {
            log.warn("Customer page answered {}: {}", exception.code().code(), exception.getMessage());
        } else {
            log.info("Customer page refused with {}: {}", exception.code().code(), exception.getMessage());
        }
        response.setStatus(refusal.status().value());
        return CustomerPages.message(model, refusal.title(), refusal.message());
    }

    @ExceptionHandler(AccountNotHeldException.class)
    String notHeld(AccountNotHeldException exception, HttpServletResponse response, Model model) {
        log.info("Customer page refused: {}", exception.getMessage());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return CustomerPages.message(model, "Not your account",
                "The payment is to be made from an account that is not yours, so you can only reject it.");
    }

    @ExceptionHandler(NotACustomerException.class)
    String notACustomer(NotACustomerException exception, HttpServletResponse response, Model model) {
        log.warn("Customer page refused: {}", exception.getMessage());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return CustomerPages.message(model, "Not a customer",
                "Your sign-in carries no customer number, so the bank cannot show you this page.");
    }

    // Anything else still gets a page.
    @ExceptionHandler(Exception.class)
    String failed(Exception exception, HttpServletResponse response, Model model) {
        log.error("Customer page failed", exception);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return CustomerPages.message(model, "Something went wrong",
                "The bank could not finish your request. Please try again later.");
    }

    /** What a customer is told when a page cannot do what they asked. */
    private record Refusal(HttpStatus status, String title, String message) {

        static Refusal of(ErrorCode code) {
            return switch (code) {
                case CONSENT_UNKNOWN, CONSENT_ID_UNKNOWN -> new Refusal(HttpStatus.NOT_FOUND, "No such consent",
                        "The bank has no consent request at this address. Ask the app that sent you here to start"
                                + " again.");
                case PAYMENT_UNKNOWN -> new Refusal(HttpStatus.NOT_FOUND, "No such payment",
                        "The bank has no payment at this address. Ask the app that sent you here to start again.");
                case STATUS_INVALID -> new Refusal(HttpStatus.CONFLICT, "Already decided",
                        "This request is no longer waiting for you, or it changed while you were deciding.");
                case CONSENT_INVALID -> new Refusal(HttpStatus.FORBIDDEN, "Not your accounts",
                        "The request names an account that is not yours, so you can only reject it.");
                case SERVICE_UNAVAILABLE -> new Refusal(HttpStatus.SERVICE_UNAVAILABLE, "Try again later",
                        "The bank cannot check your accounts right now. Please try again in a minute.");
                case FORMAT_ERROR -> new Refusal(HttpStatus.BAD_REQUEST, "Unknown request",
                        "The page could not tell what you asked for. Please go back and try again.");
                default -> new Refusal(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong",
                        "The bank could not finish your request. Please try again later.");
            };
        }
    }
}
