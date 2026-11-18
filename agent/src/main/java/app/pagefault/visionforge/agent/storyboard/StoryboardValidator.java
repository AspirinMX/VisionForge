package app.pagefault.visionforge.agent.storyboard;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates the storyboard JSON before it becomes a conversation-file version. The storyboard is
 * both a creative document and the execution plan for per-shot generation and composition.
 */
@RequiredArgsConstructor
public class StoryboardValidator {

    private static final int MAX_SHOTS = 200;
    private static final int MAX_PROMPT_LENGTH = 8_000;
    private static final int MAX_DURATION_SECONDS = 60;
    private static final Pattern SHOT_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<String> REFERENCE_ROLES =
            Set.of("CHARACTER", "PRODUCT", "SCENE", "STYLE");

    private final ObjectMapper objectMapper;

    public StoryboardSummary validate(String content) {
        StoryboardDocument document = parse(content);
        if (document.schemaVersion() != 1) {
            throw invalid("schemaVersion 必须为 1");
        }
        if (document.title() == null || document.title().isBlank()) {
            throw invalid("分镜标题不能为空");
        }
        List<StoryboardShot> shots = document.shots();
        if (shots.isEmpty()) {
            throw invalid("分镜至少需要一个镜头");
        }
        if (shots.size() > MAX_SHOTS) {
            throw invalid("分镜镜头数量超过上限 " + MAX_SHOTS);
        }

        Set<String> ids = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (StoryboardShot shot : shots) {
            validateShot(shot, ids, orders);
        }
        int firstOrder = shots.stream().mapToInt(StoryboardShot::order).min().orElse(0);
        int lastOrder = shots.stream().mapToInt(StoryboardShot::order).max().orElse(0);
        return new StoryboardSummary(shots.size(), firstOrder, lastOrder);
    }

    public StoryboardDocument parse(String content) {
        if (content == null || content.isBlank()) {
            throw invalid("分镜内容不能为空");
        }
        try {
            StoryboardDocument document =
                    objectMapper.readValue(content, StoryboardDocument.class);
            if (document == null) {
                throw invalid("分镜内容不能为空");
            }
            return document;
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("分镜 JSON 无法解析：" + shortMessage(exception));
        }
    }

    private void validateShot(
            StoryboardShot shot, Set<String> ids, Set<Integer> orders) {
        if (shot == null) {
            throw invalid("分镜镜头不能为 null");
        }
        if (shot.id() == null || !SHOT_ID.matcher(shot.id()).matches()) {
            throw invalid("镜头 id 必须是 1-64 位字母、数字、下划线或连字符");
        }
        if (!ids.add(shot.id())) {
            throw invalid("镜头 id 重复：" + shot.id());
        }
        if (shot.order() < 1) {
            throw invalid("镜头 " + shot.id() + " 的 order 必须大于 0");
        }
        if (!orders.add(shot.order())) {
            throw invalid("镜头 order 重复：" + shot.order());
        }
        if (shot.durationSeconds() < 1 || shot.durationSeconds() > MAX_DURATION_SECONDS) {
            throw invalid("镜头 " + shot.id() + " 的时长必须在 1 到 "
                    + MAX_DURATION_SECONDS + " 秒之间");
        }
        requirePrompt(shot.firstFramePrompt(), shot.id(), "firstFramePrompt");
        requirePrompt(shot.videoPrompt(), shot.id(), "videoPrompt");
        if (shot.shotSize() == null || shot.camera() == null || shot.status() == null) {
            throw invalid("镜头 " + shot.id() + " 缺少 shotSize、camera 或 status");
        }
        for (StoryboardReference reference : shot.references()) {
            if (reference == null || reference.assetId() == null || reference.version() < 1) {
                throw invalid("镜头 " + shot.id() + " 的参考素材缺少 assetId 或版本");
            }
            if (!REFERENCE_ROLES.contains(reference.role())) {
                throw invalid("镜头 " + shot.id() + " 的参考角色不合法：" + reference.role());
            }
        }
        validateAssetRef(shot.firstFrame(), shot.id(), "firstFrame");
        Set<java.util.UUID> candidateTasks = new HashSet<>();
        for (StoryboardVideoCandidate candidate : shot.videoCandidates()) {
            if (candidate == null || candidate.taskId() == null) {
                throw invalid("镜头 " + shot.id() + " 的视频候选缺少 taskId");
            }
            if (!candidateTasks.add(candidate.taskId())) {
                throw invalid("镜头 " + shot.id() + " 的视频候选 taskId 重复");
            }
            if (candidate.status() == null || candidate.status().isBlank()) {
                throw invalid("镜头 " + shot.id() + " 的视频候选缺少状态");
            }
        }
        if (shot.selectedVideo() != null) {
            StoryboardVideoSelection selected = shot.selectedVideo();
            if (selected.taskId() == null
                    || selected.assetId() == null
                    || selected.version() < 1) {
                throw invalid("镜头 " + shot.id() + " 的 selectedVideo 缺少 taskId、assetId 或版本");
            }
            if (!shot.videoCandidates().isEmpty()
                    && shot.videoCandidates().stream()
                            .noneMatch(candidate -> candidate.taskId()
                                    .equals(selected.taskId()))) {
                throw invalid("镜头 " + shot.id() + " 的 selectedVideo 不在 videoCandidates 中");
            }
        }
        if (shot.usedRange() != null) {
            BigDecimal start = shot.usedRange().startSeconds();
            BigDecimal end = shot.usedRange().endSeconds();
            if (start == null || end == null
                    || start.signum() < 0
                    || end.compareTo(start) <= 0
                    || end.compareTo(BigDecimal.valueOf(shot.durationSeconds())) > 0) {
                throw invalid("镜头 " + shot.id() + " 的 usedRange 不合法");
            }
        }
    }

    private void validateAssetRef(StoryboardAssetRef reference, String shotId, String field) {
        if (reference == null) {
            return;
        }
        if (reference.assetId() == null || reference.version() < 1) {
            throw invalid("镜头 " + shotId + " 的 " + field + " 缺少 assetId 或版本");
        }
    }

    private void requirePrompt(String value, String shotId, String field) {
        if (value == null || value.isBlank()) {
            throw invalid("镜头 " + shotId + " 缺少 " + field);
        }
        if (value.length() > MAX_PROMPT_LENGTH) {
            throw invalid("镜头 " + shotId + " 的 " + field + " 超过长度上限");
        }
    }

    private BusinessException invalid(String message) {
        return new BusinessException(AgentErrorCode.STORYBOARD_INVALID, message);
    }

    private String shortMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }
}
