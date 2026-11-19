package app.pagefault.visionforge.billing.credit;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import app.pagefault.visionforge.billing.config.BillingProperties;
import app.pagefault.visionforge.billing.error.BillingErrorCode;
import app.pagefault.visionforge.common.api.PageResult;
import app.pagefault.visionforge.common.audit.BizLog;
import app.pagefault.visionforge.common.config.DynamicRuntimeConfig;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

/**
 * Minimal credit ledger: reserve before a paid task, settle on delivery, release on confirmed
 * failure or pre-submit cancellation. Every mutation is keyed by a stable business key so workflow
 * retries cannot double-charge.
 */
public class CreditService {

    private final CreditAccountMapper accountMapper;
    private final CreditReservationMapper reservationMapper;
    private final CreditLedgerMapper ledgerMapper;
    private final IdGenerator idGenerator;
    private final Clock clock;
    private final BillingProperties properties;
    private final DynamicRuntimeConfig runtimeConfig;

    public CreditService(
            CreditAccountMapper accountMapper,
            CreditReservationMapper reservationMapper,
            CreditLedgerMapper ledgerMapper,
            IdGenerator idGenerator,
            Clock clock,
            BillingProperties properties) {
        this(accountMapper, reservationMapper, ledgerMapper, idGenerator, clock, properties, null);
    }

    public CreditService(
            CreditAccountMapper accountMapper,
            CreditReservationMapper reservationMapper,
            CreditLedgerMapper ledgerMapper,
            IdGenerator idGenerator,
            Clock clock,
            BillingProperties properties,
            DynamicRuntimeConfig runtimeConfig) {
        this.accountMapper = accountMapper;
        this.reservationMapper = reservationMapper;
        this.ledgerMapper = ledgerMapper;
        this.idGenerator = idGenerator;
        this.clock = clock;
        this.properties = properties;
        this.runtimeConfig = runtimeConfig;
    }

    public CreditBalance balance(UUID userId) {
        CreditAccount account = ensureAccount(userId);
        return new CreditBalance(
                account.getBalanceUnits(), account.getReservedUnits());
    }

    /**
     * Returns the persisted account, creating it with the initial grant on first access.
     */
    public CreditAccount account(UUID userId) {
        return ensureAccount(userId);
    }

    /**
     * Recent ledger entries for one user, newest first. Ids are UUIDv7, so a strictly smaller id is
     * a stable keyset cursor even when several entries share the same timestamp.
     */
    public List<CreditLedger> recentEntries(UUID userId, int limit, UUID beforeId) {
        requireUser(userId);
        return ledgerMapper.selectList(
                Wrappers.<CreditLedger>lambdaQuery()
                        .eq(CreditLedger::getUserId, userId)
                        .lt(beforeId != null, CreditLedger::getId, beforeId)
                        .orderByDesc(CreditLedger::getId)
                        .last("LIMIT " + safeLimit(limit)));
    }

    public List<CreditLedger> exportUserEntries(UUID userId, int maxRows) {
        requireUser(userId);
        int safeMax = Math.min(Math.max(maxRows, 1), 5_000);
        List<CreditLedger> collected = new java.util.ArrayList<>();
        UUID beforeId = null;
        while (collected.size() < safeMax) {
            int fetch = Math.min(200, safeMax - collected.size());
            List<CreditLedger> batch = recentEntries(userId, fetch, beforeId);
            if (batch.isEmpty()) {
                break;
            }
            collected.addAll(batch);
            if (batch.size() < fetch) {
                break;
            }
            beforeId = batch.get(batch.size() - 1).getId();
        }
        return collected;
    }

    public PageResult<CreditLedger> listLedger(
            long page, long size, UUID userId, CreditLedgerEntryType entryType) {
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 200);
        LambdaQueryWrapper<CreditLedger> wrapper = Wrappers.<CreditLedger>lambdaQuery()
                .eq(userId != null, CreditLedger::getUserId, userId)
                .eq(entryType != null, CreditLedger::getEntryType, entryType)
                .orderByDesc(CreditLedger::getId);
        Page<CreditLedger> result = ledgerMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        return PageResult.of(result.getRecords(), result.getCurrent(), result.getSize(), result.getTotal());
    }

    @Transactional
    @BizLog(
            type = "BILLING_CREDIT",
            subType = "ADJUST",
            bizNo = "#targetUserId",
            success = "管理员调整用户 {{#targetUserId}} 额度 {{#deltaUnits}}（幂等键={{#idempotencyKey}}）",
            fail = "调整用户 {{#targetUserId}} 额度失败：{{#errorMessage}}",
            sync = true)
    public CreditLedger adjustCredit(
            UUID targetUserId, long deltaUnits, String idempotencyKey, String remark) {
        requireUser(targetUserId);
        if (deltaUnits == 0) {
            throw new BusinessException(BillingErrorCode.AMOUNT_INVALID, "调账金额不能为 0");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(BillingErrorCode.AMOUNT_INVALID, "幂等键不能为空");
        }
        String trimmedKey = idempotencyKey.trim();
        if (trimmedKey.length() > 128) {
            throw new BusinessException(BillingErrorCode.AMOUNT_INVALID, "幂等键长度不能超过 128 个字符");
        }
        String businessKey = "ADJUST:" + targetUserId + ":" + trimmedKey;
        CreditLedger existing = ledgerMapper.selectOne(
                Wrappers.<CreditLedger>lambdaQuery().eq(CreditLedger::getBusinessKey, businessKey));
        if (existing != null) {
            if (existing.getAmountUnits() != deltaUnits) {
                throw new BusinessException(BillingErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return existing;
        }

        ensureAccount(targetUserId);
        Instant now = clock.instant();
        if (deltaUnits > 0) {
            int updated = accountMapper.addBalance(targetUserId, deltaUnits, now);
            if (updated == 0) {
                throw new BusinessException(BillingErrorCode.ACCOUNT_NOT_FOUND);
            }
        } else {
            long deductUnits = Math.abs(deltaUnits);
            int updated = accountMapper.deductAvailable(targetUserId, deductUnits, now);
            if (updated == 0) {
                throw new BusinessException(BillingErrorCode.INSUFFICIENT_CREDIT, "用户可用额度不足，无法扣减");
            }
        }

        CreditLedger ledger = new CreditLedger();
        ledger.setId(idGenerator.nextId());
        ledger.setUserId(targetUserId);
        ledger.setEntryType(CreditLedgerEntryType.ADJUST);
        ledger.setAmountUnits(deltaUnits);
        ledger.setBusinessKey(businessKey);
        ledger.setRemark(normalizeRemark(remark));
        ledger.setCreatedAt(now);
        ledgerMapper.insert(ledger);
        return ledger;
    }

    @Transactional
    public CreditLedger creditForRedemption(
            UUID userId, long amountUnits, String businessKey, String remark) {
        requireUser(userId);
        if (amountUnits <= 0) {
            throw new BusinessException(BillingErrorCode.AMOUNT_INVALID, "兑换额度必须大于 0");
        }
        ensureAccount(userId);
        Instant now = clock.instant();
        int updated = accountMapper.addBalance(userId, amountUnits, now);
        if (updated == 0) {
            throw new BusinessException(BillingErrorCode.ACCOUNT_NOT_FOUND);
        }
        CreditLedger ledger = new CreditLedger();
        ledger.setId(idGenerator.nextId());
        ledger.setUserId(userId);
        ledger.setEntryType(CreditLedgerEntryType.REDEEM);
        ledger.setAmountUnits(amountUnits);
        ledger.setBusinessKey(businessKey);
        ledger.setRemark(normalizeRemark(remark));
        ledger.setCreatedAt(now);
        ledgerMapper.insert(ledger);
        return ledger;
    }

    /**
     * Batch lookup used to attach the originating task to ledger entries. Only reservations owned
     * by the caller are returned.
     */
    public Map<UUID, CreditReservation> reservations(
            UUID userId, Collection<UUID> reservationIds) {
        requireUser(userId);
        List<UUID> ids = reservationIds == null
                ? List.of()
                : reservationIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return reservationMapper.selectList(
                        Wrappers.<CreditReservation>lambdaQuery()
                                .eq(CreditReservation::getUserId, userId)
                                .in(CreditReservation::getId, ids))
                .stream()
                .collect(Collectors.toMap(
                        CreditReservation::getId, Function.identity(), (left, right) -> left));
    }

    @Transactional
    public CreditReservation reserve(
            UUID userId, String businessKey, long amountUnits, UUID taskId) {
        requireUser(userId);
        requireBusinessKey(businessKey);
        if (amountUnits < 0) {
            throw new BusinessException(BillingErrorCode.AMOUNT_INVALID);
        }
        ensureAccount(userId);
        CreditReservation existing = find(userId, businessKey);
        if (existing != null) {
            return existing;
        }

        Instant now = clock.instant();
        CreditReservation reservation = new CreditReservation();
        reservation.setId(idGenerator.nextId());
        reservation.setUserId(userId);
        reservation.setBusinessKey(businessKey);
        reservation.setTaskId(taskId);
        reservation.setAmountUnits(amountUnits);
        reservation.setStatus(CreditReservationStatus.RESERVED);
        reservation.setCreatedAt(now);
        if (reservationMapper.insertIfAbsent(reservation) == 0) {
            return find(userId, businessKey);
        }

        if (amountUnits > 0) {
            int updated = accountMapper.update(null, Wrappers.<CreditAccount>lambdaUpdate()
                    .eq(CreditAccount::getUserId, userId)
                    .apply("balance_units - reserved_units >= {0}", amountUnits)
                    .setSql("reserved_units = reserved_units + " + amountUnits)
                    .setSql("version = version + 1")
                    .set(CreditAccount::getUpdatedAt, now));
            if (updated == 0) {
                throw new BusinessException(BillingErrorCode.INSUFFICIENT_CREDIT);
            }
        }
        record(userId, reservation, CreditLedgerEntryType.RESERVE, amountUnits, now);
        return reservation;
    }

    @Transactional
    public CreditReservation settle(UUID userId, String businessKey) {
        CreditReservation reservation = requireReservation(userId, businessKey);
        if (reservation.getStatus() != CreditReservationStatus.RESERVED) {
            return reservation;
        }
        Instant now = clock.instant();
        long amount = reservation.getAmountUnits();
        if (amount > 0) {
            int updated = accountMapper.update(null, Wrappers.<CreditAccount>lambdaUpdate()
                    .eq(CreditAccount::getUserId, userId)
                    .apply("reserved_units >= {0} AND balance_units >= {0}", amount)
                    .setSql("balance_units = balance_units - " + amount)
                    .setSql("reserved_units = reserved_units - " + amount)
                    .setSql("version = version + 1")
                    .set(CreditAccount::getUpdatedAt, now));
            if (updated == 0) {
                throw new BusinessException(BillingErrorCode.SETTLE_CONFLICT);
            }
        }
        reservation.setStatus(CreditReservationStatus.SETTLED);
        reservation.setSettledAt(now);
        reservationMapper.updateById(reservation);
        record(userId, reservation, CreditLedgerEntryType.SETTLE, amount, now);
        return reservation;
    }

    @Transactional
    public CreditReservation release(UUID userId, String businessKey) {
        CreditReservation reservation = requireReservation(userId, businessKey);
        if (reservation.getStatus() != CreditReservationStatus.RESERVED) {
            return reservation;
        }
        Instant now = clock.instant();
        long amount = reservation.getAmountUnits();
        if (amount > 0) {
            int updated = accountMapper.update(null, Wrappers.<CreditAccount>lambdaUpdate()
                    .eq(CreditAccount::getUserId, userId)
                    .apply("reserved_units >= {0}", amount)
                    .setSql("reserved_units = reserved_units - " + amount)
                    .setSql("version = version + 1")
                    .set(CreditAccount::getUpdatedAt, now));
            if (updated == 0) {
                throw new BusinessException(BillingErrorCode.SETTLE_CONFLICT);
            }
        }
        reservation.setStatus(CreditReservationStatus.RELEASED);
        reservation.setSettledAt(now);
        reservationMapper.updateById(reservation);
        record(userId, reservation, CreditLedgerEntryType.RELEASE, amount, now);
        return reservation;
    }

    public CreditReservation find(UUID userId, String businessKey) {
        return reservationMapper.selectOne(
                Wrappers.<CreditReservation>lambdaQuery()
                        .eq(CreditReservation::getUserId, userId)
                        .eq(CreditReservation::getBusinessKey, businessKey));
    }

    private CreditAccount ensureAccount(UUID userId) {
        requireUser(userId);
        CreditAccount account = accountMapper.selectById(userId);
        if (account != null) {
            return account;
        }
        Instant now = clock.instant();
        long configuredInitial = Math.max(properties.initialCreditUnits(), 0);
        long initial = runtimeConfig == null
                ? configuredInitial
                : Math.max(runtimeConfig.initialCreditUnits(configuredInitial), 0);
        accountMapper.insertIfAbsent(userId, initial, now);
        CreditAccount created = accountMapper.selectById(userId);
        if (created == null) {
            throw new BusinessException(BillingErrorCode.ACCOUNT_NOT_FOUND);
        }
        if (initial > 0) {
            recordGrant(userId, initial, now);
        }
        return created;
    }

    private void recordGrant(UUID userId, long amount, Instant now) {
        CreditLedger ledger = new CreditLedger();
        ledger.setId(idGenerator.nextId());
        ledger.setUserId(userId);
        ledger.setEntryType(CreditLedgerEntryType.GRANT);
        ledger.setAmountUnits(amount);
        ledger.setBusinessKey("GRANT:initial:" + userId);
        ledger.setCreatedAt(now);
        ledgerMapper.insertIfAbsent(ledger);
    }

    private void record(
            UUID userId,
            CreditReservation reservation,
            CreditLedgerEntryType type,
            long amount,
            Instant now) {
        CreditLedger ledger = new CreditLedger();
        ledger.setId(idGenerator.nextId());
        ledger.setUserId(userId);
        ledger.setReservationId(reservation.getId());
        ledger.setEntryType(type);
        ledger.setAmountUnits(amount);
        ledger.setBusinessKey(type.name() + ':' + reservation.getId());
        ledger.setCreatedAt(now);
        ledgerMapper.insertIfAbsent(ledger);
    }

    private CreditReservation requireReservation(UUID userId, String businessKey) {
        CreditReservation reservation = find(userId, businessKey);
        if (reservation == null) {
            throw new BusinessException(BillingErrorCode.RESERVATION_NOT_FOUND);
        }
        return reservation;
    }

    private void requireUser(UUID userId) {
        if (userId == null) {
            throw new BusinessException(BillingErrorCode.ACCOUNT_NOT_FOUND);
        }
    }

    private void requireBusinessKey(String businessKey) {
        if (businessKey == null || businessKey.isBlank()) {
            throw new BusinessException(BillingErrorCode.AMOUNT_INVALID, "额度业务标识不能为空");
        }
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return 50;
        }
        return Math.min(limit, 200);
    }

    private String normalizeRemark(String remark) {
        if (remark == null) {
            return null;
        }
        String trimmed = remark.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    public record CreditBalance(long balanceUnits, long reservedUnits) {

        public long availableUnits() {
            return balanceUnits - reservedUnits;
        }
    }
}
