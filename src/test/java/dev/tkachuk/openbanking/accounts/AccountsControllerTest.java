package dev.tkachuk.openbanking.accounts;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tkachuk.openbanking.bankcore.BankCoreAccount;
import dev.tkachuk.openbanking.bankcore.BankCoreClient;
import dev.tkachuk.openbanking.bankcore.BankCoreUnavailableException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountsController.class)
class AccountsControllerTest {

    private static final String REQUEST_ID = "99391c7e-ad88-49ec-a2ad-99ddcb1f7721";
    private static final String IBAN_A = "UA843999990000000000000000001";
    private static final String IBAN_B = "UA033999990000000000000000004";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BankCoreClient bankCore;

    @Test
    void shouldListAccountsInTheContractShape() throws Exception {
        when(bankCore.listAccounts()).thenReturn(List.of(
                new BankCoreAccount(IBAN_A, "UAH"), new BankCoreAccount(IBAN_B, "USD")));

        mockMvc.perform(get("/v2/accounts").header("X-Request-ID", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-ID", REQUEST_ID))
                .andExpect(jsonPath("$.accounts", hasSize(2)))
                .andExpect(jsonPath("$.accounts[0].resourceId").value(IBAN_A))
                .andExpect(jsonPath("$.accounts[0].iban").value(IBAN_A))
                .andExpect(jsonPath("$.accounts[0].currency").value("UAH"))
                .andExpect(jsonPath("$.accounts[1].currency").value("USD"));
    }

    @Test
    void shouldListNoAccountsAsEmptyList() throws Exception {
        when(bankCore.listAccounts()).thenReturn(List.of());

        mockMvc.perform(get("/v2/accounts").header("X-Request-ID", REQUEST_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts", hasSize(0)));
    }

    @Test
    void shouldRefuseRequestWithoutRequestId() throws Exception {
        mockMvc.perform(get("/v2/accounts"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.type").value("urn:bank-open-api:error:format-error"))
                .andExpect(jsonPath("$.detail").value("Header X-Request-ID is missing"));

        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldRefuseRequestIdThatIsNotUuid() throws Exception {
        mockMvc.perform(get("/v2/accounts").header("X-Request-ID", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().doesNotExist("X-Request-ID"))
                .andExpect(jsonPath("$.code").value("FORMAT_ERROR"))
                .andExpect(jsonPath("$.detail").value("X-Request-ID has the wrong format"));

        verifyNoInteractions(bankCore);
    }

    @Test
    void shouldAnswer503WhenBankCoreIsUnavailable() throws Exception {
        when(bankCore.listAccounts()).thenThrow(new BankCoreUnavailableException("bank-core answered 500", null));

        mockMvc.perform(get("/v2/accounts").header("X-Request-ID", REQUEST_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("X-Request-ID", REQUEST_ID))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.title").value("Service unavailable"))
                .andExpect(jsonPath("$.detail", not(containsString("bank-core"))));
    }

    @Test
    void shouldAnswer500ForUnexpectedError() throws Exception {
        when(bankCore.listAccounts()).thenThrow(new IllegalStateException("a bug"));

        mockMvc.perform(get("/v2/accounts").header("X-Request-ID", REQUEST_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail", not(containsString("a bug"))));
    }
}
