package dev.tkachuk.provider.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.provider.ProviderProperties;
import dev.tkachuk.provider.bank.Account;
import dev.tkachuk.provider.bank.BankApi;
import dev.tkachuk.provider.bank.BankApiException;
import dev.tkachuk.provider.bank.PaymentOrder;
import dev.tkachuk.provider.bank.Started;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// The pages with Spring Security's chain and application.yml's settings, the bank a mock.
@WebMvcTest(ProviderController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties({ProviderProperties.class, FormDefaults.class})
class ProviderControllerTest {

    private static final String IBAN = "UA843999990000000000000000001";
    private static final String PAYEE = "UA573999990000000000000000002";
    private static final Started CONSENT =
            new Started("consent-1", URI.create("https://localhost:8443/customer/consents/consent-1"));
    private static final Started PAYMENT =
            new Started("payment-1", URI.create("https://localhost:8443/customer/payments/payment-1"));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BankApi bank;

    private final MockHttpSession session = new MockHttpSession();

    @Test
    void shouldShowTheStartPageWithAFormThatCarriesTheCsrfToken() throws Exception {
        mockMvc.perform(get("/").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Ask the bank for access")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString(IBAN)));
    }

    @Test
    void shouldRefuseAFormPostedWithoutTheCsrfToken() throws Exception {
        mockMvc.perform(post("/consents").param("iban", IBAN).session(session))
                .andExpect(status().isForbidden());

        verifyNoInteractions(bank);
    }

    @Test
    void shouldAskTheBankAndSendTheVisitorToTheBanksPage() throws Exception {
        String back = startConsent();

        assertThat(back).matches("https://provider\\.localhost:8443/consents/done\\?state=[0-9a-f]{32}");
    }

    @Test
    void shouldTakeTheReturnWithItsStateAndAskTheBankWhatTheCustomerDecided() throws Exception {
        String back = startConsent();
        when(bank.consentStatus("consent-1")).thenReturn("valid");

        mockMvc.perform(get(URI.create(back).getRawPath() + "?" + URI.create(back).getRawQuery()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/accounts"));
    }

    @Test
    void shouldTellTheVisitorWhenTheCustomerDidNotApprove() throws Exception {
        String back = startConsent();
        when(bank.consentStatus("consent-1")).thenReturn("rejected");

        mockMvc.perform(get(URI.create(back).getRawPath() + "?" + URI.create(back).getRawQuery()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/"))
                .andExpect(flash().attribute("message", containsString("rejected")));
        mockMvc.perform(get("/accounts").session(session)).andExpect(redirectedUrl("/"));
    }

    @Test
    void shouldIgnoreAReturnThatIsNotThisVisitors() throws Exception {
        startConsent();

        mockMvc.perform(get("/consents/done").param("state", "0".repeat(32)).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("does not belong to your visit")));
        mockMvc.perform(get("/consents/done").session(new MockHttpSession()))
                .andExpect(status().isBadRequest());

        verify(bank, never()).consentStatus(anyString());
    }

    @Test
    void shouldShowTheBalancesUnderAValidConsent() throws Exception {
        approvedConsent();
        when(bank.accounts(eq("consent-1"), anyString())).thenReturn(List.of(new Account("acc-1", IBAN, "UAH",
                List.of(new Account.Balance("closingBooked", "850.00", "UAH")))));

        mockMvc.perform(get("/accounts").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("850.00 UAH")))
                .andExpect(content().string(containsString(IBAN)));
    }

    @Test
    void shouldRefuseAnIbanThatIsNoneBeforeAskingTheBank() throws Exception {
        mockMvc.perform(post("/consents").param("iban", "not an iban").with(csrf()).session(session))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("That is not an IBAN")));

        verifyNoInteractions(bank);
    }

    @Test
    void shouldStartAPaymentFromTheConsentsAccountAndTakeItsReturn() throws Exception {
        approvedConsent();
        ArgumentCaptor<URI> payBack = ArgumentCaptor.forClass(URI.class);
        when(bank.startPayment(eq(new PaymentOrder(IBAN, PAYEE, "Olena Kovalenko", "1.00", "For the demo")),
                anyString(), payBack.capture())).thenReturn(PAYMENT);

        mockMvc.perform(post("/payments").param("creditorIban", "UA57 3999 9900 0000 0000 0000 0000 2")
                        .param("creditorName", " Olena Kovalenko ").param("amount", "1.00")
                        .param("remittance", "For the demo").with(csrf()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(PAYMENT.scaRedirect().toString()));

        when(bank.paymentStatus("payment-1")).thenReturn("ACCC");
        URI back = payBack.getValue();
        mockMvc.perform(get(back.getRawPath() + "?" + back.getRawQuery()).session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("the bank has made the transfer")));
    }

    @Test
    void shouldRefuseAnAmountThatIsNotHryvniasAndKopiykas() throws Exception {
        approvedConsent();

        for (String amount : new String[] {"0.00", "1,00", "1.5", "-1.00", "lots"}) {
            mockMvc.perform(post("/payments").param("creditorIban", PAYEE).param("creditorName", "Olena Kovalenko")
                            .param("amount", amount).with(csrf()).session(session))
                    .andExpect(status().isBadRequest());
        }
        verify(bank, never()).startPayment(any(), anyString(), any());
    }

    @Test
    void shouldNotStartAPaymentWithoutAValidConsent() throws Exception {
        mockMvc.perform(post("/payments").param("creditorIban", PAYEE).param("creditorName", "Olena Kovalenko")
                        .param("amount", "1.00").with(csrf()).session(session))
                .andExpect(redirectedUrl("/"));

        verifyNoInteractions(bank);
    }

    @Test
    void shouldShowWhatTheBankAnsweredWhenItRefuses() throws Exception {
        when(bank.startConsent(eq(IBAN), anyString(), any())).thenThrow(
                new BankApiException("the bank refused creating the consent", 400, "FORMAT_ERROR",
                        "PSU-IP-Address is missing"));

        mockMvc.perform(post("/consents").param("iban", IBAN).with(csrf()).session(session))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(containsString("FORMAT_ERROR")))
                .andExpect(content().string(containsString("PSU-IP-Address is missing")));
    }

    // Starts a consent as the visitor's form does; returns the address the bank was given to send them back to.
    private String startConsent() throws Exception {
        ArgumentCaptor<URI> back = ArgumentCaptor.forClass(URI.class);
        when(bank.startConsent(eq(IBAN), anyString(), back.capture())).thenReturn(CONSENT);

        mockMvc.perform(post("/consents").param("iban", IBAN).with(csrf()).session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(CONSENT.scaRedirect().toString()));
        return back.getValue().toString();
    }

    private void approvedConsent() throws Exception {
        String back = startConsent();
        when(bank.consentStatus("consent-1")).thenReturn("valid");
        mockMvc.perform(get(URI.create(back).getRawPath() + "?" + URI.create(back).getRawQuery()).session(session))
                .andExpect(redirectedUrl("/accounts"));
    }
}
