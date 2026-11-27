package headerrandomizer;

import burp.api.montoya.core.ToolType;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

final class TemplateExamples extends JPanel {
    private final JComboBox<Example> choices = new JComboBox<>(new Example[]{
            new Example("Build variant", "X-Test-Variant", "build-{digits:4}-{num:1-3}",
                    "Generate four digits and an integer from 1 through 3."),
            new Example("Request ID", "X-Demo-Run-Id", "run-{digits:8}",
                    "Keep the run prefix and generate exactly eight digits."),
            new Example("Forwarded address", "X-Forwarded-For", "192.0.2.{octet}",
                    "Keep the documentation prefix and generate the last octet."),
            new Example("Address with a prefix", "X-Demo-Address", "addr-{ip}",
                    "Insert a complete IPv4 value after the prefix.")
    });
    private final JLabel description = new JLabel();
    private final JTextField header = readOnlyField();
    private final JTextField template = readOnlyField();
    private final JTextField sample = readOnlyField();

    TemplateExamples() {
        super(new BorderLayout(0, 10));

        JPanel selection = new JPanel(new BorderLayout(8, 0));
        JLabel example = new JLabel("Example");
        example.setLabelFor(choices);
        selection.add(example, BorderLayout.WEST);
        selection.add(choices, BorderLayout.CENTER);
        add(selection, BorderLayout.NORTH);

        JPanel labels = new JPanel(new GridLayout(0, 1, 0, 5));
        JPanel fields = new JPanel(new GridLayout(0, 1, 0, 5));
        addField(labels, fields, "Header", header);
        addField(labels, fields, "Template", template);
        addField(labels, fields, "Sample value", sample);

        JPanel details = new JPanel(new BorderLayout(8, 0));
        details.add(labels, BorderLayout.WEST);
        details.add(fields, BorderLayout.CENTER);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.add(description, BorderLayout.NORTH);
        body.add(details, BorderLayout.CENTER);
        add(body, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        JButton again = new JButton("Generate again");
        again.addActionListener(event -> refreshSample());
        actions.add(again);
        add(actions, BorderLayout.SOUTH);

        choices.addActionListener(event -> refreshExample());
        refreshExample();
    }

    HeaderRule selectedRule() {
        return choices.getItemAt(choices.getSelectedIndex()).rule();
    }

    private void refreshExample() {
        Example example = choices.getItemAt(choices.getSelectedIndex());
        description.setText(example.description());
        header.setText(example.rule().name);
        template.setText(example.rule().config);
        refreshSample();
    }

    private void refreshSample() {
        sample.setText(selectedRule().generate(ThreadLocalRandom.current()));
    }

    private static JTextField readOnlyField() {
        JTextField field = new JTextField(26);
        field.setEditable(false);
        return field;
    }

    private static void addField(JPanel labels, JPanel fields, String name, JTextField value) {
        JLabel label = new JLabel(name);
        label.setLabelFor(value);
        labels.add(label);
        fields.add(value);
    }

    private record Example(String name, HeaderRule rule, String description) {
        Example(String name, String header, String value, String description) {
            this(name, new HeaderRule(false, header, HeaderRule.Mode.TEMPLATE, value, Set.of(ToolType.REPEATER)),
                    description);
        }

        @Override public String toString() {
            return name;
        }
    }
}
