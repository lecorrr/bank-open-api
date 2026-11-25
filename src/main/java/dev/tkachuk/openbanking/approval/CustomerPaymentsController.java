package dev.tkachuk.openbanking.approval;

import dev.tkachuk.openbanking.common.error.ApiException;
import dev.tkachuk.openbanking.common.error.ErrorCode;
import dev.tkachuk.openbanking.payments.CustomerPayment;
import dev.tkachuk.openbanking.payments.PaymentService;
import dev.tkachuk.openbanking.security.SignedInCustomer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The bank's page for a payment a provider sent the customer to (the scaRedirect link): approving makes the payment
 * at once. Errors are pages too (CustomerPageErrors).
 */
@Controller
class CustomerPaymentsController {

    static final String PAYMENTS = "/customer/payments";

    private final PaymentService payments;
    private final SignedInCustomer customer;

    CustomerPaymentsController(PaymentService payments, SignedInCustomer customer) {
        this.payments = payments;
        this.customer = customer;
    }

    @GetMapping(PAYMENTS + "/{paymentId}")
    String payment(@PathVariable String paymentId, Model model) {
        model.addAttribute("page", new PaymentPage(payments.forCustomer(paymentId, customer.id())));
        model.addAttribute("customerName", customer.name());
        return "customer/payment";
    }

    @PostMapping(PAYMENTS + "/{paymentId}")
    String decide(@PathVariable String paymentId, @RequestParam(defaultValue = "") String decision,
            HttpServletRequest request, HttpServletResponse response, Model model) {
        CustomerPayment payment = switch (decision) {
            case "approve" -> payments.approve(paymentId, customer.id());
            case "reject" -> payments.reject(paymentId, customer.id());
            default -> throw new ApiException(ErrorCode.FORMAT_ERROR, "decision must be approve or reject");
        };
        // The customer is done here: the session ends, so a shared computer keeps nothing of it.
        customer.signOut(request, response);
        if (payment.tppRedirectUri() != null) {
            return "redirect:" + payment.tppRedirectUri();
        }
        PaymentPage page = new PaymentPage(payment);
        return CustomerPages.message(model, page.decidedTitle(), page.decidedText());
    }
}
