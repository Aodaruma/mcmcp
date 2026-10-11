package dev.aod.mcmcp.agent.dsl;

import dev.aod.mcmcp.mcp.LegacyActionSchema;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ActionDslTemplateTest {
    @Test
    void legacyTemplatesRemainValidForTheInternalExecutor() throws Exception {
        var projectDirectory = Path.of(System.getProperty("mcmcp.projectDir"));
        var templateDirectory = projectDirectory.resolve("docs/action-templates");
        var examples = LegacyActionSchema.inputSchema().getAsJsonArray("examples");
        try (var files = Files.list(templateDirectory)) {
            var templates = files.filter(path -> path.toString().endsWith(".json")).toList();
            assertThat(templates).hasSize(13);
            for (var template : templates) {
                String json = Files.readString(template);
                assertThat(ActionDslParser.parse(json).schemaVersion())
                        .as(template.toString())
                        .isEqualTo(1);
                assertThat(examples).as(template.toString())
                        .contains(JsonParser.parseString(json));
            }
        }
    }
}
