package headerrandomizer;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;
import burp.api.montoya.http.handler.HttpHandler;
import burp.api.montoya.http.handler.HttpRequestToBeSent;
import burp.api.montoya.http.handler.HttpResponseReceived;
import burp.api.montoya.http.handler.RequestToBeSentAction;
import burp.api.montoya.http.handler.ResponseReceivedAction;

import javax.swing.SwingUtilities;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class HeaderRandomizerExtension implements BurpExtension {
    private static final String NAME = "Header Randomizer";

    @Override public void initialize(MontoyaApi api) {
        api.extension().setName(NAME);
        var preferences = api.persistence().preferences();
        RuleSettings settings = new RuleSettings(preferences);
        try {
            settings.load();
        } catch (RuntimeException ignored) {
            api.logging().logToError("Could not load saved rules. The extension started without active rules.");
        }

        AtomicBoolean reportedError = new AtomicBoolean();
        Registration http = api.http().registerHttpHandler(new HttpHandler() {
            @Override public RequestToBeSentAction handleHttpRequestToBeSent(HttpRequestToBeSent request) {
                try {
                    return RequestToBeSentAction.continueWith(RequestRules.apply(
                            request, request.toolSource().toolType(), settings.active(), ThreadLocalRandom.current()),
                            request.annotations());
                } catch (RuntimeException ignored) {
                    if (reportedError.compareAndSet(false, true)) {
                        api.logging().logToError("A header rule could not be applied. Continuing with the original request.");
                    }
                    return RequestToBeSentAction.continueWith(request);
                }
            }

            @Override public ResponseReceivedAction handleHttpResponseReceived(HttpResponseReceived response) {
                return ResponseReceivedAction.continueWith(response, response.annotations());
            }
        });

        AtomicBoolean unloaded = new AtomicBoolean();
        AtomicReference<Runnable> closeTab = new AtomicReference<>(() -> {});
        api.extension().registerUnloadingHandler(() -> {
            unloaded.set(true);
            try {
                http.deregister();
            } finally {
                SwingUtilities.invokeLater(() -> closeTab.get().run());
            }
        });
        SwingUtilities.invokeLater(() -> {
            if (unloaded.get()) return;
            RulesPanel panel = new RulesPanel(settings);
            api.userInterface().applyThemeToComponent(panel);
            Registration tab = api.userInterface().registerSuiteTab(NAME, panel);
            closeTab.set(() -> {
                panel.close();
                tab.deregister();
            });
        });
        api.logging().logToOutput(NAME + " loaded. New rules default to Repeater.");
    }
}
