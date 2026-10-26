package common;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ThreadLocalRandom;

/** Runtime random values for JSON templates. Values are test data, not unique identifiers. */
final class RandomTemplateValues {
  private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final String DIGITS = "0123456789";

  private RandomTemplateValues() {
  }

  /** Returns null when the expression is an ordinary variable rather than a generator. */
  static String generate(String expression) {
    String[] parts = expression.split(":", -1);
    switch (parts[0]) {
      case "randomString":
      case "randomDigits":
        if (parts.length != 2) {
          throw invalid(expression, "expected a length between 1 and 10000");
        }
        int length = integer(parts[1], expression);
        if (length < 1 || length > 10000) {
          throw invalid(expression, "length must be between 1 and 10000");
        }
        String alphabet = parts[0].equals("randomString") ? LETTERS : DIGITS;
        StringBuilder result = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
          result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return result.toString();
      case "geboorteDatum":
        if (parts.length != 1 && parts.length != 3) {
          throw invalid(expression, "expected geboorteDatum or geboorteDatum:minAge:maxAge");
        }
        int minAge = parts.length == 1 ? 18 : integer(parts[1], expression);
        int maxAge = parts.length == 1 ? 80 : integer(parts[2], expression);
        if (minAge < 0 || maxAge > 150 || minAge > maxAge) {
          throw invalid(expression, "ages must satisfy 0 <= minAge <= maxAge <= 150");
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        // Both bounds include every date belonging to a person of a permitted completed age.
        long earliest = today.minusYears(maxAge + 1L).plusDays(1).toEpochDay();
        long latest = today.minusYears(minAge).toEpochDay();
        return LocalDate.ofEpochDay(ThreadLocalRandom.current().nextLong(earliest, latest + 1))
            .toString();
      default:
        return null;
    }
  }

  private static int integer(String value, String expression) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw invalid(expression, "arguments must be integers");
    }
  }

  private static IllegalArgumentException invalid(String expression, String reason) {
    return new IllegalArgumentException("Invalid random placeholder ${" + expression + "}: " + reason);
  }
}
