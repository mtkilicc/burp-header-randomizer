package headerrandomizer;

import burp.api.montoya.core.ToolType;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.schema.CoreSchema;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ConfigurationYaml {
    private static final LoadSettings LOAD = LoadSettings.builder().setSchema(new CoreSchema())
            .setAllowDuplicateKeys(false).setAllowNonScalarKeys(false).setMaxAliasesForCollections(0)
            .setCodePointLimit(1024 * 1024).build();
    private static final DumpSettings DUMP = DumpSettings.builder().setSchema(new CoreSchema())
            .setDefaultFlowStyle(FlowStyle.BLOCK).setIndicatorIndent(2).setIndentWithIndicator(true)
            .setSplitLines(false).build();

    static RuleSettings.Snapshot readRules(String yaml) {
        Map<?, ?> root = rulesDocument(yaml);
        List<HeaderRule> rules = new ArrayList<>();
        for (Object item : list(root.get("rules"), "rules")) {
            try {
                rules.add(readRule(item));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Rule " + (rules.size() + 1) + " — " + failure.getMessage());
            }
        }
        return new RuleSettings.Snapshot(flag(root, "enabled", false), rules);
    }

    private static HeaderRule readRule(Object item) {
        Map<?, ?> fields = mapping(item, "rule", Set.of("enabled", "header", "generator", "value", "values", "tools"));
        String header = text(fields.get("header"), "header");
        String generator = text(fields.get("generator"), "generator");
        HeaderRule.Mode mode;
        try {
            mode = HeaderRule.Mode.valueOf(generator.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Use fixed, list, digits, template, or ipv4 as the generator.");
        }
        String config;
        if (mode == HeaderRule.Mode.LIST) {
            if (fields.containsKey("value")) throw new IllegalArgumentException("List generators use values.");
            List<String> values = list(fields.get("values"), "values").stream()
                    .map(value -> text(value, "list value"))
                    .toList();
            values.forEach(HeaderRule::validateValue);
            config = String.join("\n", values);
        } else if (mode == HeaderRule.Mode.IPV4) {
            if (fields.containsKey("value") || fields.containsKey("values")) {
                throw new IllegalArgumentException("IPv4 needs only a header and generator.");
            }
            config = "";
        } else {
            if (fields.containsKey("values")) throw new IllegalArgumentException("This generator uses value.");
            Object value = fields.get("value");
            config = mode == HeaderRule.Mode.DIGITS && value instanceof Number
                    ? value.toString() : text(value, "value");
        }
        Set<ToolType> scope = fields.containsKey("tools") ? tools(fields.get("tools")) : Set.of(ToolType.REPEATER);
        return new HeaderRule(flag(fields, "enabled", true), header, mode, config, scope);
    }

    private static Set<ToolType> tools(Object value) {
        List<?> names = value instanceof String name ? List.of(name) : list(value, "tools");
        if (names.size() == 1 && names.get(0) instanceof String name && name.equalsIgnoreCase("all")) {
            return Set.copyOf(HeaderRule.TOOLS);
        }
        var tools = EnumSet.noneOf(ToolType.class);
        for (Object raw : names) {
            String name = text(raw, "tool");
            ToolType tool = HeaderRule.TOOLS.stream().filter(candidate -> candidate.name().equalsIgnoreCase(name))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "Use repeater, proxy, intruder, scanner, or all by itself."));
            tools.add(tool);
        }
        return tools;
    }

    static String writeRules(RuleSettings.Snapshot snapshot) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (HeaderRule rule : snapshot.rules()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("enabled", rule.enabled);
            fields.put("header", rule.name);
            fields.put("generator", rule.mode.name().toLowerCase(Locale.ROOT));
            if (rule.mode == HeaderRule.Mode.LIST) {
                fields.put("values", rule.config.lines().map(String::strip).filter(line -> !line.isEmpty()).toList());
            } else if (rule.mode != HeaderRule.Mode.IPV4) {
                fields.put("value", rule.config);
            }
            Object scope = rule.tools.containsAll(HeaderRule.TOOLS) ? "all" : HeaderRule.TOOLS.stream()
                    .filter(rule.tools::contains).map(tool -> tool.name().toLowerCase(Locale.ROOT)).toList();
            fields.put("tools", scope);
            rules.add(fields);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("enabled", snapshot.enabled());
        root.put("rules", rules);
        return new Dump(DUMP).dumpToString(root);
    }

    private static Map<?, ?> rulesDocument(String yaml) {
        Object loaded;
        try {
            int depth = 0;
            for (var event : new Parse(LOAD).parseString(yaml)) {
                switch (event.getEventId()) {
                    case MappingStart, SequenceStart -> {
                        if (++depth > 12) throw new IllegalArgumentException("The YAML has too many nested levels.");
                    }
                    case MappingEnd, SequenceEnd -> depth--;
                    case Alias -> throw new IllegalArgumentException("The YAML format uses direct values without aliases.");
                    default -> { }
                }
            }
            loaded = new Load(LOAD).loadFromString(yaml);
        } catch (MarkedYamlEngineException failure) {
            int line = failure.getProblemMark().map(mark -> mark.getLine() + 1).orElse(0);
            throw new IllegalArgumentException(line == 0 ? "Invalid YAML syntax." : "Invalid YAML near line " + line + ".");
        } catch (YamlEngineException failure) {
            throw new IllegalArgumentException("Invalid YAML syntax.");
        }
        Map<?, ?> root = mapping(loaded, "rules document", Set.of("version", "enabled", "rules"));
        if (!Integer.valueOf(1).equals(root.get("version"))) throw new IllegalArgumentException("Use version: 1.");
        return root;
    }

    private static Map<?, ?> mapping(Object value, String label, Set<String> keys) {
        if (!(value instanceof Map<?, ?> result)
                || result.keySet().stream().anyMatch(key -> !(key instanceof String name) || !keys.contains(name))) {
            throw new IllegalArgumentException("Check the fields in the " + label + ".");
        }
        return result;
    }

    private static List<?> list(Object value, String label) {
        if (value instanceof List<?> result) return result;
        throw new IllegalArgumentException("Use a YAML list for " + label + ".");
    }

    private static String text(Object value, String label) {
        if (value instanceof String result && !result.isBlank()) return result;
        throw new IllegalArgumentException("Enter text for " + label + ". Quote numbers used as text.");
    }

    private static boolean flag(Map<?, ?> fields, String key, boolean defaultValue) {
        if (!fields.containsKey(key)) return defaultValue;
        if (fields.get(key) instanceof Boolean flag) return flag;
        throw new IllegalArgumentException("Use true or false for " + key + ".");
    }

    static String resource(String filename) {
        try (var stream = Objects.requireNonNull(ConfigurationYaml.class.getResourceAsStream(filename))) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
