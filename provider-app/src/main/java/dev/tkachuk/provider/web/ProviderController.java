package dev.tkachuk.provider.web;

import dev.tkachuk.provider.ProviderProperties;
import dev.tkachuk.provider.bank.BankApi;
import dev.tkachuk.provider.bank.PaymentOrder;
import dev.tkachuk.provider.bank.Started;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The provider's pages. A visitor asks the provider to read an account at the bank; the provider asks the bank for a
 * consent and sends the visitor to the bank's page, where they sign in and decide. The bank sends them back here,
 * and the provider asks the bank what they decided: the return itself says nothing the provider would believe.
 * Payments go the same way.
 */
@Controller
class ProviderController {

    private static final Pattern IBAN = Pattern.compile("[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}");
    private static final Pattern AMOUNT = Pattern.compile("[0-9]{1,12}(\\.[0-9]{2})?");
    // The contract's limits for a creditor's name and an unstructured remittance.
    private static final int NAME_MAX = 70;
    private static final int REMITTANCE_MAX = 140;

    private final BankApi bank;
    private final ProviderProperties properties;
    private final FormDefaults defaults;

    ProviderController(BankApi bank, ProviderProperties properties, FormDefaults defaults) {
        this.bank = bank;
        this.properties = properties;
        this.defaults = defaults;
    }

    @GetMapping("/")
    ModelAndView index(HttpSession session) {
        return page("index", session);
    }

    @PostMapping("/consents")
    ModelAndView startConsent(@RequestParam String iban, HttpServletRequest request, HttpSession session) {
        String account = compact(iban);
        if (!IBAN.matcher(account).matches()) {
            return refused(session, "That is not an IBAN: two letters, two digits, then 11 to 30 letters or digits.");
        }
        String state = Journey.newState();
        Started consent = bank.startConsent(account, request.getRemoteAddr(), back("/consents/done", state));
        Journey.of(session).consentStarted(account, consent.id(), state);
        return toBank(consent);
    }

    @GetMapping("/consents/done")
    ModelAndView consentDone(@RequestParam(required = false) @Nullable String state, HttpSession session,
            RedirectAttributes redirect) {
        Journey journey = Journey.of(session);
        if (!journey.returnsFromConsent(state)) {
            return notThisVisit(session);
        }
        String status = bank.consentStatus(journey.consentId());
        journey.consentDecided("valid".equals(status));
        if (journey.hasValidConsent()) {
            return new ModelAndView("redirect:/accounts");
        }
        redirect.addFlashAttribute("message", "The bank says the consent is " + status
                + ": the provider may not read the account. You can ask again.");
        return new ModelAndView("redirect:/");
    }

    @GetMapping("/accounts")
    ModelAndView accounts(HttpServletRequest request, HttpSession session) {
        Journey journey = Journey.of(session);
        if (!journey.hasValidConsent()) {
            return new ModelAndView("redirect:/");
        }
        return page("accounts", session).addObject("accounts",
                bank.accounts(journey.consentId(), request.getRemoteAddr()));
    }

    @PostMapping("/consents/end")
    ModelAndView endConsent(HttpSession session, RedirectAttributes redirect) {
        Journey journey = Journey.of(session);
        if (journey.consentId() != null) {
            bank.deleteConsent(journey.consentId());
            journey.consentEnded();
            redirect.addFlashAttribute("message", "The provider ended its access: the bank lets it read no more.");
        }
        return new ModelAndView("redirect:/");
    }

    @PostMapping("/payments")
    ModelAndView startPayment(@RequestParam String creditorIban, @RequestParam String creditorName,
            @RequestParam String amount, @RequestParam(defaultValue = "") String remittance,
            HttpServletRequest request, HttpSession session) {
        Journey journey = Journey.of(session);
        if (!journey.hasValidConsent() || journey.iban() == null) {
            return new ModelAndView("redirect:/");
        }
        String payee = compact(creditorIban);
        String name = creditorName.strip();
        String sum = amount.strip();
        if (!IBAN.matcher(payee).matches()) {
            return refused(session, "The payee's IBAN is not an IBAN.");
        }
        if (name.isEmpty() || name.length() > NAME_MAX) {
            return refused(session, "The payee's name must be 1 to " + NAME_MAX + " characters.");
        }
        if (!AMOUNT.matcher(sum).matches() || new BigDecimal(sum).signum() <= 0) {
            return refused(session, "The amount must be in hryvnias with kopiykas, such as 1.00, and more than 0.");
        }
        if (remittance.strip().length() > REMITTANCE_MAX) {
            return refused(session, "The payment's text must be at most " + REMITTANCE_MAX + " characters.");
        }
        String state = Journey.newState();
        PaymentOrder order = new PaymentOrder(journey.iban(), payee, name, sum, remittance.strip());
        Started payment = bank.startPayment(order, request.getRemoteAddr(), back("/payments/done", state));
        journey.paymentStarted(payment.id(), state);
        return toBank(payment);
    }

    @GetMapping("/payments/done")
    ModelAndView paymentDone(@RequestParam(required = false) @Nullable String state, HttpSession session) {
        Journey journey = Journey.of(session);
        if (!journey.returnsFromPayment(state)) {
            return notThisVisit(session);
        }
        journey.paymentDecided();
        return page("payment", session).addObject("status", bank.paymentStatus(journey.paymentId()));
    }

    // Where the bank sends the browser back: an address of the provider's own, with this trip's state.
    private URI back(String path, String state) {
        return UriComponentsBuilder.fromUri(properties.page(path)).queryParam("state", state).build(true).toUri();
    }

    // The bank's page, as the bank named it; a view of its own, so no view resolver keeps an address per consent.
    private static ModelAndView toBank(Started started) {
        return new ModelAndView(new RedirectView(started.scaRedirect().toString()));
    }

    private ModelAndView page(String view, HttpSession session) {
        return new ModelAndView(view).addObject("journey", Journey.of(session)).addObject("defaults", defaults);
    }

    private ModelAndView refused(HttpSession session, String problem) {
        ModelAndView page = page("index", session).addObject("problem", problem);
        page.setStatus(HttpStatus.BAD_REQUEST);
        return page;
    }

    private ModelAndView notThisVisit(HttpSession session) {
        return refused(session, "This return from the bank does not belong to your visit, so it changes nothing. "
                + "Start again from this page.");
    }

    private static String compact(String iban) {
        return iban.replace(" ", "").toUpperCase(Locale.ROOT);
    }
}
