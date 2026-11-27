package headerrandomizer;

import burp.api.montoya.core.ToolType;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static headerrandomizer.HeaderRule.Mode.FIXED;
import static headerrandomizer.HeaderRule.Mode.LIST;

final class RulesPanel extends JPanel {
    private static final int ALL_TOOLS_COLUMN = 4;
    private static final int FIRST_TOOL_COLUMN = 5;
    private final RuleSettings settings;
    private final ConfigurationFiles files = new ConfigurationFiles();
    private final RuleTableModel rows = new RuleTableModel();
    private final JTable table = new JTable(rows);
    private final JCheckBox enabled = new JCheckBox("Enable request rewriting");
    private final JLabel status = new JLabel();
    private boolean dirty;

    RulesPanel(RuleSettings settings) {
        super(new BorderLayout(8, 8));
        this.settings = settings;
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel heading = new JPanel(new BorderLayout());
        JPanel creation = new JPanel(new FlowLayout(FlowLayout.LEFT));
        addButton(creation, "Add rule", "Create a rule that starts in Repeater.",
                () -> {
                    stopEditing();
                    appendDraftRule(new HeaderRule(true, "X-Custom-Header", FIXED, "example", Set.of(ToolType.REPEATER)));
                });
        addButton(creation, "Import YAML", "Load a rule file into the draft table.", this::importRules);
        addButton(creation, "Export YAML", "Save the current draft, including its values, to a YAML file.", this::exportRules);
        addButton(creation, "YAML template", "Save the five disabled starter examples.", this::saveTemplate);
        JButton examplesHelp = addButton(creation, "?", "Template examples", this::showTemplateExamples);
        examplesHelp.getAccessibleContext().setAccessibleName("Template examples");
        enabled.setToolTipText("Pause or resume all rules after Apply & save.");
        heading.add(creation, BorderLayout.WEST);
        heading.add(enabled, BorderLayout.EAST);
        add(heading, BorderLayout.NORTH);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.putClientProperty("terminateEditOnFocusLost", true);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(new JComboBox<>(HeaderRule.Mode.values())));
        table.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable owner, Object value, boolean selected, boolean focused, int row, int column) {
                if (owner.getValueAt(row, 2) == LIST) {
                    long count = value.toString().lines().filter(line -> !line.isBlank()).count();
                    value = count + (count == 1 ? " value" : " values");
                }
                return super.getTableCellRendererComponent(owner, value, selected, focused, row, column);
            }
        });
        int[] widths = {42, 147, 130, 300, 74, 75, 64, 66, 66};
        for (int i = 0; i < widths.length; i++) table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(4, 4));
        JPanel editing = new JPanel(new FlowLayout(FlowLayout.LEFT));
        addButton(editing, "Edit value", "Edit the selected rule's value or generator settings.", this::editValue);
        addButton(editing, "Import TXT", "Copy one value per line from a UTF-8 file into the selected rule.", this::importText);
        addButton(editing, "Preview", "Show one sample without sending a request.", this::preview);
        addButton(editing, "Remove selected", "Remove the selected row from the draft.", this::removeSelected);
        footer.add(editing, BorderLayout.NORTH);
        JLabel help = new JLabel("New rules start in Repeater. Apply & save activates table changes.");
        footer.add(help, BorderLayout.CENTER);

        JPanel changes = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        addButton(changes, "Revert edits", "Restore the last applied rules.", this::restore);
        addButton(changes, "Apply & save", "Save the draft and use it for subsequent requests.", this::apply);
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.add(status, BorderLayout.CENTER);
        statusBar.add(changes, BorderLayout.EAST);
        footer.add(statusBar, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);

        rows.addTableModelListener(event -> markDraft());
        enabled.addActionListener(event -> markDraft());
        restore();
    }

    private void markDraft() {
        dirty = true;
        status.setText("Unapplied changes.");
    }

    private void importRules() {
        stopEditing();
        files.read(this, "Import rule YAML", true, ConfigurationYaml::readRules, snapshot -> {
            if (dirty && JOptionPane.showConfirmDialog(this, "Replace the unapplied rules in the table?",
                    "Import rules", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
            display(snapshot);
            markDraft();
            status.setText("Imported " + snapshot.rules().size() + " rules. Select Apply & save to activate.");
        });
    }

    private void exportRules() {
        try {
            RuleSettings.Snapshot draft = draft();
            files.writeYaml(this, "Export rule YAML", "header-rules.yaml",
                    () -> ConfigurationYaml.writeRules(draft), () -> status.setText("Exported rule YAML."));
        } catch (IllegalArgumentException failure) {
            showError(failure.getMessage());
        }
    }

    private void saveTemplate() {
        files.writeYaml(this, "Save rule template", "header-rules-template.yaml",
                () -> ConfigurationYaml.resource("rules-template.yaml"),
                () -> status.setText("Saved the YAML template."));
    }

    private void showTemplateExamples() {
        stopEditing();
        TemplateExamples examples = new TemplateExamples();
        String[] actions = {"Add to draft", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this, examples, "Template examples", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, actions, actions[0]);
        if (choice != 0) return;
        addTemplateExample(examples.selectedRule());
    }

    private void addTemplateExample(HeaderRule example) {
        for (int row = 0; row < rows.getRowCount(); row++) {
            if (example.name.equalsIgnoreCase(rows.getValueAt(row, 1).toString().strip())
                    && rows.getValueAt(row, 2) == example.mode
                    && example.config.equals(rows.getValueAt(row, 3).toString().strip())) {
                selectAndScroll(row);
                status.setText("This template is already in the table.");
                return;
            }
        }
        appendDraftRule(example);
        status.setText("Added a disabled template to the draft.");
    }

    private void appendDraftRule(HeaderRule rule) {
        rows.addRule(rule);
        selectAndScroll(rows.getRowCount() - 1);
    }

    private void selectAndScroll(int row) {
        table.setRowSelectionInterval(row, row);
        table.scrollRectToVisible(table.getCellRect(row, 1, true));
    }

    private void editValue() {
        int row = selectedRow();
        if (row < 0) return;
        HeaderRule.Mode mode = (HeaderRule.Mode) rows.getValueAt(row, 2);
        String hint = switch (mode) {
            case LIST -> "Enter one value per line. Blank lines are ignored.";
            case DIGITS -> "Enter a length from 1 to 4096.";
            case TEMPLATE -> "Combine text and tokens. Example value build-{digits:4}-{num:1-3}.";
            case IPV4 -> "This generator needs no configuration.";
            case FIXED -> "Enter a single header value.";
        };
        JTextArea text = textArea(rows.getValueAt(row, 3).toString(), 8);
        JPanel editor = new JPanel(new BorderLayout(0, 8));
        editor.add(new JLabel(hint), BorderLayout.NORTH);
        editor.add(new JScrollPane(text), BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, editor, "Edit value", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) rows.setValueAt(text.getText(), row, 3);
    }

    private void preview() {
        int row = selectedRow();
        if (row < 0) return;
        try {
            HeaderRule rule = ruleAt(row);
            JTextArea preview = textArea(rule.name + ": " + rule.generate(ThreadLocalRandom.current()), 4);
            preview.setEditable(false);
            JOptionPane.showMessageDialog(this, new JScrollPane(preview), "Header preview", JOptionPane.PLAIN_MESSAGE);
        } catch (IllegalArgumentException failure) {
            showError(failure.getMessage());
        }
    }

    private void importText() {
        int row = selectedRow();
        if (row < 0) return;
        var targetRow = rows.getDataVector().get(row);
        files.read(this, "Import text values", false, values -> {
            if (values.isBlank()) throw new IllegalArgumentException("The TXT file has no values.");
            return values;
        }, values -> {
            int index = rowOf(targetRow);
            if (index >= 0) {
                rows.setValueAt(LIST, index, 2);
                rows.setValueAt(values, index, 3);
            }
        });
    }

    private int rowOf(Object target) {
        for (int i = 0; i < rows.getRowCount(); i++) {
            if (rows.getDataVector().get(i) == target) return i;
        }
        return -1;
    }

    private void removeSelected() {
        int row = selectedRow();
        if (row >= 0) rows.removeRow(row);
    }

    private void restore() {
        if (table.isEditing()) table.getCellEditor().cancelCellEditing();
        RuleSettings.Snapshot active = settings.active();
        display(active);
        dirty = false;
        status.setText(active.rules().isEmpty() ? "Add a rule or import YAML to get started."
                : active.enabled() ? "Restored the active rules." : "Rewriting is paused. Select a rule to preview.");
    }

    private void display(RuleSettings.Snapshot snapshot) {
        rows.setRowCount(0);
        snapshot.rules().forEach(rows::addRule);
        enabled.setSelected(snapshot.enabled());
    }

    private void apply() {
        RuleSettings.Snapshot draft;
        try {
            draft = draft();
        } catch (IllegalArgumentException failure) {
            showError(failure.getMessage());
            return;
        }
        try {
            settings.apply(draft);
            dirty = false;
            int count = draft.rules().size();
            status.setText("Saved " + count + (count == 1 ? " rule." : " rules.")
                    + (draft.enabled() ? "" : " Rewriting is paused."));
        } catch (RuntimeException ignored) {
            showError("Could not save the rules. The previous settings are still active.");
        }
    }

    private RuleSettings.Snapshot draft() {
        stopEditing();
        List<HeaderRule> rules = new ArrayList<>();
        for (int row = 0; row < rows.getRowCount(); row++) {
            try {
                rules.add(ruleAt(row));
            } catch (IllegalArgumentException failure) {
                table.setRowSelectionInterval(row, row);
                throw new IllegalArgumentException("Row " + (row + 1) + " — " + failure.getMessage());
            }
        }
        return new RuleSettings.Snapshot(enabled.isSelected(), rules);
    }

    private HeaderRule ruleAt(int row) {
        var tools = EnumSet.noneOf(ToolType.class);
        for (int i = 0; i < HeaderRule.TOOLS.size(); i++) {
            if (Boolean.TRUE.equals(rows.getValueAt(row, FIRST_TOOL_COLUMN + i))) tools.add(HeaderRule.TOOLS.get(i));
        }
        return new HeaderRule(Boolean.TRUE.equals(rows.getValueAt(row, 0)), rows.getValueAt(row, 1).toString(),
                (HeaderRule.Mode) rows.getValueAt(row, 2), rows.getValueAt(row, 3).toString(), tools);
    }

    private int selectedRow() {
        stopEditing();
        int row = table.getSelectedRow();
        if (row < 0) status.setText("Select a rule first.");
        return row;
    }

    private void stopEditing() {
        if (table.isEditing()) table.getCellEditor().stopCellEditing();
    }

    void close() {
        files.close();
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Header Randomizer", JOptionPane.ERROR_MESSAGE);
    }

    private static JTextArea textArea(String value, int rows) {
        JTextArea area = new JTextArea(value, rows, 50);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setCaretPosition(0);
        return area;
    }

    private static JButton addButton(JPanel parent, String label, String tooltip, Runnable action) {
        JButton button = new JButton(label);
        button.setToolTipText(tooltip);
        button.addActionListener(event -> action.run());
        parent.add(button);
        return button;
    }

    private static final class RuleTableModel extends DefaultTableModel {
        RuleTableModel() {
            super(new String[]{"On", "Header", "Generator", "Configuration", "All tools",
                    "Repeater", "Proxy", "Intruder", "Scanner"}, 0);
        }

        void addRule(HeaderRule rule) {
            Object[] cells = new Object[getColumnCount()];
            cells[0] = rule.enabled;
            cells[1] = rule.name;
            cells[2] = rule.mode;
            cells[3] = rule.config;
            for (int i = 0; i < HeaderRule.TOOLS.size(); i++) {
                cells[FIRST_TOOL_COLUMN + i] = rule.tools.contains(HeaderRule.TOOLS.get(i));
            }
            addRow(cells);
        }

        @Override public Class<?> getColumnClass(int column) {
            if (column == 0 || column >= ALL_TOOLS_COLUMN) return Boolean.class;
            return column == 2 ? HeaderRule.Mode.class : String.class;
        }

        @Override public boolean isCellEditable(int row, int column) {
            return column != 3 || getValueAt(row, 2) != LIST;
        }

        @Override public Object getValueAt(int row, int column) {
            if (column != ALL_TOOLS_COLUMN) return super.getValueAt(row, column);
            for (int tool = FIRST_TOOL_COLUMN; tool < getColumnCount(); tool++) {
                if (!Boolean.TRUE.equals(super.getValueAt(row, tool))) return false;
            }
            return true;
        }

        @Override public void setValueAt(Object value, int row, int column) {
            if (column == ALL_TOOLS_COLUMN) {
                for (int tool = FIRST_TOOL_COLUMN; tool < getColumnCount(); tool++) {
                    super.setValueAt(Boolean.TRUE.equals(value), row, tool);
                }
            } else {
                super.setValueAt(value, row, column);
            }
            if (column >= ALL_TOOLS_COLUMN) fireTableCellUpdated(row, ALL_TOOLS_COLUMN);
        }
    }
}
