package headerrandomizer;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpHeader;
import burp.api.montoya.http.message.requests.HttpRequest;

import java.util.List;
import java.util.random.RandomGenerator;

final class RequestRules {
    static HttpRequest apply(HttpRequest request, ToolType tool, RuleSettings.Snapshot snapshot, RandomGenerator random) {
        if (!snapshot.enabled()) return request;

        HttpRequest updated = request;
        for (HeaderRule rule : snapshot.rules()) {
            if (!rule.enabled || !rule.tools.contains(tool)) continue;
            List<HttpHeader> matches = updated.headers().stream()
                    .filter(header -> header.name().equalsIgnoreCase(rule.name)).toList();
            String value = rule.generate(random);
            if (matches.isEmpty()) {
                updated = updated.withAddedHeader(rule.name, value);
            } else if (matches.size() == 1) {
                HttpHeader original = matches.get(0);
                if (!original.value().equals(value)) updated = updated.withUpdatedHeader(original.name(), value);
            } else {
                updated = updated.withRemovedHeaders(matches).withAddedHeader(rule.name, value);
            }
        }
        return updated;
    }
}
