package common;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.Period;
import org.junit.jupiter.api.Test;

class RandomTemplateValuesTest {
  @Test
  void generatesRequestedLengthsAndCharacterSets() {
    for (int length : new int[]{1, 12, 256}) {
      assertThat(RandomTemplateValues.generate("randomString:" + length))
          .hasSize(length).matches("[A-Za-z]+");
      assertThat(RandomTemplateValues.generate("randomDigits:" + length))
          .hasSize(length).matches("[0-9]+");
    }
    assertThat(RandomTemplateValues.generate("CUSTOMER_ID")).isNull();
  }

  @Test
  void birthDatesRespectInclusiveCompletedAges() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    for (String expression : new String[]{"geboorteDatum", "geboorteDatum:18:65", "geboorteDatum:0:0",
        "geboorteDatum:65:65"}) {
      int min = expression.equals("geboorteDatum") ? 18 : Integer.parseInt(expression.split(":")[1]);
      int max = expression.equals("geboorteDatum") ? 80 : Integer.parseInt(expression.split(":")[2]);
      for (int i = 0; i < 100; i++) {
        LocalDate date = LocalDate.parse(RandomTemplateValues.generate(expression));
        assertThat(date).isBetween(today.minusYears(max + 1L).plusDays(1), today.minusYears(min));
        assertThat(Period.between(date, today).getYears()).isBetween(min, max);
      }
    }
  }

  @Test
  void rejectsInvalidGeneratorArguments() {
    for (String expression : new String[]{"randomString", "randomString:0", "randomString:-1",
        "randomString:10001", "randomString:abc", "randomDigits:2:3", "randomDigits:0",
        "randomDigits:", "geboorteDatum:18", "geboorteDatum:65:18", "geboorteDatum:-1:18",
        "geboorteDatum:0:151", "geboorteDatum:a:b", "geboorteDatum:18:65:80"}) {
      assertThatThrownBy(() -> RandomTemplateValues.generate(expression))
          .as(expression).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining(expression);
    }
  }

  @Test
  void templatesGenerateFreshStringValuesOnEachRendering() {
    String template = "example/requests/random-values-test.json";
    JsonNode first = Json.read(JsonTemplates.render(template, name -> null), JsonNode.class);
    JsonNode second = Json.read(JsonTemplates.render(template, name -> null), JsonNode.class);
    assertThat(first.get("name").asText()).matches("[A-Za-z]{12}");
    assertThat(first.get("digits").isTextual()).isTrue();
    assertThat(first.get("digits").asText()).matches("[0-9]{8}");
    assertThat(first.get("name").asText()).isNotEqualTo(second.get("name").asText());
    LocalDate.parse(first.get("birth_date").asText());
    LocalDate.parse(first.at("/nested/0").asText());
  }
}
