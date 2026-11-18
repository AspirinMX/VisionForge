package app.pagefault.visionforge.api.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import app.pagefault.visionforge.api.admin.dto.AdminOverviewResponse;

import lombok.RequiredArgsConstructor;

/**
 * Aggregates read-only operational counts across user, agent, media, and composition tables.
 */
@Service
@RequiredArgsConstructor
public class AdminOverviewService {

    private static final String OVERVIEW_SQL = """
            SELECT
                (SELECT COUNT(*) FROM user_account) AS total_users,
                (SELECT COUNT(*) FROM user_account WHERE status = 'ACTIVE') AS active_users,
                (SELECT COUNT(*) FROM user_account WHERE status = 'DISABLED') AS disabled_users,
                (SELECT COUNT(*) FROM conversation WHERE deleted_at IS NULL) AS active_conversations,
                (SELECT COUNT(*) FROM model_definition WHERE enabled = TRUE) AS enabled_models,
                (SELECT COUNT(*) FROM agent_run WHERE status IN ('PENDING', 'RUNNING')) AS active_agent_runs,
                (SELECT COUNT(*) FROM agent_run WHERE status = 'FAILED') AS failed_agent_runs,
                (SELECT COUNT(*) FROM generation_task WHERE status IN ('PENDING', 'SUBMITTING', 'RUNNING', 'ARCHIVING', 'PROCESSING')) AS active_generation_tasks,
                (SELECT COUNT(*) FROM generation_task WHERE status = 'ARCHIVE_FAILED') AS archive_failed_generation_tasks,
                (SELECT COUNT(*) FROM generation_task WHERE billing_status = 'PENDING_RECONCILIATION') AS pending_reconciliation_tasks,
                (SELECT COUNT(*) FROM composition_task WHERE status IN ('PENDING', 'VALIDATING', 'COMPOSING', 'UPLOADING', 'PUBLISHING')) AS active_composition_tasks,
                (SELECT COUNT(*) FROM composition_task WHERE status = 'FAILED') AS failed_composition_tasks,
                (SELECT COUNT(*) FROM workflow_start_request WHERE status = 'FAILED') AS failed_workflow_starts
            """;

    private final JdbcTemplate jdbcTemplate;

    public AdminOverviewResponse snapshot() {
        AdminOverviewResponse response = jdbcTemplate.queryForObject(
                OVERVIEW_SQL,
                (rs, rowNum) -> new AdminOverviewResponse(
                        rs.getLong("total_users"),
                        rs.getLong("active_users"),
                        rs.getLong("disabled_users"),
                        rs.getLong("active_conversations"),
                        rs.getLong("enabled_models"),
                        rs.getLong("active_agent_runs"),
                        rs.getLong("failed_agent_runs"),
                        rs.getLong("active_generation_tasks"),
                        rs.getLong("archive_failed_generation_tasks"),
                        rs.getLong("pending_reconciliation_tasks"),
                        rs.getLong("active_composition_tasks"),
                        rs.getLong("failed_composition_tasks"),
                        rs.getLong("failed_workflow_starts")));
        return response == null
                ? new AdminOverviewResponse(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                : response;
    }
}
