# Header Randomizer for Burp Suite

Header Randomizer applies user-defined HTTP request header rules through the Montoya API. A fresh installation shows five disabled examples for Repeater, one per generator. Select any example and click **Preview** to generate a value locally. Upgrading an older installation adds the missing example headers once and keeps your saved rules and rewrite setting. You can remove examples with **Remove selected**, then **Apply & save**.

## Build and install

Download `header-randomizer.jar` from the [latest release](https://github.com/mtkilicc/burp-header-randomizer/releases/latest). In Burp, open **Extensions > Installed > Add**, choose **Java**, and select the JAR.

To build from source, run the Gradle wrapper with Java 17 or later.

```sh
./gradlew build
```

The build runs the checks and writes `dist/header-randomizer.jar`.

## Rules

1. Open the **Header Randomizer** tab and choose **Add rule** or **Import YAML**.
2. Choose Repeater, Proxy, Intruder, or Scanner for each rule. **All tools** selects those four tools for that row.
3. Use **Edit value** to change the configuration, **Import TXT** to load a UTF-8 value list, and **Preview** to sample a value locally.
4. Turn on **On** for the rows you want to run and select **Enable request rewriting**. Choose **Apply & save** to activate the draft.

**YAML template** saves a [starter file](src/main/resources/headerrandomizer/rules-template.yaml) with rewriting paused. **Import YAML** loads the file into the draft. **Export YAML** exports the current table. **Revert edits** restores the applied settings.

### Rule YAML fields

| Field | Meaning |
|---|---|
| `version` | Use `1` for this format. |
| Top-level `enabled` | Controls all rules. The default is `false` during import. |
| `rules` | List of rules in the order they appear in the table. |
| Rule-level `enabled` | Controls one row. The default is `true`. |
| `header` | Request header name without a colon, for example `Accept-Language`. |
| `generator` | How the rule produces a header value. See the generator table below. |
| `value` | Exact text for `fixed`, a length from 1 to 4096 for `digits`, or a template string for `template`. |
| `values` | List of single-line values for the `list` generator. One entry is picked for each matching request. |
| `tools` | Use `all` or a list such as `[repeater, proxy]`. The default is `[repeater]`. |

Quote values you want to keep as literal text, such as `"001"` or `"true"`.

When multiple active rules target the same header, the last applicable rule wins. Java rules are saved in the extension's own preferences.

### Choose a generator

| Generator | Starter header | When to use it |
|---|---|---|
| `fixed` | `X-Demo-Mode` | Send the same complete value on every matching request. |
| `list` | `User-Agent` | Randomly pick one complete value from a set. |
| `digits` | `X-Demo-Request-Id` | Generate a string with an exact number of random digits, including possible leading zeros. |
| `template` | `X-Forwarded-For` | Keep literal text and generate the parts in braces. |
| `ipv4` | `X-Demo-Client-Ip` | Generate four random IPv4 octets, each from 1 through 254. |

Open **?** next to **YAML template** to browse template examples. Choose an example to see its header, template, and sample value. **Generate again** shows another local sample. **Add to draft** inserts a disabled Repeater rule or selects the existing row if the same template is already in the table.

In `build-{digits:4}-{num:1-3}`, the prefix and hyphens stay fixed. `{digits:4}` produces four digits, and `{num:1-3}` picks an integer from 1 through 3. `{octet}` produces a number from 1 through 254 and `{ip}` produces an IPv4 value.

The starter `User-Agent` rule chooses between two fictional client strings. The `X-Forwarded-For` rule uses `192.0.2.{octet}` to show how a template keeps a prefix. The range is reserved for documentation. [RFC 5737](https://www.rfc-editor.org/rfc/rfc5737.html)

## User-Agent lists

Select a `User-Agent` rule with the **Random from list** generator. Choose **Edit value** to enter one full User-Agent string per line, or use **Import TXT**. In rule YAML, put the strings under `values`.

## Stored data

The bundled examples and tests use synthetic data. Burp stores applied rules in the extension's preferences. YAML exports include the values shown in the draft table. The extension log contains startup status and generic errors.

## Testing workflows

- **Response formats and locales.** Use a fixed `Accept` or `Accept-Language` rule in Repeater. Compare status, `Content-Type`, and `Content-Language` for the same request. [HTTP Semantics](https://www.rfc-editor.org/rfc/rfc9110.html#name-content-negotiation-fields)
- **Cache variants.** Use fixed values when checking whether a response varies with a header. Compare `Vary` and cache behavior across the same URL. [HTTP Caching](https://www.rfc-editor.org/rfc/rfc9111.html#name-calculating-cache-keys-with)
- **Regression reproduction.** Export one focused rule set per scenario. Use a fixed value for exact reproduction and a list for sampling variants in Repeater. [Burp Repeater](https://portswigger.net/burp/documentation/desktop/tools/repeater/http-messages)

## Third-party notices

The JAR bundles SnakeYAML Engine 3.2 under the Apache License, Version 2.0. [Notices](src/main/resources/META-INF/THIRD-PARTY-NOTICES.txt) · [License](src/main/resources/META-INF/LICENSE-snakeyaml-engine.txt)
