package app.pagefault.visionforge.agent.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.agent.tool.skill.LoadSkillTool;
import app.pagefault.visionforge.common.error.BusinessException;

import tools.jackson.databind.ObjectMapper;

class SkillRegistryTest {

    private final ClassPathResource shortFilm =
            new ClassPathResource("visionforge/skills/short-film/SKILL.md");

    @Test
    void advertisesMetadataAndLoadsBodyThroughReadOnlyTool() throws IOException {
        SkillRegistry registry = new SkillRegistry(shortFilm);
        ObjectMapper mapper = new ObjectMapper();
        LoadSkillTool tool = new LoadSkillTool(registry, mapper);

        assertThat(registry.promptCatalog()).contains("short-film").doesNotContain("save_storyboard");
        var result = tool.execute(null, mapper.createObjectNode().put("name", "short-film"));
        assertThat(result.success()).isTrue();
        assertThat(result.content()).contains("save_storyboard", "firstFramePrompt").doesNotContain("description:");
    }

    @Test
    void rejectsUnknownNamesAndDuplicateRegistrations() throws IOException {
        SkillRegistry registry = new SkillRegistry(shortFilm);
        assertThatThrownBy(() -> registry.require("../short-film/SKILL.md"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(AgentErrorCode.SKILL_NOT_FOUND));
        assertThatThrownBy(() -> new SkillRegistry(shortFilm, shortFilm))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate skill: short-film");
    }
}
