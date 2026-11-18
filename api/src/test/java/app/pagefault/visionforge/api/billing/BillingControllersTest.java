package app.pagefault.visionforge.api.billing;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import app.pagefault.visionforge.agent.generation.GenerationTaskService;
import app.pagefault.visionforge.api.account.AccountController;
import app.pagefault.visionforge.api.security.SecurityConfiguration;
import app.pagefault.visionforge.billing.credit.CreditLedger;
import app.pagefault.visionforge.billing.credit.CreditLedgerEntryType;
import app.pagefault.visionforge.billing.credit.CreditRedemptionCode;
import app.pagefault.visionforge.billing.credit.CreditService;
import app.pagefault.visionforge.billing.credit.RedemptionCodeStatus;
import app.pagefault.visionforge.billing.credit.RedemptionService;
import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.identity.user.IdentityService;
import app.pagefault.visionforge.identity.user.UserAccount;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {AccountController.class, AdminBillingController.class})
@Import(SecurityConfiguration.class)
class BillingControllersTest {

    private static final UUID ADMIN_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000001");
    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000002");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private CreditService creditService;

    @MockitoBean
    private RedemptionService redemptionService;

    @MockitoBean
    private GenerationTaskService generationTaskService;

    @MockitoBean
    private IdentityService identityService;

    @BeforeEach
    void stubJwt() {
        Instant now = Instant.parse("2026-11-19T00:00:00Z");
        when(jwtDecoder.decode("admin-jwt")).thenReturn(Jwt.withTokenValue("admin-jwt")
                .header("alg", "HS256")
                .subject(ADMIN_ID.toString())
                .claim("role", "ADMIN")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build());
        when(jwtDecoder.decode("user-jwt")).thenReturn(Jwt.withTokenValue("user-jwt")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .claim("role", "USER")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(900))
                .build());
    }

    @Test
    void userCanRedeemCodeAndExportLedgerCsv() throws Exception {
        UUID ledgerId = UUID.randomUUID();
        when(redemptionService.redeem(USER_ID, "VF-TEST-1111"))
                .thenReturn(new RedemptionService.RedeemResult(ledgerId, "VF-TEST-1111", 500L, 1500L, 1500L));

        CreditLedger entry = new CreditLedger();
        entry.setId(ledgerId);
        entry.setUserId(USER_ID);
        entry.setEntryType(CreditLedgerEntryType.REDEEM);
        entry.setAmountUnits(500L);
        entry.setRemark("=EVIL()");
        entry.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        when(creditService.exportUserEntries(USER_ID, 2000)).thenReturn(List.of(entry));

        mockMvc.perform(post("/api/v1/account/credit/redeem")
                        .header("Authorization", "Bearer user-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"VF-TEST-1111\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.amountUnits").value(500))
                .andExpect(jsonPath("$.data.newBalanceUnits").value(1500));

        mockMvc.perform(get("/api/v1/account/credit/ledger/export")
                        .header("Authorization", "Bearer user-jwt"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"credit-ledger.csv\""))
                .andExpect(content().contentType("text/csv;charset=UTF-8"));
    }

    @Test
    void adminCanAdjustCreditCreateAndVoidRedemptionCodesAndQueryLedger() throws Exception {
        UserAccount user = new UserAccount();
        user.setId(USER_ID);
        when(identityService.getUser(USER_ID)).thenReturn(user);

        CreditLedger ledger = new CreditLedger();
        ledger.setId(UUID.randomUUID());
        ledger.setUserId(USER_ID);
        ledger.setEntryType(CreditLedgerEntryType.ADJUST);
        ledger.setAmountUnits(1000L);
        ledger.setBusinessKey("ADJUST:" + USER_ID + ":grant-1");
        ledger.setRemark("Promo");
        ledger.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        when(creditService.adjustCredit(USER_ID, 1000L, "grant-1", "Promo")).thenReturn(ledger);
        when(creditService.listLedger(1L, 50L, USER_ID, CreditLedgerEntryType.ADJUST))
                .thenReturn(PageResult.of(List.of(ledger), 1L, 50L, 1L));

        CreditRedemptionCode code = new CreditRedemptionCode();
        code.setId(UUID.randomUUID());
        code.setBatchName("PromoBatch");
        code.setCode("VF-1111-2222-3333");
        code.setAmountUnits(500L);
        code.setMaxUses(10);
        code.setPerUserLimit(1);
        code.setStatus(RedemptionCodeStatus.ACTIVE);
        code.setCreatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        code.setUpdatedAt(Instant.parse("2026-11-19T00:00:00Z"));
        when(redemptionService.createBatch(ADMIN_ID, "PromoBatch", 1, 500L, 10, 1, null, null))
                .thenReturn(List.of(code));
        when(redemptionService.voidCode(code.getId())).thenReturn(code);

        mockMvc.perform(post("/api/v1/admin/users/" + USER_ID + "/credit-adjust")
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deltaUnits\":1000,\"idempotencyKey\":\"grant-1\",\"remark\":\"Promo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entryType").value("ADJUST"));

        mockMvc.perform(get("/api/v1/admin/billing/ledger")
                        .param("userId", USER_ID.toString())
                        .param("entryType", "ADJUST")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].remark").value("Promo"));

        mockMvc.perform(post("/api/v1/admin/billing/redemptions")
                        .header("Authorization", "Bearer admin-jwt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"batchName\":\"PromoBatch\",\"count\":1,\"amountUnits\":500,\"maxUsesPerCode\":10,\"perUserLimit\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].code").value("VF-1111-2222-3333"));

        mockMvc.perform(post("/api/v1/admin/billing/redemptions/" + code.getId() + "/void")
                        .header("Authorization", "Bearer admin-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("VF-1111-2222-3333"));
    }
}
