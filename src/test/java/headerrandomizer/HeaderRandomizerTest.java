package headerrandomizer;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.persistence.Preferences;

import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.ServiceLoader;
import java.util.Set;

public final class HeaderRandomizerTest {
    public static void main(String[] args) throws Exception {
        assert ServiceLoader.load(BurpExtension.class).findFirst().orElseThrow() instanceof HeaderRandomizerExtension;
        testYamlAndTemplates();
        testStarterExamples();
        testStarterExamplesUpgrade();
        testAllToolsCheckbox();
        testSavedRuleScopeAndHeaderReplacement();
        System.out.println("HeaderRandomizerTest: OK");
    }

    private static void testYamlAndTemplates() {
        var imported = ConfigurationYaml.readRules("""
                version: 1
                enabled: true
                rules:
                  - header: X-Demo
                    generator: fixed
                    value: 'literal: text # sample'
                    tools: all
                  - header: X-Count
                    generator: digits
                    value: 4
                """);
        var updated = ConfigurationYaml.readRules(ConfigurationYaml.writeRules(imported));
        assert updated.enabled();
        assert updated.rules().get(0).tools.equals(Set.copyOf(HeaderRule.TOOLS));
        assert updated.rules().get(0).config.equals("literal: text # sample");
        assert updated.rules().get(1).tools.equals(Set.of(ToolType.REPEATER));
        assert updated.rules().get(1).generate(new Random(17)).length() == 4;
        assertRejected(() -> ConfigurationYaml.readRules("version: 1\nrules: []\nrules: []\n"));
        assertRejected(() -> ConfigurationYaml.readRules("version: 1\nrules: []\nnull: ignored\n"));
        assertRejected(() -> ConfigurationYaml.readRules("version: 1\nrules: &data [*data]\n"));
        assertRejected(() -> ConfigurationYaml.readRules(
                "version: 1\nrules: " + "[".repeat(40) + "]".repeat(40)));
        assertRejected(() -> ConfigurationYaml.readRules("""
                version: 1
                rules:
                  - header: X-Bad
                    generator: list
                    values: ["one\\nanother"]
                """));

        Random random = new Random(17);
        var sample = new HeaderRule(true, "X-Demo", HeaderRule.Mode.TEMPLATE,
                "test-{digits:4}-{num:-4--4}", Set.of(ToolType.REPEATER));
        assert sample.generate(random).matches("test-[0-9]{4}--4");
        try {
            new HeaderRule(true, "X-Demo", HeaderRule.Mode.FIXED, "bad\r\nheader", Set.of());
            throw new AssertionError("An embedded header line should be rejected");
        } catch (IllegalArgumentException expected) {
            assert expected.getMessage().contains("single lines");
        }
    }

    private static void testStarterExamples() {
        var preferences = memoryPreferences();
        var settings = new RuleSettings(preferences);
        settings.load();
        var demo = settings.active();
        assert !demo.enabled();
        assert demo.rules().stream().noneMatch(rule -> rule.enabled);
        assert demo.rules().size() == HeaderRule.Mode.values().length;
        assert demo.rules().stream().map(rule -> rule.name).distinct().count() == demo.rules().size();
        assert demo.rules().stream().allMatch(rule -> rule.tools.equals(Set.of(ToolType.REPEATER)));

        Random random = new Random(3);
        var agent = demo.rules().get(0);
        assert agent.name.equals("User-Agent");
        assert agent.mode == HeaderRule.Mode.LIST;
        assert Set.of("DemoDesktop/1.0", "DemoMobile/1.0").contains(agent.generate(random));
        var forwarded = demo.rules().get(1);
        assert forwarded.name.equals("X-Forwarded-For");
        assert forwarded.mode == HeaderRule.Mode.TEMPLATE;
        String sample = forwarded.generate(random);
        assert sample.startsWith("192.0.2.");
        int octet = Integer.parseInt(sample.substring("192.0.2.".length()));
        assert octet >= 1 && octet <= 254;

        var fixed = demo.rules().get(2);
        assert fixed.name.equals("X-Demo-Mode");
        assert fixed.mode == HeaderRule.Mode.FIXED;
        assert fixed.generate(random).equals("preview");
        var digits = demo.rules().get(3);
        assert digits.name.equals("X-Demo-Request-Id");
        assert digits.mode == HeaderRule.Mode.DIGITS;
        assert digits.generate(random).matches("[0-9]{8}");
        var ip = demo.rules().get(4);
        assert ip.name.equals("X-Demo-Client-Ip");
        assert ip.mode == HeaderRule.Mode.IPV4;
        String[] octets = ip.generate(random).split("\\.");
        assert octets.length == 4;
        for (String value : octets) {
            int number = Integer.parseInt(value);
            assert number >= 1 && number <= 254;
        }

        HttpRequest original = request(List.of());
        var enabledMaster = new RuleSettings.Snapshot(true, demo.rules());
        assert RequestRules.apply(original, ToolType.REPEATER, enabledMaster, random) == original;
    }

    private static void testStarterExamplesUpgrade() {
        var preferences = memoryPreferences();
        preferences.setString("header_randomizer_java_v1", """
                enabled=true
                rules=2
                0.enabled=true
                0.name=Accept
                0.mode=FIXED
                0.value=application/json
                0.tools=REPEATER
                1.enabled=true
                1.name=user-agent
                1.mode=FIXED
                1.value=Local-QA/1
                1.tools=PROXY
                """);
        var settings = new RuleSettings(preferences);
        settings.load();
        var upgraded = settings.active();
        assert upgraded.enabled();
        assert upgraded.rules().size() == 6;
        assert upgraded.rules().get(0).name.equals("Accept");
        var agent = upgraded.rules().get(1);
        assert agent.name.equals("user-agent");
        assert agent.enabled;
        assert agent.config.equals("Local-QA/1");
        assert agent.tools.equals(Set.of(ToolType.PROXY));
        assert upgraded.rules().get(2).name.equals("X-Forwarded-For");
        assert upgraded.rules().subList(2, 6).stream().noneMatch(rule -> rule.enabled);

        var reopened = new RuleSettings(preferences);
        reopened.load();
        assert reopened.active().rules().size() == upgraded.rules().size();
        reopened.apply(new RuleSettings.Snapshot(false, List.of()));
        settings.load();
        assert !settings.active().enabled();
        assert settings.active().rules().isEmpty();
    }

    private static void testAllToolsCheckbox() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Preferences preferences = memoryPreferences();
            RuleSettings settings = new RuleSettings(preferences);
            RulesPanel panel = new RulesPanel(settings);
            try {
                assert findButton(panel, "Header presets") == null;
                assert findButton(panel, "Import YAML") != null;
                assert findButton(panel, "Export YAML") != null;
                assert findButton(panel, "YAML template") != null;
                assert findButton(panel, "?").getAccessibleContext().getAccessibleName().equals("Template examples");
                HeaderRule example = new TemplateExamples().selectedRule();
                assert !example.enabled;
                assert example.mode == HeaderRule.Mode.TEMPLATE;
                assert example.tools.equals(Set.of(ToolType.REPEATER));
                assert example.generate(new Random(17)).matches("build-[0-9]{4}-[1-3]");
                findButton(panel, "Add rule").doClick(0);
                JTable table = findTable(panel);
                int all = table.getColumn("All tools").getModelIndex();
                int repeater = table.getColumn("Repeater").getModelIndex();
                int proxy = table.getColumn("Proxy").getModelIndex();
                assert table.getValueAt(0, all).equals(false);
                assert table.getValueAt(0, repeater).equals(true);
                assert table.getValueAt(0, proxy).equals(false);
                table.setValueAt(true, 0, all);
                assert table.getValueAt(0, proxy).equals(true);
                assert table.getValueAt(0, table.getColumn("Scanner").getModelIndex()).equals(true);
                findButton(panel, "Apply & save").doClick(0);
                assert settings.active().rules().get(0).tools.equals(Set.copyOf(HeaderRule.TOOLS));
                table.setValueAt(false, 0, proxy);
                assert table.getValueAt(0, all).equals(false);
                table.setValueAt(false, 0, all);
                assert table.getValueAt(0, repeater).equals(false);
            } finally {
                panel.close();
            }
        });
    }

    private static JTable findTable(Component component) {
        if (component instanceof JTable table) return table;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JTable table = findTable(child);
                if (table != null) return table;
            }
        }
        return null;
    }

    private static JButton findButton(Component component, String title) {
        if (component instanceof JButton button && title.equals(button.getText())) return button;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                JButton button = findButton(child, title);
                if (button != null) return button;
            }
        }
        return null;
    }

    private static Preferences memoryPreferences() {
        var values = new HashMap<String, String>();
        return (Preferences) Proxy.newProxyInstance(Preferences.class.getClassLoader(),
                new Class<?>[]{Preferences.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "getString" -> values.get(arguments[0]);
                    case "setString" -> {
                        values.put((String) arguments[0], (String) arguments[1]);
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static void assertRejected(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("Invalid YAML was accepted");
        } catch (IllegalArgumentException expected) {
            assert !expected.getMessage().isBlank();
        }
    }

    private static void testSavedRuleScopeAndHeaderReplacement() {
        var snapshot = new RuleSettings.Snapshot(true, List.of(
                new HeaderRule(true, "Accept", HeaderRule.Mode.FIXED, "application/json", Set.of(ToolType.REPEATER)),
                new HeaderRule(true, "User-Agent", HeaderRule.Mode.LIST, "synthetic-a\nsynthetic-b", Set.of(ToolType.REPEATER))
        ));
        var restored = RuleSettings.decode(RuleSettings.encode(snapshot));
        assert restored.rules().get(1).tools.equals(Set.of(ToolType.REPEATER));
        HttpRequest original = request(List.of(
                new Header("accept", "text/html"), new Header("ACCEPT", "text/plain"),
                new Header("user-agent", "synthetic-original"), new Header("X-Other", "untouched")));
        Random random = new Random(1024);
        assert RequestRules.apply(original, ToolType.PROXY, restored, random) == original;
        assert RequestRules.apply(original, ToolType.REPEATER,
                new RuleSettings.Snapshot(false, restored.rules()), random) == original;
        var observed = new HashSet<String>();
        for (int i = 0; i < 16; i++) {
            HttpRequest rewritten = RequestRules.apply(original, ToolType.REPEATER, restored, random);
            assert values(rewritten, "Accept").equals(List.of("application/json"));
            assert values(rewritten, "X-Other").equals(List.of("untouched"));
            observed.addAll(values(rewritten, "User-Agent"));
        }
        assert observed.equals(Set.of("synthetic-a", "synthetic-b"));
    }

    private static List<String> values(HttpRequest request, String name) {
        return request.headers().stream().filter(header -> header.name().equalsIgnoreCase(name))
                .map(HttpHeader::value).toList();
    }

    private static HttpRequest request(List<HttpHeader> headers) {
        return (HttpRequest) Proxy.newProxyInstance(HttpRequest.class.getClassLoader(),
                new Class<?>[]{HttpRequest.class}, (proxy, method, args) -> {
                    if (method.getName().equals("headers")) return headers;
                    var next = new ArrayList<>(headers);
                    switch (method.getName()) {
                        case "withRemovedHeaders" -> next.removeAll((List<?>) args[0]);
                        case "withAddedHeader" -> next.add(new Header((String) args[0], (String) args[1]));
                        case "withUpdatedHeader" -> next.replaceAll(header -> header.name().equalsIgnoreCase((String) args[0])
                                ? new Header(header.name(), (String) args[1]) : header);
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                    return request(next);
                });
    }

    private record Header(String name, String value) implements HttpHeader {}
}
