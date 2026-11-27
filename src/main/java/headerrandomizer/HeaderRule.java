package headerrandomizer;

import burp.api.montoya.core.ToolType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

final class HeaderRule {
    static final List<ToolType> TOOLS = List.of(ToolType.REPEATER, ToolType.PROXY, ToolType.INTRUDER, ToolType.SCANNER);
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_\\x60|~0-9A-Za-z-]+");
    private static final Pattern TOKEN = Pattern.compile("\\{(ip|octet|digits:([0-9]+)|num:(-?[0-9]+)-(-?[0-9]+))}");

    final boolean enabled;
    final String name;
    final Mode mode;
    final String config;
    final Set<ToolType> tools;
    private final Function<RandomGenerator, String> generator;

    HeaderRule(boolean enabled, String name, Mode mode, String config, Set<ToolType> tools) {
        this.enabled = enabled;
        this.name = name.strip();
        this.mode = mode;
        this.config = config.strip();
        this.tools = Set.copyOf(tools);
        if (!HEADER_NAME.matcher(this.name).matches()) throw new IllegalArgumentException("Enter a valid HTTP header name.");
        if (!TOOLS.containsAll(this.tools)) throw new IllegalArgumentException("Select supported Burp tools.");
        this.generator = compile(mode, this.config);
    }

    String generate(RandomGenerator random) {
        return generator.apply(random);
    }

    enum Mode {
        FIXED("Fixed value"), LIST("Random from list"),
        DIGITS("Random digits"), TEMPLATE("Template"), IPV4("Random IPv4");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        @Override public String toString() {
            return label;
        }
    }

    private static Function<RandomGenerator, String> compile(Mode mode, String config) {
        return switch (mode) {
            case FIXED -> fixed(config);
            case LIST -> {
                List<String> values = config.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
                if (values.isEmpty()) throw new IllegalArgumentException("Enter at least one value.");
                values.forEach(HeaderRule::validateValue);
                yield random -> values.get(random.nextInt(values.size()));
            }
            case DIGITS -> digits(digitLength(config));
            case TEMPLATE -> template(config);
            case IPV4 -> HeaderRule::ipv4;
        };
    }

    private static Function<RandomGenerator, String> fixed(String value) {
        if (value.isEmpty()) throw new IllegalArgumentException("Enter a header value.");
        validateValue(value);
        return random -> value;
    }

    static void validateValue(String value) {
        if (value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\t')) {
            throw new IllegalArgumentException("Header values must be single lines without control characters.");
        }
    }

    private static int digitLength(String value) {
        int length = integer(value, "Enter a digit length from 1 to 4096.");
        if (length < 1 || length > 4096) throw new IllegalArgumentException("Enter a digit length from 1 to 4096.");
        return length;
    }

    private static int integer(String value, String message) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Function<RandomGenerator, String> digits(int length) {
        return random -> {
            StringBuilder result = new StringBuilder(length);
            for (int i = 0; i < length; i++) result.append(random.nextInt(10));
            return result.toString();
        };
    }

    private static String ipv4(RandomGenerator random) {
        return random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "."
                + random.nextInt(1, 255) + "." + random.nextInt(1, 255);
    }

    private static Function<RandomGenerator, String> template(String config) {
        if (config.isEmpty()) throw new IllegalArgumentException("Enter a template.");
        validateValue(config);
        var matcher = TOKEN.matcher(config);
        List<Function<RandomGenerator, String>> pieces = new ArrayList<>();
        int offset = 0;
        while (matcher.find()) {
            addLiteral(pieces, config.substring(offset, matcher.start()));
            if (matcher.group(2) != null) {
                pieces.add(digits(digitLength(matcher.group(2))));
            } else if (matcher.group(3) != null) {
                int from = integer(matcher.group(3), "Enter a valid template number range.");
                int to = integer(matcher.group(4), "Enter a valid template number range.");
                if (from > to) throw new IllegalArgumentException("The template number range is reversed.");
                pieces.add(random -> Long.toString(random.nextLong(from, (long) to + 1)));
            } else if (matcher.group(1).equals("ip")) {
                pieces.add(HeaderRule::ipv4);
            } else {
                pieces.add(random -> Integer.toString(random.nextInt(1, 255)));
            }
            offset = matcher.end();
        }
        addLiteral(pieces, config.substring(offset));
        return random -> {
            StringBuilder value = new StringBuilder();
            for (var piece : pieces) value.append(piece.apply(random));
            return value.toString();
        };
    }

    private static void addLiteral(List<Function<RandomGenerator, String>> pieces, String literal) {
        if (literal.contains("{") || literal.contains("}")) throw new IllegalArgumentException("The template has an unknown or malformed token.");
        if (!literal.isEmpty()) pieces.add(random -> literal);
    }
}
