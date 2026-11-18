package app.pagefault.visionforge.agent.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import app.pagefault.visionforge.agent.error.AgentErrorCode;
import app.pagefault.visionforge.common.error.BusinessException;

/** A catalog of application-bundled skills. Model input can select only a registered name. */
public class SkillRegistry {

    private static final Pattern DOCUMENT = Pattern.compile("\\A---\\R(.*?)\\R---\\R(.*)\\z", Pattern.DOTALL);
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_FILE_BYTES = 16_384;

    private final Map<String, Skill> skills;

    public SkillRegistry(Resource... documents) throws IOException {
        var registered = new TreeMap<String, Skill>();
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        for (Resource document : documents) {
            Skill skill = read(document, yaml);
            if (registered.putIfAbsent(skill.name(), skill) != null) {
                throw new IllegalStateException("Duplicate skill: " + skill.name());
            }
        }
        skills = Collections.unmodifiableMap(registered);
    }

    public Skill require(String name) {
        Skill skill = name == null ? null : skills.get(name);
        if (skill == null) {
            throw new BusinessException(AgentErrorCode.SKILL_NOT_FOUND);
        }
        return skill;
    }

    public String promptCatalog() {
        if (skills.isEmpty()) {
            return "";
        }
        var catalog = new StringBuilder("\n可用创作 skill（使用前调用 load_skill 读取正文，同一轮复用已加载的内容）：\n");
        skills.values().forEach(skill -> catalog.append("- ").append(skill.name())
                .append("：").append(skill.description()).append('\n'));
        return catalog.toString();
    }

    private static Skill read(Resource document, Yaml yaml) throws IOException {
        byte[] bytes;
        try (var input = document.getInputStream()) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalStateException("Skill file exceeds size limit: " + document.getDescription());
        }
        var match = DOCUMENT.matcher(new String(bytes, StandardCharsets.UTF_8));
        if (!match.matches() || !(yaml.load(match.group(1)) instanceof Map<?, ?> metadata)
                || !(metadata.get("name") instanceof String name) || name.length() > 64
                || !NAME.matcher(name).matches()
                || !document.getURL().getPath().endsWith("/" + name + "/SKILL.md")
                || !(metadata.get("description") instanceof String description)
                || description.isBlank() || description.length() > 1024 || description.lines().count() != 1
                || match.group(2).isBlank()) {
            throw new IllegalStateException("Invalid SKILL.md: " + document.getDescription());
        }
        return new Skill(name, description.strip(), match.group(2).strip());
    }

    public record Skill(String name, String description, String instructions) {
    }
}
