package common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/** Cached JSON structures with whole-value placeholders resolved once per request. */
public final class JsonTemplates {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ZoneId DATE_ZONE = ZoneId.of("Europe/Amsterdam");
  private static final DateTimeFormatter DATE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx");
  private static final ConcurrentMap<String, JsonNode> TEMPLATES = new ConcurrentHashMap<>();

  private JsonTemplates() {
  }

  public static String render(String resourcePath, Function<String, String> variables) {
    if (resourcePath == null || resourcePath.isBlank()) {
      throw new IllegalArgumentException("Missing JSON template path");
    }
    JsonNode copy = TEMPLATES.computeIfAbsent(resourcePath, JsonTemplates::load).deepCopy();
    return Json.write(resolve(copy, variables));
  }

  private static JsonNode load(String path) {
    try (InputStream input = JsonTemplates.class.getClassLoader().getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalArgumentException("JSON template not found: " + path);
      }
      JsonNode node = MAPPER.readTree(input);
      if (node == null) {
        throw new IllegalArgumentException("Empty JSON template: " + path);
      }
      return node;
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read JSON template: " + path, e);
    }
  }

  private static JsonNode resolve(JsonNode node, Function<String, String> variables) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      object.fieldNames().forEachRemaining(key -> object.set(key, resolve(object.get(key), variables)));
    } else if (node.isArray()) {
      ArrayNode array = (ArrayNode) node;
      for (int i = 0; i < array.size(); i++) {
        array.set(i, resolve(array.get(i), variables));
      }
    } else if (node.isTextual()) {
      String text = node.textValue();
      if (!text.startsWith("${") || !text.endsWith("}")) {
        return node;
      }
      String expression = text.substring(2, text.length() - 1);
      if (expression.equals("now")) {
        return TextNode.valueOf(Instant.now().toString());
      }
      if (expression.equals("datumVandaag")) {
        return TextNode.valueOf(LocalDate.now(DATE_ZONE).toString());
      }
      if (expression.equals("datumTijdVandaag")) {
        return TextNode.valueOf(LocalDate.now(DATE_ZONE).atStartOfDay(DATE_ZONE)
            .format(DATE_TIME_FORMAT));
      }
      if (expression.equals("uuid")) {
        return TextNode.valueOf(UUID.randomUUID().toString());
      }
      String generated = RandomTemplateValues.generate(expression);
      if (generated != null) {
        return TextNode.valueOf(generated);
      }
      String[] parts = expression.split(":", -1);
      String value = variables.apply(parts[0]);
      if (value == null || value.equals("<EOF>")) {
        throw new IllegalArgumentException("Missing template variable: " + parts[0]);
      }
      if (parts.length == 1) {
        return TextNode.valueOf(value);
      }
      if (parts.length == 2 && parts[1].equals("number")) {
        try {
          return DecimalNode.valueOf(new BigDecimal(value));
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("Invalid number for template variable: " + parts[0], e);
        }
      }
      if (parts.length == 2 && parts[1].equals("boolean")
          && (value.equals("true") || value.equals("false"))) {
        return BooleanNode.valueOf(Boolean.parseBoolean(value));
      }
      throw new IllegalArgumentException("Invalid typed template variable: " + expression);
    }
    return node;
  }
}
