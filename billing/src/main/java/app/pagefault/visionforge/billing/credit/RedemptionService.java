package app.pagefault.visionforge.billing.credit;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import app.pagefault.visionforge.billing.error.BillingErrorCode;
import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.error.CommonErrorCode;
import app.pagefault.visionforge.common.id.IdGenerator;

import lombok.RequiredArgsConstructor;

/**
 * Manages promotional credit redemption codes (CDK) and atomic per-user redemption.
 */
@RequiredArgsConstructor
public class RedemptionService {

    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int MAX_BATCH_COUNT = 100;
    private static final int BATCH_NAME_MAX_LENGTH = 100;

    private final CreditRedemptionCodeMapper codeMapper;
    private final CreditRedemptionRecordMapper recordMapper;
    private final CreditService creditService;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    @BizLog(
            type = "BILLING_REDEEM",
            subType = "CREATE_BATCH",
            bizNo = "#batchName",
            success = "管理员创建兑换码批次「{{#batchName}}」（数量={{#count}}，面额={{#amountUnits}}）",
            fail = "创建兑换码批次失败：{{#errorMessage}}",
            sync = true)
    public List<CreditRedemptionCode> createBatch(
            UUID actorId,
            String batchName,
            int count,
            long amountUnits,
            int maxUsesPerCode,
            int perUserLimit,
            String customPrefix,
            Instant expiresAt) {
        String safeBatchName = batchName == null ? "" : batchName.trim();
        if (safeBatchName.isEmpty() || safeBatchName.length() > BATCH_NAME_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "批次名称长度必须在 1 到 100 之间");
        }
        if (count < 1 || count > MAX_BATCH_COUNT) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "单次生成数量必须在 1 到 100 之间");
        }
        if (amountUnits <= 0) {
            throw new BusinessException(BillingErrorCode.AMOUNT_INVALID, "兑换额度必须大于 0");
        }
        if (maxUsesPerCode < 1 || maxUsesPerCode > 100_000) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "单码最大兑换次数必须在 1 到 100000 之间");
        }
        if (perUserLimit < 1 || perUserLimit > maxUsesPerCode) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "每用户限兑次数必须在 1 与单码最大次数之间");
        }
        Instant now = clock.instant();
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "过期时间必须晚于当前时间");
        }
        String prefix = normalizePrefix(customPrefix);

        List<CreditRedemptionCode> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CreditRedemptionCode entity = new CreditRedemptionCode();
            entity.setId(idGenerator.nextId());
            entity.setBatchName(safeBatchName);
            entity.setCode(generateUniqueCode(prefix));
            entity.setAmountUnits(amountUnits);
            entity.setMaxUses(maxUsesPerCode);
            entity.setRedeemedCount(0);
            entity.setPerUserLimit(perUserLimit);
            entity.setStatus(RedemptionCodeStatus.ACTIVE);
            entity.setExpiresAt(expiresAt);
            entity.setCreatedBy(actorId);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            codeMapper.insert(entity);
            created.add(entity);
        }
        return created;
    }

    public PageResult<CreditRedemptionCode> listCodes(
            long page, long size, String batchName, RedemptionCodeStatus status) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 200);
        LambdaQueryWrapper<CreditRedemptionCode> wrapper = Wrappers.<CreditRedemptionCode>lambdaQuery()
                .orderByDesc(CreditRedemptionCode::getCreatedAt)
                .orderByDesc(CreditRedemptionCode::getId);
        if (batchName != null && !batchName.isBlank()) {
            wrapper.like(CreditRedemptionCode::getBatchName, batchName.trim());
        }
        if (status != null) {
            wrapper.eq(CreditRedemptionCode::getStatus, status);
        }
        Page<CreditRedemptionCode> result = codeMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result.getRecords(), result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Transactional
    @BizLog(
            type = "BILLING_REDEEM",
            subType = "VOID",
            bizNo = "#codeId",
            success = "管理员作废兑换码 {{#codeId}}",
            fail = "作废兑换码失败：{{#errorMessage}}",
            sync = true)
    public CreditRedemptionCode voidCode(UUID codeId) {
        CreditRedemptionCode code = codeId == null ? null : codeMapper.selectById(codeId);
        if (code == null) {
            throw new BusinessException(BillingErrorCode.REDEMPTION_CODE_NOT_FOUND);
        }
        if (code.getStatus() == RedemptionCodeStatus.VOIDED) {
            return code;
        }
        code.setStatus(RedemptionCodeStatus.VOIDED);
        code.setUpdatedAt(clock.instant());
        codeMapper.updateById(code);
        return code;
    }

    @Transactional
    @BizLog(
            type = "BILLING_REDEEM",
            subType = "REDEEM",
            bizNo = "#result.code",
            success = "用户核销兑换码 {{#result.code}}，入账 {{#result.amountUnits}}",
            fail = "核销兑换码失败：{{#errorMessage}}")
    public RedeemResult redeem(UUID userId, String rawCode) {
        if (userId == null) {
            throw new BusinessException(BillingErrorCode.ACCOUNT_NOT_FOUND);
        }
        String normalizedCode = rawCode == null ? "" : rawCode.trim().toUpperCase(Locale.ROOT);
        if (normalizedCode.isEmpty() || normalizedCode.length() > 64) {
            throw new BusinessException(BillingErrorCode.REDEMPTION_CODE_NOT_FOUND);
        }
        CreditRedemptionCode code = codeMapper.selectByCodeForUpdate(normalizedCode);
        if (code == null) {
            throw new BusinessException(BillingErrorCode.REDEMPTION_CODE_NOT_FOUND);
        }
        if (code.getStatus() == RedemptionCodeStatus.VOIDED) {
            throw new BusinessException(BillingErrorCode.REDEMPTION_CODE_VOIDED);
        }
        Instant now = clock.instant();
        if (code.getExpiresAt() != null && !code.getExpiresAt().isAfter(now)) {
            throw new BusinessException(BillingErrorCode.REDEMPTION_CODE_EXPIRED);
        }
        if (code.getRedeemedCount() >= code.getMaxUses()) {
            throw new BusinessException(BillingErrorCode.REDEMPTION_CODE_EXHAUSTED);
        }
        int usedByUser = recordMapper.countByCodeAndUser(code.getId(), userId);
        if (usedByUser >= code.getPerUserLimit()) {
            throw new BusinessException(BillingErrorCode.REDEMPTION_USER_LIMIT_REACHED);
        }

        int updated = codeMapper.incrementRedeemedCountIfAvailable(code.getId(), now);
        if (updated == 0) {
            throw new BusinessException(BillingErrorCode.REDEMPTION_CODE_EXHAUSTED);
        }

        UUID recordId = idGenerator.nextId();
        CreditLedger ledger = creditService.creditForRedemption(
                userId,
                code.getAmountUnits(),
                "REDEEM:" + code.getId() + ":" + recordId,
                "兑换码核销: " + code.getCode());

        CreditRedemptionRecord record = new CreditRedemptionRecord();
        record.setId(recordId);
        record.setCodeId(code.getId());
        record.setUserId(userId);
        record.setLedgerId(ledger.getId());
        record.setAmountUnits(code.getAmountUnits());
        record.setCreatedAt(now);
        recordMapper.insert(record);

        CreditAccount updatedAccount = creditService.account(userId);
        long availableUnits = updatedAccount.getBalanceUnits() - updatedAccount.getReservedUnits();
        return new RedeemResult(
                ledger.getId(),
                code.getCode(),
                code.getAmountUnits(),
                updatedAccount.getBalanceUnits(),
                availableUnits);
    }

    private String generateUniqueCode(String prefix) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = prefix + "-" + randomSegment(4) + "-" + randomSegment(4) + "-" + randomSegment(4);
            CreditRedemptionCode existing = codeMapper.selectOne(
                    Wrappers.<CreditRedemptionCode>lambdaQuery().eq(CreditRedemptionCode::getCode, candidate));
            if (existing == null) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate unique redemption code");
    }

    private String randomSegment(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(CODE_ALPHABET.charAt(secureRandom.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private String normalizePrefix(String customPrefix) {
        if (customPrefix == null || customPrefix.isBlank()) {
            return "VF";
        }
        String sanitized = customPrefix.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (sanitized.isEmpty() || sanitized.length() > 12) {
            throw new BusinessException(CommonErrorCode.INVALID_ARGUMENT, "兑换码前缀仅支持 1 到 12 位字母或数字");
        }
        return sanitized;
    }

    public record RedeemResult(
            UUID ledgerId,
            String code,
            long amountUnits,
            long newBalanceUnits,
            long newAvailableUnits) {
    }
}
