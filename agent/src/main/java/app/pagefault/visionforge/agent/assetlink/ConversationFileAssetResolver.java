package app.pagefault.visionforge.agent.assetlink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import app.pagefault.visionforge.agent.composition.CompositionTask;
import app.pagefault.visionforge.agent.composition.CompositionTaskMapper;
import app.pagefault.visionforge.agent.generation.GenerationOutput;
import app.pagefault.visionforge.agent.generation.GenerationOutputMapper;
import app.pagefault.visionforge.asset.library.ConversationFileVersion;
import app.pagefault.visionforge.asset.media.AssetService;

import lombok.RequiredArgsConstructor;

/**
 * Resolves the asset behind a conversation file version.
 *
 * <p>The version row stores the asset id for every media published by this codebase. Older rows and
 * rows written by other paths may lack it, so the resolver falls back to the generation output and
 * then the composition task that produced the version. Candidates are finally checked against the
 * caller's conversation, so a stale or forged id can never expose another user's asset.
 */
@RequiredArgsConstructor
public class ConversationFileAssetResolver {

    private final GenerationOutputMapper outputMapper;
    private final CompositionTaskMapper compositionTaskMapper;
    private final AssetService assetService;

    public Map<UUID, UUID> resolve(
            UUID userId,
            UUID conversationId,
            List<ConversationFileVersion> versions) {
        if (versions == null || versions.isEmpty()) {
            return Map.of();
        }
        Map<UUID, UUID> resolved = new LinkedHashMap<>();
        List<UUID> unresolved = new ArrayList<>();
        for (ConversationFileVersion version : versions) {
            if (version.getAssetId() == null) {
                unresolved.add(version.getId());
            } else {
                resolved.put(version.getId(), version.getAssetId());
            }
        }
        resolveFromGenerationOutputs(unresolved, resolved);
        List<UUID> remaining = unresolved.stream()
                .filter(id -> !resolved.containsKey(id))
                .toList();
        resolveFromCompositions(remaining, resolved);
        if (resolved.isEmpty()) {
            return Map.of();
        }
        Set<UUID> owned =
                assetService.filterOwned(userId, conversationId, resolved.values());
        return resolved.entrySet().stream()
                .filter(entry -> owned.contains(entry.getValue()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private void resolveFromGenerationOutputs(
            List<UUID> versionIds, Map<UUID, UUID> resolved) {
        if (versionIds.isEmpty()) {
            return;
        }
        List<GenerationOutput> outputs = outputMapper.selectList(
                Wrappers.<GenerationOutput>lambdaQuery()
                        .in(GenerationOutput::getFileVersionId, versionIds)
                        .isNotNull(GenerationOutput::getAssetId));
        for (GenerationOutput output : outputs) {
            if (output.getFileVersionId() != null && output.getAssetId() != null) {
                resolved.putIfAbsent(output.getFileVersionId(), output.getAssetId());
            }
        }
    }

    private void resolveFromCompositions(
            List<UUID> versionIds, Map<UUID, UUID> resolved) {
        if (versionIds.isEmpty()) {
            return;
        }
        List<CompositionTask> tasks = compositionTaskMapper.selectList(
                Wrappers.<CompositionTask>lambdaQuery()
                        .in(CompositionTask::getOutputVersionId, versionIds)
                        .isNotNull(CompositionTask::getOutputAssetId));
        for (CompositionTask task : tasks) {
            if (task.getOutputVersionId() != null && task.getOutputAssetId() != null) {
                resolved.putIfAbsent(task.getOutputVersionId(), task.getOutputAssetId());
            }
        }
    }
}
