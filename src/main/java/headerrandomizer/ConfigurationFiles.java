package headerrandomizer;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Component;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class ConfigurationFiles {
    private static final int MAX_INPUT_BYTES = 1024 * 1024;
    private SwingWorker<?, Void> active;

    <T> void read(Component parent, String title, boolean yaml, Function<String, T> parse, Consumer<T> onSuccess) {
        if (busy(parent)) return;
        JFileChooser chooser = chooser(title, yaml);
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        Path source = chooser.getSelectedFile().toPath();
        run(parent, () -> parse.apply(readUtf8(source)), onSuccess);
    }

    void writeYaml(Component parent, String title, String filename, Supplier<String> content, Runnable onSuccess) {
        if (busy(parent)) return;
        JFileChooser chooser = chooser(title, true);
        chooser.setSelectedFile(new File(filename));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath().toAbsolutePath();
        boolean replace = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (replace && JOptionPane.showConfirmDialog(parent, "Replace the existing file?", "Confirm replacement",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        run(parent, () -> {
            writeUtf8(target, content.get(), replace);
            return null;
        }, ignored -> onSuccess.run());
    }

    void close() {
        if (active != null) active.cancel(true);
    }

    private boolean busy(Component parent) {
        if (active == null) return false;
        error(parent, "A file operation is already in progress.");
        return true;
    }

    private <T> void run(Component parent, Callable<T> work, Consumer<T> onSuccess) {
        SwingWorker<T, Void> worker = new SwingWorker<>() {
            @Override protected T doInBackground() throws Exception {
                return work.call();
            }

            @Override protected void done() {
                active = null;
                if (isCancelled()) return;
                try {
                    onSuccess.accept(get());
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException failure) {
                    if (failure.getCause() instanceof IllegalArgumentException invalid) {
                        error(parent, invalid.getMessage());
                    } else {
                        error(parent, "Could not read or write the file. Check its location and UTF-8 encoding.");
                    }
                } catch (RuntimeException ignored) {
                    error(parent, "Could not save the imported settings.");
                }
            }
        };
        active = worker;
        worker.execute();
    }

    private static JFileChooser chooser(String title, boolean yaml) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileFilter(yaml ? new FileNameExtensionFilter("YAML files", "yaml", "yml")
                : new FileNameExtensionFilter("Text files", "txt"));
        return chooser;
    }

    private static String readUtf8(Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            byte[] bytes = stream.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length > MAX_INPUT_BYTES) throw new IllegalArgumentException("Choose a file up to 1 MiB.");
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            return text.startsWith("\ufeff") ? text.substring(1) : text;
        }
    }

    private static void writeUtf8(Path target, String text, boolean replace) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), ".header-randomizer-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            if (replace) {
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void error(Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "Header Randomizer", JOptionPane.ERROR_MESSAGE);
    }
}
