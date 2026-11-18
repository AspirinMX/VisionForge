package app.pagefault.visionforge.billing.credit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import app.pagefault.visionforge.billing.config.BillingProperties;
import app.pagefault.visionforge.billing.error.BillingErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;
import app.pagefault.visionforge.common.id.IdGenerator;

class CreditServiceTest {

    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e34");
    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    private CreditAccountMapper accountMapper;
    private CreditReservationMapper reservationMapper;
    private CreditLedgerMapper ledgerMapper;
    private CreditService service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), CreditAccount.class);
        accountMapper = mock(CreditAccountMapper.class);
        reservationMapper = mock(CreditReservationMapper.class);
        ledgerMapper = mock(CreditLedgerMapper.class);
        AtomicLong sequence = new AtomicLong();
        IdGenerator idGenerator = () -> new UUID(0, sequence.incrementAndGet());
        service = new CreditService(
                accountMapper,
                reservationMapper,
                ledgerMapper,
                idGenerator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new BillingProperties(1_000));
        when(accountMapper.selectById(USER_ID)).thenReturn(account(100, 0));
        when(reservationMapper.selectOne(any())).thenReturn(null);
        when(reservationMapper.insertIfAbsent(any())).thenReturn(1);
    }

    @Test
    void reserveFailsWhenAvailableCreditIsTooLow() {
        when(accountMapper.update(any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.reserve(USER_ID, "generation:1", 200, null))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(BillingErrorCode.INSUFFICIENT_CREDIT));
    }

    @Test
    void reserveIsIdempotentForTheSameBusinessKey() {
        CreditReservation existing = reservation(CreditReservationStatus.RESERVED, 50);
        when(reservationMapper.selectOne(any())).thenReturn(existing);

        CreditReservation result = service.reserve(USER_ID, "generation:1", 50, null);

        assertThat(result).isSameAs(existing);
        verify(reservationMapper, never()).insertIfAbsent(any());
    }

    @Test
    void reserveDeductsAvailabilityAndWritesLedger() {
        when(accountMapper.update(any(), any())).thenReturn(1);

        CreditReservation reservation = service.reserve(USER_ID, "generation:1", 50, null);

        assertThat(reservation.getStatus()).isEqualTo(CreditReservationStatus.RESERVED);
        verify(ledgerMapper).insertIfAbsent(any(CreditLedger.class));
    }

    @Test
    void settleChargesTheReservedAmountOnce() {
        CreditReservation reservation = reservation(CreditReservationStatus.RESERVED, 40);
        when(reservationMapper.selectOne(any())).thenReturn(reservation);
        when(accountMapper.update(any(), any())).thenReturn(1);

        CreditReservation settled = service.settle(USER_ID, "generation:1");

        assertThat(settled.getStatus()).isEqualTo(CreditReservationStatus.SETTLED);
        assertThat(settled.getSettledAt()).isEqualTo(NOW);
        verify(ledgerMapper).insertIfAbsent(any(CreditLedger.class));
    }

    @Test
    void accountReturnsThePersistedBalance() {
        CreditAccount result = service.account(USER_ID);

        assertThat(result.getBalanceUnits()).isEqualTo(100);
        assertThat(result.getReservedUnits()).isZero();
        verify(accountMapper, never()).insertIfAbsent(any(), anyLong(), any());
    }

    @Test
    void reservationsSkipsTheQueryForAnEmptyIdList() {
        assertThat(service.reservations(USER_ID, List.of())).isEmpty();
        assertThat(service.reservations(USER_ID, null)).isEmpty();
        verify(reservationMapper, never()).selectList(any());
    }

    @Test
    void recentEntriesRequiresAUser() {
        assertThatThrownBy(() -> service.recentEntries(null, 10, null))
                .isInstanceOf(BusinessException.class);
    }

    private CreditAccount account(long balance, long reserved) {
        CreditAccount account = new CreditAccount();
        account.setUserId(USER_ID);
        account.setBalanceUnits(balance);
        account.setReservedUnits(reserved);
        return account;
    }

    private CreditReservation reservation(CreditReservationStatus status, long amount) {
        CreditReservation reservation = new CreditReservation();
        reservation.setId(UUID.randomUUID());
        reservation.setUserId(USER_ID);
        reservation.setBusinessKey("generation:1");
        reservation.setAmountUnits(amount);
        reservation.setStatus(status);
        reservation.setCreatedAt(NOW);
        return reservation;
    }
}
