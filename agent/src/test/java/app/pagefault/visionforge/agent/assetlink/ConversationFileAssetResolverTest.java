package app.pagefault.visionforge.agent.assetlink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;

import app.pagefault.visionforge.agent.composition.CompositionTask;
import app.pagefault.visionforge.agent.composition.CompositionTaskMapper;
import app.pagefault.visionforge.agent.generation.GenerationOutput;
import app.pagefault.visionforge.agent.generation.GenerationOutputMapper;
import app.pagefault.visionforge.asset.library.ConversationFileVersion;
import app.pagefault.visionforge.asset.media.AssetService;

class ConversationFileAssetResolverTest {

    private static final UUID USER_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e34");
    private static final UUID CONVERSATION_ID =
            UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e30");
    private static final UUID VERSION_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e40");
    private static final UUID ASSET_ID = UUID.fromString("0193b2c4-7f2a-7c3e-9d10-6a8b5c4d3e41");

    private GenerationOutputMapper outputMapper;
    private CompositionTaskMapper compositionTaskMapper;
    private AssetService assetService;
    private ConversationFileAssetResolver resolver;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ConversationFileVersion.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                GenerationOutput.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CompositionTask.class);
        outputMapper = mock(GenerationOutputMapper.class);
        compositionTaskMapper = mock(CompositionTaskMapper.class);
        assetService = mock(AssetService.class);
        resolver = new ConversationFileAssetResolver(
                outputMapper, compositionTaskMapper, assetService);
    }

    @Test
    void usesTheVersionAssetIdWhenPresent() {
        when(assetService.filterOwned(any(), any(), anyCollection()))
                .thenReturn(Set.of(ASSET_ID));

        Map<UUID, UUID> result = resolver.resolve(
                USER_ID, CONVERSATION_ID, List.of(version(ASSET_ID)));

        assertThat(result).containsEntry(VERSION_ID, ASSET_ID);
        verify(outputMapper, never()).selectList(any());
        verify(compositionTaskMapper, never()).selectList(any());
    }

    @Test
    void fallsBackToTheGenerationOutput() {
        when(assetService.filterOwned(any(), any(), anyCollection()))
                .thenReturn(Set.of(ASSET_ID));
        GenerationOutput output = new GenerationOutput();
        output.setFileVersionId(VERSION_ID);
        output.setAssetId(ASSET_ID);
        when(outputMapper.selectList(any())).thenReturn(List.of(output));

        Map<UUID, UUID> result = resolver.resolve(
                USER_ID, CONVERSATION_ID, List.of(version(null)));

        assertThat(result).containsEntry(VERSION_ID, ASSET_ID);
        verify(compositionTaskMapper, never()).selectList(any());
    }

    @Test
    void fallsBackToTheCompositionTask() {
        when(assetService.filterOwned(any(), any(), anyCollection()))
                .thenReturn(Set.of(ASSET_ID));
        CompositionTask task = new CompositionTask();
        task.setOutputVersionId(VERSION_ID);
        task.setOutputAssetId(ASSET_ID);
        when(compositionTaskMapper.selectList(any())).thenReturn(List.of(task));

        Map<UUID, UUID> result = resolver.resolve(
                USER_ID, CONVERSATION_ID, List.of(version(null)));

        assertThat(result).containsEntry(VERSION_ID, ASSET_ID);
    }

    @Test
    void dropsAssetsOutsideTheConversation() {
        when(assetService.filterOwned(any(), any(), anyCollection())).thenReturn(Set.of());

        assertThat(resolver.resolve(
                USER_ID, CONVERSATION_ID, List.of(version(ASSET_ID)))).isEmpty();
    }

    @Test
    void returnsAnEmptyMapWithoutVersions() {
        assertThat(resolver.resolve(USER_ID, CONVERSATION_ID, List.of())).isEmpty();
        assertThat(resolver.resolve(USER_ID, CONVERSATION_ID, null)).isEmpty();
        verify(assetService, never()).filterOwned(any(), any(), anyCollection());
    }

    private ConversationFileVersion version(UUID assetId) {
        ConversationFileVersion version = new ConversationFileVersion();
        version.setId(VERSION_ID);
        version.setVersion(1);
        version.setAssetId(assetId);
        return version;
    }
}
