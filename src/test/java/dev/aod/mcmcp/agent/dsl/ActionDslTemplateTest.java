package dev.aod.mcmcp.agent.dsl;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ActionDslTemplateTest {
    @Test
    void everyPublishedTemplateUsesTheStrictDslSchema() throws Exception {
        var projectDirectory = Path.of(System.getProperty("mcmcp.projectDir"));
        var templateDirectory = projectDirectory.resolve("docs/action-templates");
        var catalog = JsonParser.parseString(Files.readString(
                projectDirectory.resolve("docs/MCMCP_MCP_Tool_Catalog.json")))
                .getAsJsonObject();
        var examples = StreamSupport.stream(
                        catalog.getAsJsonArray("tools").spliterator(), false)
                .map(JsonElement::getAsJsonObject)
                .filter(tool -> tool.get("name").getAsString().equals("agent_start_action"))
                .findFirst().orElseThrow()
                .getAsJsonObject("inputSchema")
                .getAsJsonArray("examples");
        try (var files = Files.list(templateDirectory)) {
            var templates = files.filter(path -> path.toString().endsWith(".json")).toList();
            assertThat(templates).hasSize(5);
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
