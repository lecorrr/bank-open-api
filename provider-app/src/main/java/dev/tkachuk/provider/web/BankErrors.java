package dev.tkachuk.provider.web;

import dev.tkachuk.provider.bank.BankApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

/**
 * A call to the bank that failed ends on a page that says what the bank answered: its status, its code and its
 * text. The visitor can start again; the provider's log has the same.
 */
@ControllerAdvice
class BankErrors {

    private static final Logger log = LoggerFactory.getLogger(BankErrors.class);

    @ExceptionHandler(BankApiException.class)
    ModelAndView bankFailed(BankApiException e) {
        log.warn("{}: status {}, code {}, {}", e.getMessage(), e.status(), e.code(), e.detail());
        ModelAndView page = new ModelAndView("bank-error");
        page.addObject("failure", e);
        page.setStatus(e.status() == 0 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY);
        return page;
    }
}
