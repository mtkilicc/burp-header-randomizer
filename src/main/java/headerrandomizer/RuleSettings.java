package headerrandomizer;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.persistence.Preferences;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

final class RuleSettings {
    private static final String KEY = "header_randomizer_java_v1";
    private static final String STARTER_EXAMPLES_ADDED = "starterExamplesAdded";

    record Snapshot(boolean enabled, List<HeaderRule> rules) {
        static final Snapshot EMPTY = new Snapshot(true, List.of());

        Snapshot {
            rules = List.copyOf(rules);
        }
    }

    private final Preferences preferences;
    private final AtomicReference<Snapshot> active = new AtomicReference<>(Snapshot.EMPTY);

    RuleSettings(Preferences preferences) {
        this.preferences = preferences;
    }

    void load() {
        String saved = preferences.getString(KEY);
        if (saved == null) {
            active.set(starterRules());
            return;
        }
        Properties data = readProperties(saved);
        Snapshot snapshot = decode(data);
        if (Boolean.parseBoolean(data.getProperty(STARTER_EXAMPLES_ADDED))) {
            active.set(snapshot);
            return;
        }
        List<HeaderRule> rules = new ArrayList<>(snapshot.rules());
        for (HeaderRule example : starterRules().rules()) {
            if (rules.stream().noneMatch(rule -> rule.name.equalsIgnoreCase(example.name))) {
                rules.add(example);
            }
        }
        apply(new Snapshot(snapshot.enabled(), rules));
    }

    private static Snapshot starterRules() {
        return ConfigurationYaml.readRules(ConfigurationYaml.resource("rules-template.yaml"));
    }

    Snapshot active() {
        return active.get();
    }

    void apply(Snapshot snapshot) {
        preferences.setString(KEY, encode(snapshot));
        active.set(snapshot);
    }

    static String encode(Snapshot snapshot) {
        Properties data = new Properties();
        data.setProperty(STARTER_EXAMPLES_ADDED, "true");
        data.setProperty("enabled", Boolean.toString(snapshot.enabled()));
        data.setProperty("rules", Integer.toString(snapshot.rules().size()));
        for (int i = 0; i < snapshot.rules().size(); i++) {
            HeaderRule rule = snapshot.rules().get(i);
            String prefix = i + ".";
            data.setProperty(prefix + "enabled", Boolean.toString(rule.enabled));
            data.setProperty(prefix + "name", rule.name);
            data.setProperty(prefix + "mode", rule.mode.name());
            data.setProperty(prefix + "value", rule.config);
            data.setProperty(prefix + "tools", rule.tools.stream().map(ToolType::name).sorted()
                    .collect(Collectors.joining(",")));
        }
        StringWriter output = new StringWriter();
        try {
            data.store(output, null);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return output.toString();
    }

    static Snapshot decode(String value) {
        return decode(readProperties(value));
    }

    private static Properties readProperties(String value) {
        Properties data = new Properties();
        try {
            data.load(new StringReader(value));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
        return data;
    }

    private static Snapshot decode(Properties data) {
        List<HeaderRule> rules = new ArrayList<>();
        int count = Integer.parseInt(data.getProperty("rules"));
        if (count < 0 || count > data.size() / 5) throw new IllegalArgumentException("Invalid saved rules.");
        for (int i = 0; i < count; i++) {
            String prefix = i + ".";
            var tools = EnumSet.noneOf(ToolType.class);
            String toolList = data.getProperty(prefix + "tools");
            if (!toolList.isEmpty()) {
                for (String tool : toolList.split(",")) tools.add(ToolType.valueOf(tool));
            }
            rules.add(new HeaderRule(
                    Boolean.parseBoolean(data.getProperty(prefix + "enabled")),
                    data.getProperty(prefix + "name"),
                    HeaderRule.Mode.valueOf(data.getProperty(prefix + "mode")),
                    data.getProperty(prefix + "value"),
                    tools));
        }
        return new Snapshot(Boolean.parseBoolean(data.getProperty("enabled")), rules);
    }
}
