package dev.tkachuk.openbanking.approval;

import org.springframework.ui.Model;

/** What the customer pages share: the page that tells the customer an outcome or a refusal. */
final class CustomerPages {

    static final String MESSAGE_VIEW = "customer/message";

    private CustomerPages() {
    }

    static String message(Model model, String title, String text) {
        model.addAttribute("title", title);
        model.addAttribute("message", text);
        return MESSAGE_VIEW;
    }
}
