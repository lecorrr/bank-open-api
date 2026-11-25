package dev.tkachuk.openbanking.approval;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.consents.CustomerConsent;
import dev.tkachuk.openbanking.security.CustomerSignInConfig;
import dev.tkachuk.openbanking.security.SignedInCustomer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The bank's pages for a signed-in customer: the consent a provider sent them to decide on (the scaRedirect link),
 * and the consents they gave, which they may withdraw. Errors are pages too (CustomerPageErrors).
 */
@Controller
class CustomerConsentsController {

    static final String CONSENTS = "/customer/consents";

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
        return CustomerPages.message(model, approved ? "Consent approved" : "Consent rejected", approved
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
        return CustomerPages.message(model, "Sign-in did not finish",
                "The bank could not sign you in. Go back to the app that sent you here and start again.");
    }

    @GetMapping(CustomerSignInConfig.SIGNED_OUT_PATH)
    String signedOut(Model model) {
        return CustomerPages.message(model, "Signed out",
                "You are signed out of the bank's pages. You can close this page.");
    }
}
