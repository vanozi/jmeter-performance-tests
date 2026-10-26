package common;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonTemplatesTest {
  private static final String TEMPLATE = "example/requests/template-test.json";

  @Test
  void rendersTypedValuesAndEscapesStringsWithoutMutatingTemplate() {
    Map<String, String> data = Map.of("NAME", "a\"b\\c\n", "COUNT", "3", "ACTIVE", "true");
    JsonNode first = Json.read(JsonTemplates.render(TEMPLATE, data::get), JsonNode.class);
    assertThat(first.get("name").asText()).isEqualTo(data.get("NAME"));
    assertThat(first.get("count").isNumber()).isTrue();
    assertThat(first.get("count").intValue()).isEqualTo(3);
    assertThat(first.get("active").booleanValue()).isTrue();
    assertThat(first.at("/nested/0").asText()).isEqualTo(data.get("NAME"));
    Map<String, String> next = Map.of("NAME", "next", "COUNT", "4", "ACTIVE", "false");
    JsonNode second = Json.read(JsonTemplates.render(TEMPLATE, next::get), JsonNode.class);
    assertThat(second.get("name").asText()).isEqualTo("next");
    assertThat(second.get("active").booleanValue()).isFalse();
    assertThat(second.get("id").asText()).isNotEqualTo(first.get("id").asText());
    UUID.fromString(second.get("id").asText());
    Instant.parse(second.get("time").asText());
  }

  @Test
  void rejectsMissingVariablesAndInvalidTypes() {
    assertThatThrownBy(() -> JsonTemplates.render(TEMPLATE, name -> null))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NAME");
    Map<String, String> data = Map.of("NAME", "ok", "COUNT", "oops", "ACTIVE", "true");
    assertThatThrownBy(() -> JsonTemplates.render(TEMPLATE, data::get))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("COUNT");
    Map<String, String> badBool = Map.of("NAME", "ok", "COUNT", "2", "ACTIVE", "yes");
    assertThatThrownBy(() -> JsonTemplates.render(TEMPLATE, badBool::get))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ACTIVE");
    assertThatThrownBy(() -> JsonTemplates.render("missing.json", data::get))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("missing.json");
  }
}
