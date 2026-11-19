package app.pagefault.visionforge.api.admin;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToLongFunction;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import app.pagefault.visionforge.api.admin.dto.AdminDashboardResponse;
import app.pagefault.visionforge.api.admin.dto.AdminOverviewResponse;

import lombok.RequiredArgsConstructor;

/**
 * Builds the admin operations dashboard from read-only aggregate queries.
 *
 * <p>Days are bucketed in {@link #ANALYTICS_ZONE} so a Beijing operator sees local
 * calendar days rather than UTC boundaries.</p>
 */
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final ZoneId ANALYTICS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int LEADERBOARD_LIMIT = 8;
    private static final int DEFAULT_RANGE_DAYS = 30;

    private static final String TASK_SUCCESS_STATUSES =
            "('SUCCEEDED', 'PARTIAL_SUCCEEDED')";
    private static final String TASK_FAILURE_STATUSES =
            "('FAILED', 'ARCHIVE_FAILED', 'DELIVERY_FAILED')";

    private final JdbcTemplate jdbcTemplate;
    private final AdminOverviewService adminOverviewService;
    private final Clock clock;

    public AdminDashboardResponse dashboard(int requestedDays) {
        int rangeDays = normalizeRange(requestedDays);
        Instant now = clock.instant();
        LocalDate today = now.atZone(ANALYTICS_ZONE).toLocalDate();
        LocalDate firstDay = today.minusDays(rangeDays - 1L);
        Instant start = firstDay.atStartOfDay(ANALYTICS_ZONE).toInstant();
        Instant endExclusive = today.plusDays(1).atStartOfDay(ANALYTICS_ZONE).toInstant();
        Instant previousStart = start.minus(rangeDays, ChronoUnit.DAYS);

        Map<LocalDate, MutableDaily> daily = emptyDaily(firstDay, rangeDays);
        loadDailyCredits(daily, start, endExclusive);
        loadDailyTokens(daily, start, endExclusive);
        loadDailyTasks(daily, start, endExclusive);

        Map<LocalDate, MutableDaily> previousDaily =
                emptyDaily(firstDay.minusDays(rangeDays), rangeDays);
        loadDailyCredits(previousDaily, previousStart, start);
        loadDailyTokens(previousDaily, previousStart, start);
        loadDailyTasks(previousDaily, previousStart, start);

        long settledCredits = sum(daily, row -> row.settledCredits);
        long inputTokens = sum(daily, row -> row.inputTokens);
        long outputTokens = sum(daily, row -> row.outputTokens);
        TaskTotals currentTasks = taskTotals(daily);
        Double successRate = successRate(currentTasks);

        TaskTotals previousTasks = taskTotals(previousDaily);
        long activeUsers = activeUsers(start, endExclusive);
        long previousActiveUsers = activeUsers(previousStart, start);

        AdminOverviewResponse snapshot = adminOverviewService.snapshot();
        Instant lastDay = now.minus(24, ChronoUnit.HOURS);
        long failedTasks24h = failedTasks(lastDay);

        return new AdminDashboardResponse(
                rangeDays,
                now,
                new AdminDashboardResponse.Totals(
                        settledCredits,
                        todayCredits(daily, today),
                        inputTokens,
                        outputTokens,
                        successRate,
                        activeUsers,
                        newUsers(start, endExclusive)),
                new AdminDashboardResponse.Deltas(
                        relativeDelta(settledCredits,
                                sum(previousDaily, row -> row.settledCredits)),
                        relativeDelta(inputTokens + outputTokens,
                                sum(previousDaily, row -> row.inputTokens)
                                        + sum(previousDaily, row -> row.outputTokens)),
                        relativeDelta(activeUsers, previousActiveUsers),
                        relativePointDelta(successRate, successRate(previousTasks))),
                daily.values().stream()
                        .map(row -> new AdminDashboardResponse.DailyPoint(
                                row.date,
                                row.inputTokens,
                                row.outputTokens,
                                row.invocations,
                                row.settledCredits,
                                row.succeeded,
                                row.failed,
                                row.cancelled))
                        .toList(),
                topUsersByCredits(start, endExclusive),
                topUsersByTokens(start, endExclusive),
                new AdminDashboardResponse.Attention(
                        snapshot.archiveFailedGenerationTasks(),
                        snapshot.pendingReconciliationTasks(),
                        snapshot.failedWorkflowStarts(),
                        failedTasks24h,
                        snapshot.failedCompositionTasks()),
                new AdminDashboardResponse.Live(
                        snapshot.activeAgentRuns(),
                        snapshot.activeGenerationTasks(),
                        snapshot.activeCompositionTasks()));
    }

    private void loadDailyCredits(
            Map<LocalDate, MutableDaily> daily, Instant start, Instant endExclusive) {
        jdbcTemplate.query("""
                SELECT (created_at AT TIME ZONE 'Asia/Shanghai')::date AS day,
                       COALESCE(SUM(amount_units), 0) AS credits
                  FROM credit_ledger
                 WHERE entry_type = 'SETTLE'
                   AND created_at >= ?
                   AND created_at < ?
                 GROUP BY day
                """,
                rs -> {
                    MutableDaily row = daily.get(rs.getDate("day").toLocalDate());
                    if (row != null) {
                        row.settledCredits = rs.getLong("credits");
                    }
                },
                timestamp(start),
                timestamp(endExclusive));
    }

    private void loadDailyTokens(
            Map<LocalDate, MutableDaily> daily, Instant start, Instant endExclusive) {
        jdbcTemplate.query("""
                SELECT (created_at AT TIME ZONE 'Asia/Shanghai')::date AS day,
                       COALESCE(SUM(input_tokens) FILTER (WHERE status = 'SUCCEEDED'), 0) AS input_tokens,
                       COALESCE(SUM(output_tokens) FILTER (WHERE status = 'SUCCEEDED'), 0) AS output_tokens,
                       COUNT(*) FILTER (WHERE status = 'SUCCEEDED') AS invocations
                  FROM model_invocation
                 WHERE created_at >= ?
                   AND created_at < ?
                 GROUP BY day
                """,
                rs -> {
                    MutableDaily row = daily.get(rs.getDate("day").toLocalDate());
                    if (row != null) {
                        row.inputTokens = rs.getLong("input_tokens");
                        row.outputTokens = rs.getLong("output_tokens");
                        row.invocations = rs.getLong("invocations");
                    }
                },
                timestamp(start),
                timestamp(endExclusive));
    }

    private void loadDailyTasks(
            Map<LocalDate, MutableDaily> daily, Instant start, Instant endExclusive) {
        jdbcTemplate.query("""
                SELECT (COALESCE(completed_at, created_at) AT TIME ZONE 'Asia/Shanghai')::date AS day,
                       COUNT(*) FILTER (WHERE status IN %s) AS succeeded,
                       COUNT(*) FILTER (WHERE status IN %s) AS failed,
                       COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled
                  FROM generation_task
                 WHERE COALESCE(completed_at, created_at) >= ?
                   AND COALESCE(completed_at, created_at) < ?
                 GROUP BY day
                """.formatted(TASK_SUCCESS_STATUSES, TASK_FAILURE_STATUSES),
                rs -> {
                    MutableDaily row = daily.get(rs.getDate("day").toLocalDate());
                    if (row != null) {
                        row.succeeded = rs.getLong("succeeded");
                        row.failed = rs.getLong("failed");
                        row.cancelled = rs.getLong("cancelled");
                    }
                },
                timestamp(start),
                timestamp(endExclusive));
    }

    private TaskTotals taskTotals(Map<LocalDate, MutableDaily> daily) {
        long succeeded = sum(daily, row -> row.succeeded);
        long failed = sum(daily, row -> row.failed);
        long cancelled = sum(daily, row -> row.cancelled);
        return new TaskTotals(succeeded, failed, cancelled);
    }

    private static long sum(
            Map<LocalDate, MutableDaily> daily, ToLongFunction<MutableDaily> selector) {
        return daily.values().stream().mapToLong(selector).sum();
    }

    private long activeUsers(Instant start, Instant endExclusive) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM (
                        SELECT c.created_by AS user_id
                          FROM conversation c
                         WHERE c.deleted_at IS NULL
                           AND c.created_by IS NOT NULL
                           AND c.last_message_at >= ?
                           AND c.last_message_at < ?
                        UNION
                        SELECT g.user_id
                          FROM generation_task g
                         WHERE g.created_at >= ?
                           AND g.created_at < ?
                       ) activity
                """, Long.class,
                timestamp(start),
                timestamp(endExclusive),
                timestamp(start),
                timestamp(endExclusive));
        return value == null ? 0 : value;
    }

    private long newUsers(Instant start, Instant endExclusive) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM user_account
                 WHERE created_at >= ?
                   AND created_at < ?
                """, Long.class, timestamp(start), timestamp(endExclusive));
        return value == null ? 0 : value;
    }

    private List<AdminDashboardResponse.LeaderboardEntry> topUsersByCredits(
            Instant start, Instant endExclusive) {
        return jdbcTemplate.query("""
                SELECT l.user_id,
                       COALESCE(NULLIF(u.display_name, ''), u.email) AS display_name,
                       COALESCE(SUM(l.amount_units), 0) AS value
                  FROM credit_ledger l
                  JOIN user_account u ON u.id = l.user_id
                 WHERE l.entry_type = 'SETTLE'
                   AND l.created_at >= ?
                   AND l.created_at < ?
                 GROUP BY l.user_id, u.display_name, u.email
                 ORDER BY value DESC, l.user_id
                 LIMIT ?
                """,
                (rs, rowNum) -> new AdminDashboardResponse.LeaderboardEntry(
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("display_name"),
                        rs.getLong("value")),
                timestamp(start),
                timestamp(endExclusive),
                LEADERBOARD_LIMIT);
    }

    private List<AdminDashboardResponse.LeaderboardEntry> topUsersByTokens(
            Instant start, Instant endExclusive) {
        return jdbcTemplate.query("""
                SELECT c.created_by AS user_id,
                       COALESCE(NULLIF(u.display_name, ''), u.email) AS display_name,
                       COALESCE(SUM(COALESCE(mi.input_tokens, 0)
                                    + COALESCE(mi.output_tokens, 0)), 0) AS value
                  FROM model_invocation mi
                  JOIN agent_run ar ON ar.id = mi.agent_run_id
                  JOIN conversation c ON c.id = ar.conversation_id
                  JOIN user_account u ON u.id = c.created_by
                 WHERE mi.status = 'SUCCEEDED'
                   AND c.created_by IS NOT NULL
                   AND mi.created_at >= ?
                   AND mi.created_at < ?
                 GROUP BY c.created_by, u.display_name, u.email
                 ORDER BY value DESC, c.created_by
                 LIMIT ?
                """,
                (rs, rowNum) -> new AdminDashboardResponse.LeaderboardEntry(
                        UUID.fromString(rs.getString("user_id")),
                        rs.getString("display_name"),
                        rs.getLong("value")),
                timestamp(start),
                timestamp(endExclusive),
                LEADERBOARD_LIMIT);
    }

    private long failedTasks(Instant since) {
        Long value = jdbcTemplate.queryForObject("""
                SELECT
                    (SELECT COUNT(*)
                       FROM generation_task
                      WHERE status IN ('FAILED', 'ARCHIVE_FAILED', 'DELIVERY_FAILED')
                        AND COALESCE(completed_at, created_at) >= ?)
                    +
                    (SELECT COUNT(*)
                       FROM composition_task
                      WHERE status = 'FAILED'
                        AND COALESCE(completed_at, created_at) >= ?)
                """, Long.class, timestamp(since), timestamp(since));
        return value == null ? 0 : value;
    }

    private static long todayCredits(Map<LocalDate, MutableDaily> daily, LocalDate today) {
        MutableDaily row = daily.get(today);
        return row == null ? 0 : row.settledCredits;
    }

    private static Map<LocalDate, MutableDaily> emptyDaily(LocalDate firstDay, int rangeDays) {
        Map<LocalDate, MutableDaily> daily = new LinkedHashMap<>();
        for (int index = 0; index < rangeDays; index++) {
            LocalDate date = firstDay.plusDays(index);
            daily.put(date, new MutableDaily(date));
        }
        return daily;
    }

    private static Double successRate(TaskTotals totals) {
        long finished = totals.succeeded + totals.failed + totals.cancelled;
        return finished == 0 ? null : (double) totals.succeeded / finished;
    }

    private static Double relativeDelta(long current, long previous) {
        return previous == 0 ? null : (double) (current - previous) / previous;
    }

    private static Double relativePointDelta(Double current, Double previous) {
        return current == null || previous == null ? null : current - previous;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private static int normalizeRange(int days) {
        return days == 7 || days == 30 || days == 90 ? days : DEFAULT_RANGE_DAYS;
    }

    private record TaskTotals(long succeeded, long failed, long cancelled) {
    }

    private static final class MutableDaily {

        private final LocalDate date;
        private long inputTokens;
        private long outputTokens;
        private long invocations;
        private long settledCredits;
        private long succeeded;
        private long failed;
        private long cancelled;

        private MutableDaily(LocalDate date) {
            this.date = date;
        }
    }
}
