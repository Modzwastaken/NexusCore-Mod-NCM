package com.mwtstudios.nexuscore.config;

import java.util.concurrent.atomic.AtomicReference;

import com.mojang.logging.LogUtils;
import com.mwtstudios.nexuscore.storage.JsonStore;
import com.mwtstudios.nexuscore.storage.StorageException;

import org.slf4j.Logger;

/**
 * Loads, validates, and reloads the NexusCore settings document.
 *
 * <p>Reload is transactional: the new document is parsed and validated <em>before</em> it
 * replaces the live one. A reload that fails leaves the previously good settings in place and
 * reports why, so a typo in a config file can never take a running server's configuration
 * away from it.</p>
 *
 * <p>The live settings are held in an {@link AtomicReference} and always replaced wholesale.
 * Readers therefore see a consistent snapshot rather than a document being mutated underneath
 * them mid-command.</p>
 */
public final class ConfigurationService {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final JsonStore store;
    private final String modVersion;
    private final AtomicReference<NexusSettings> live = new AtomicReference<>(new NexusSettings());

    /**
     * @param store the data store the settings document lives in
     * @param modVersion recorded into the document as {@code generatedByVersion}
     */
    public ConfigurationService(JsonStore store, String modVersion) {
        this.store = store;
        this.modVersion = modVersion;
    }

    /** @return the current settings snapshot; never null */
    public NexusSettings settings() {
        return live.get();
    }

    /**
     * Loads the settings for the first time, generating defaults if the file is absent.
     *
     * @return the validation report for whatever was loaded
     */
    public ValidationReport load() {
        boolean firstRun = !store.exists(NexusSettings.FILE);
        ReloadResult result = readAndValidate();

        if (result.failure() != null) {
            LOGGER.error("NexusCore could not read {}: {}", NexusSettings.FILE, result.failure());
            LOGGER.error("Starting with built-in defaults. The unreadable file has been preserved; "
                    + "repair it and run /nexus reload.");
            NexusSettings defaults = new NexusSettings();
            defaults.generatedByVersion = modVersion;
            live.set(defaults);
            return defaults.validate(NexusSettings.FILE);
        }

        live.set(result.settings());
        persist(result.settings());

        if (firstRun) {
            LOGGER.info("NexusCore generated default settings at {}", store.root().resolve(NexusSettings.FILE));
        }
        logReport(result.report());
        return result.report();
    }

    /**
     * Re-reads the settings from disk.
     *
     * <p>On failure the live settings are left untouched, which is the whole point of doing
     * this transactionally.</p>
     *
     * @return the outcome, carrying either a report or a failure message
     */
    public ReloadResult reload() {
        ReloadResult result = readAndValidate();
        if (result.failure() != null) {
            LOGGER.error("NexusCore reload refused: {}", result.failure());
            return result;
        }
        live.set(result.settings());
        persist(result.settings());
        logReport(result.report());
        return result;
    }

    private ReloadResult readAndValidate() {
        try {
            NexusSettings loaded = store.read(NexusSettings.FILE, NexusSettings.class, NexusSettings::new);
            ValidationReport report = loaded.validate(NexusSettings.FILE);
            loaded.generatedByVersion = modVersion;
            return new ReloadResult(loaded, report, null);
        } catch (StorageException e) {
            return new ReloadResult(null, null, e.getMessage());
        }
    }

    private void persist(NexusSettings settings) {
        try {
            store.write(NexusSettings.FILE, settings);
        } catch (StorageException e) {
            // Not fatal: the server can run on validated in-memory settings. But the operator
            // must be told, because their edits are not being normalised back to disk.
            LOGGER.error("NexusCore could not write {} back to disk. Settings are live in memory only.",
                    NexusSettings.FILE, e);
        }
    }

    private static void logReport(ValidationReport report) {
        if (report.isClean()) {
            LOGGER.info("NexusCore configuration validated: no problems found");
            return;
        }
        LOGGER.warn("NexusCore configuration validation found {} problem(s):", report.findings().size());
        for (ValidationReport.Finding finding : report.findings()) {
            LOGGER.warn("  {}", finding);
        }
    }

    /**
     * The outcome of a load or reload.
     *
     * @param settings the validated settings, or null when the attempt failed
     * @param report the validation report, or null when the attempt failed
     * @param failure why the attempt failed, or null when it succeeded
     */
    public record ReloadResult(NexusSettings settings, ValidationReport report, String failure) {

        /** @return true when new settings were produced */
        public boolean succeeded() {
            return failure == null;
        }
    }
}
