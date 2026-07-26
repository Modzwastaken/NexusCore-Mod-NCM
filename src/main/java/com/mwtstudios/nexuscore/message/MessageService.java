package com.mwtstudios.nexuscore.message;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.storage.JsonStore;
import com.mwtstudios.nexuscore.storage.StorageException;

import net.minecraft.network.chat.Component;

import org.slf4j.Logger;

/**
 * Server-side message resolution (ADR-0003).
 *
 * <p>NexusCore v0.1 is server-only: players join with unmodified vanilla clients that have
 * never seen this mod's language file. Sending a {@code TranslatableComponent} would show
 * every player the raw key. So keys are resolved <em>here</em>, against NexusCore's own
 * catalogue, and delivered as already-rendered components.</p>
 *
 * <p>The catalogue is the bundled {@code assets/nexuscore/lang/en_us.json}, overlaid with the
 * operator's {@code messages.json}. An operator can therefore reword anything without
 * touching the JAR, and a key they do not override keeps the shipped wording.</p>
 *
 * <p>Placeholders are named — {@code {target}}, not {@code %s} — because a positional
 * placeholder in an operator-edited file is an invitation to silently swap two arguments.
 * An unknown key renders visibly rather than throwing: a missing message must never take a
 * command down with it (§5, "fail safely").</p>
 */
public final class MessageService {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Bundled catalogue, always present in the JAR. */
    public static final String BUNDLED_RESOURCE = "/assets/nexuscore/lang/en_us.json";

    /** Operator override file under the NexusCore data directory. */
    public static final String OVERRIDE_FILE = "messages.json";

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z0-9_]+)}");

    /** Legacy colour code introducer an operator may use in the override file. */
    private static final char OPERATOR_COLOUR_CHAR = '&';
    private static final char MINECRAFT_COLOUR_CHAR = '§';
    private static final String COLOUR_CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    private final Map<String, String> catalogue = new LinkedHashMap<>();

    /**
     * Loads the bundled catalogue and applies operator overrides on top of it.
     *
     * @param store the data store holding the optional override file
     */
    public MessageService(JsonStore store) {
        loadBundled();
        loadOverrides(store);
    }

    /** @return how many keys are resolvable */
    public int size() {
        return catalogue.size();
    }

    /** @return true when the catalogue knows this key */
    public boolean has(String key) {
        return catalogue.containsKey(key);
    }

    /**
     * Resolves a key into plain text.
     *
     * @param key catalogue key
     * @param args alternating placeholder name and value, for example {@code "target", "Steve"}
     * @return the rendered text, with colour codes translated
     */
    public String raw(String key, Object... args) {
        String template = catalogue.get(key);
        if (template == null) {
            LOGGER.warn("NexusCore message key '{}' is missing from the catalogue", key);
            return colourise("&c[" + key + "]" + renderArgsForMissingKey(args));
        }
        return colourise(substitute(template, toMap(args)));
    }

    /**
     * Resolves a key into a component ready to send to any client, modded or vanilla.
     *
     * @param key catalogue key
     * @param args alternating placeholder name and value
     * @return a literal component carrying the resolved text
     */
    public Component render(String key, Object... args) {
        return Component.literal(raw(key, args));
    }

    private static Map<String, String> toMap(Object... args) {
        if (args.length % 2 != 0) {
            // A programming error here must not take down a command; report and degrade.
            LOGGER.warn("NexusCore message arguments must be name/value pairs, got {} value(s)", args.length);
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            map.put(String.valueOf(args[i]), String.valueOf(args[i + 1]));
        }
        return map;
    }

    private static String renderArgsForMissingKey(Object... args) {
        if (args.length == 0) {
            return "";
        }
        StringBuilder text = new StringBuilder(" ");
        for (int i = 0; i + 1 < args.length; i += 2) {
            text.append(args[i]).append('=').append(args[i + 1]).append(' ');
        }
        return text.toString().stripTrailing();
    }

    /**
     * Replaces {@code {name}} placeholders. A placeholder with no supplied value is left
     * visible rather than blanked, so the gap is obvious in testing instead of silent in
     * production.
     */
    private static String substitute(String template, Map<String, String> args) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = args.get(name);
            matcher.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : "{" + name + "}"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Translates operator-friendly {@code &a} colour codes into the section-sign form the
     * client renders. A trailing lone {@code &} is left alone rather than swallowed.
     *
     * @param text text possibly containing {@code &} colour codes
     * @return the same text with valid codes converted
     */
    public static String colourise(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length - 1; i++) {
            if (chars[i] == OPERATOR_COLOUR_CHAR && COLOUR_CODES.indexOf(chars[i + 1]) >= 0) {
                chars[i] = MINECRAFT_COLOUR_CHAR;
            }
        }
        return new String(chars);
    }

    private void loadBundled() {
        try (InputStream stream = MessageService.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (stream == null) {
                throw new IOException("resource not found in the JAR");
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                merge(JsonParser.parseReader(reader).getAsJsonObject());
            }
        } catch (IOException | RuntimeException e) {
            // The bundled catalogue is part of the artifact. If it is unreadable the JAR is
            // broken, and pretending otherwise would produce a server where every message is
            // a bracketed key. Report it loudly and carry on with an empty catalogue.
            LOGGER.error("NexusCore could not read its bundled message catalogue {}", BUNDLED_RESOURCE, e);
        }
    }

    private void loadOverrides(JsonStore store) {
        if (!store.exists(OVERRIDE_FILE)) {
            return;
        }
        try {
            JsonObject overrides = store.read(OVERRIDE_FILE, JsonObject.class, JsonObject::new);
            int before = catalogue.size();
            merge(overrides);
            LOGGER.info("NexusCore applied {} message override(s) from {}", catalogue.size() - before + overrides.size(),
                    OVERRIDE_FILE);
        } catch (StorageException e) {
            LOGGER.error("NexusCore could not read {}; shipped wording will be used instead", OVERRIDE_FILE, e);
        }
    }

    private void merge(JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                catalogue.put(entry.getKey(), value.getAsString());
            } else {
                LOGGER.warn("NexusCore message key '{}' is not a string and was ignored", entry.getKey());
            }
        }
    }
}
