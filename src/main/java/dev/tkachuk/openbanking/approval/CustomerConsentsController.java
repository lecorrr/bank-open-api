package dev.tkachuk.openbanking.approval;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.consents.CustomerConsent;
import dev.tkachuk.openbanking.security.CustomerSignInConfig;
import dev.tkachuk.openbanking.security.NotACustomerException;
import dev.tkachuk.openbanking.security.SignedInCustomer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The bank's pages for a signed-in customer: the consent a provider sent them to decide on (the scaRedirect link),
 * and the consents they gave, which they may withdraw. Errors are pages too, not the API's problem details.
 */
@Controller
class CustomerConsentsController {

    static final String CONSENTS = "/customer/consents";

    private static final Logger log = LoggerFactory.getLogger(CustomerConsentsController.class);
    private static final String MESSAGE_VIEW = "customer/message";

    private final ApprovalService approvals;
    private final SignedInCustomer customer;

    CustomerConsentsController(ApprovalService approvals, SignedInCustomer customer) {
        this.approvals = approvals;
        this.customer = customer;
    }

    @GetMapping(CONSENTS + "/{consentId}")
    String consent(@PathVariable String consentId, Model model) {
        model.addAttribute("page", approvals.page(consentId, customer.id()));
        model.addAttribute("customerName", customer.name());
        return "customer/consent";
    }

    @PostMapping(CONSENTS + "/{consentId}")
    String decide(@PathVariable String consentId, @RequestParam(defaultValue = "") String decision,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        CustomerConsent consent = switch (decision) {
            case "approve" -> approvals.approve(consentId, customer.id());
            case "reject" -> approvals.reject(consentId, customer.id());
            default -> throw new ApiException(ErrorCode.FORMAT_ERROR, "decision must be approve or reject");
        };
        // The customer is done here: the session ends, so a shared computer keeps nothing of it.
        customer.signOut(request, response);
        if (consent.tppRedirectUri() != null) {
            return "redirect:" + consent.tppRedirectUri();
        }
        boolean approved = "approve".equals(decision);
        return message(model, approved ? "Consent approved" : "Consent rejected", approved
                ? "The provider can now read what you approved. You can close this page."
                : "The provider gets no access. You can close this page.");
    }

    @GetMapping(CONSENTS)
    String consents(Model model) {
        model.addAttribute("consents", approvals.validConsentsOf(customer.id()));
        model.addAttribute("customerName", customer.name());
        return "customer/consents";
    }

    @PostMapping(CONSENTS + "/{consentId}/withdraw")
    String withdraw(@PathVariable String consentId, RedirectAttributes redirect) {
        approvals.revoke(consentId, customer.id());
        redirect.addFlashAttribute("notice", "The consent is withdrawn: the provider can no longer read your data.");
        return "redirect:" + CONSENTS;
    }

    @GetMapping(CustomerSignInConfig.SIGN_IN_FAILED_PATH)
    String signInFailed(Model model) {
        return message(model, "Sign-in did not finish",
                "The bank could not sign you in. Go back to the app that sent you here and start again.");
    }

    @GetMapping(CustomerSignInConfig.SIGNED_OUT_PATH)
    String signedOut(Model model) {
        return message(model, "Signed out", "You are signed out of the bank's pages. You can close this page.");
    }

    @ExceptionHandler(ApiException.class)
    String refused(ApiException exception, HttpServletResponse response, Model model) {
        Refusal refusal = Refusal.of(exception.code());
        if (refusal.status().is5xxServerError()) {
            log.warn("Customer page answered {}: {}", exception.code().code(), exception.getMessage());
        } else {
            log.info("Customer page refused with {}: {}", exception.code().code(), exception.getMessage());
        }
        response.setStatus(refusal.status().value());
        return message(model, refusal.title(), refusal.message());
    }

    @ExceptionHandler(NotACustomerException.class)
    String notACustomer(NotACustomerException exception, HttpServletResponse response, Model model) {
        log.warn("Customer page refused: {}", exception.getMessage());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        return message(model, "Not a customer",
                "Your sign-in carries no customer number, so the bank cannot show consents.");
    }

    // Anything else still gets a page; the API's handler would answer a browser with JSON.
    @ExceptionHandler(Exception.class)
    String failed(Exception exception, HttpServletResponse response, Model model) {
        log.error("Customer page failed", exception);
        response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
        return message(model, "Something went wrong",
                "The bank could not finish your request. Please try again later.");
    }

    private static String message(Model model, String title, String text) {
        model.addAttribute("title", title);
        model.addAttribute("message", text);
        return MESSAGE_VIEW;
    }

    /** What a customer is told when a page cannot do what they asked. */
    private record Refusal(HttpStatus status, String title, String message) {

        static Refusal of(ErrorCode code) {
            return switch (code) {
                case CONSENT_UNKNOWN, CONSENT_ID_UNKNOWN -> new Refusal(HttpStatus.NOT_FOUND, "No such consent",
                        "The bank has no consent request at this address. Ask the app that sent you here to start"
                                + " again.");
                case STATUS_INVALID -> new Refusal(HttpStatus.CONFLICT, "Already decided",
                        "This consent is no longer waiting for you, or it changed while you were deciding.");
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
