package app.pagefault.visionforge.billing.credit;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.pagefault.visionforge.billing.error.BillingErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedemptionServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000001");
    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-000000000002");
    private static final Instant NOW = Instant.parse("2026-11-19T00:00:00Z");

    private CreditRedemptionCodeMapper codeMapper;
    private CreditRedemptionRecordMapper recordMapper;
    private CreditService creditService;
    private RedemptionService service;

    @BeforeEach
    void setUp() {
        codeMapper = mock(CreditRedemptionCodeMapper.class);
        recordMapper = mock(CreditRedemptionRecordMapper.class);
        creditService = mock(CreditService.class);
        AtomicLong seq = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0L, seq.incrementAndGet());
        service = new RedemptionService(
                codeMapper, recordMapper, creditService, idGenerator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createBatchGeneratesUniquePrefixedCodes() {
        when(codeMapper.selectOne(any())).thenReturn(null);

        List<CreditRedemptionCode> batch = service.createBatch(
                ADMIN_ID, "Launch Promo", 3, 1000L, 5, 1, "vf-pro", NOW.plusSeconds(3600));

        assertThat(batch).hasSize(3);
        assertThat(batch).allSatisfy(code -> {
            assertThat(code.getCode()).startsWith("VFPRO-");
            assertThat(code.getAmountUnits()).isEqualTo(1000L);
            assertThat(code.getMaxUses()).isEqualTo(5);
            assertThat(code.getStatus()).isEqualTo(RedemptionCodeStatus.ACTIVE);
        });
    }

    @Test
    void redeemCreditsAccountAtomicallyAndEnforcesLimits() {
        CreditRedemptionCode code = activeCode("VF-ABCD-1234-5678", 500L, 5, 1, NOW.plusSeconds(600));
        when(codeMapper.selectByCodeForUpdate("VF-ABCD-1234-5678")).thenReturn(code);
        when(recordMapper.countByCodeAndUser(code.getId(), USER_ID)).thenReturn(0);
        when(codeMapper.incrementRedeemedCountIfAvailable(code.getId(), NOW)).thenReturn(1);

        CreditLedger ledger = new CreditLedger();
        ledger.setId(UUID.randomUUID());
        ledger.setAmountUnits(500L);
        when(creditService.creditForRedemption(eq(USER_ID), eq(500L), anyString(), anyString()))
                .thenReturn(ledger);

        CreditAccount updatedAccount = new CreditAccount();
        updatedAccount.setUserId(USER_ID);
        updatedAccount.setBalanceUnits(1500L);
        updatedAccount.setReservedUnits(200L);
        when(creditService.account(USER_ID)).thenReturn(updatedAccount);

        RedemptionService.RedeemResult result = service.redeem(USER_ID, "  vf-abcd-1234-5678 ");
        assertThat(result.code()).isEqualTo("VF-ABCD-1234-5678");
        assertThat(result.amountUnits()).isEqualTo(500L);
        assertThat(result.newBalanceUnits()).isEqualTo(1500L);
        assertThat(result.newAvailableUnits()).isEqualTo(1300L);
        verify(recordMapper).insert(any(CreditRedemptionRecord.class));
    }

    @Test
    void redeemRejectsVoidedExpiredExhaustedAndOverUserLimit() {
        CreditRedemptionCode voided = activeCode("VF-VOID", 100L, 1, 1, null);
        voided.setStatus(RedemptionCodeStatus.VOIDED);
        when(codeMapper.selectByCodeForUpdate("VF-VOID")).thenReturn(voided);
        assertThatThrownBy(() -> service.redeem(USER_ID, "VF-VOID"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(BillingErrorCode.REDEMPTION_CODE_VOIDED));

        CreditRedemptionCode expired = activeCode("VF-EXP", 100L, 1, 1, NOW.minusSeconds(1));
        when(codeMapper.selectByCodeForUpdate("VF-EXP")).thenReturn(expired);
        assertThatThrownBy(() -> service.redeem(USER_ID, "VF-EXP"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(BillingErrorCode.REDEMPTION_CODE_EXPIRED));

        CreditRedemptionCode exhausted = activeCode("VF-MAX", 100L, 2, 1, null);
        exhausted.setRedeemedCount(2);
        when(codeMapper.selectByCodeForUpdate("VF-MAX")).thenReturn(exhausted);
        assertThatThrownBy(() -> service.redeem(USER_ID, "VF-MAX"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(BillingErrorCode.REDEMPTION_CODE_EXHAUSTED));

        CreditRedemptionCode limited = activeCode("VF-USER", 100L, 5, 1, null);
        when(codeMapper.selectByCodeForUpdate("VF-USER")).thenReturn(limited);
        when(recordMapper.countByCodeAndUser(limited.getId(), USER_ID)).thenReturn(1);
        assertThatThrownBy(() -> service.redeem(USER_ID, "VF-USER"))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getErrorCode()).isEqualTo(BillingErrorCode.REDEMPTION_USER_LIMIT_REACHED));
    }

    private CreditRedemptionCode activeCode(
            String code, long amount, int maxUses, int perUser, Instant expiresAt) {
        CreditRedemptionCode entity = new CreditRedemptionCode();
        entity.setId(UUID.randomUUID());
        entity.setBatchName("Test Batch");
        entity.setCode(code);
        entity.setAmountUnits(amount);
        entity.setMaxUses(maxUses);
        entity.setRedeemedCount(0);
        entity.setPerUserLimit(perUser);
        entity.setStatus(RedemptionCodeStatus.ACTIVE);
        entity.setExpiresAt(expiresAt);
        entity.setCreatedAt(NOW);
        entity.setUpdatedAt(NOW);
        return entity;
    }
}
