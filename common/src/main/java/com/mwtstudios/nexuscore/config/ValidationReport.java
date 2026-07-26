package com.mwtstudios.nexuscore.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of validating a configuration document.
 *
 * <p>M1 requires that a corrupted config produce a report naming <em>the file, the key, the
 * invalid value, the expected form, and the chosen fallback</em> — and that the server keep
 * running. A boolean "config was bad" tells an operator nothing they can act on, so every
 * finding carries all five fields.</p>
 */
public final class ValidationReport {

    /**
     * One rejected value and what was done about it.
     *
     * @param file the file the problem was found in
     * @param key the dotted key path, for example {@code teleport.warmupSeconds}
     * @param invalidValue the value as it was found
     * @param expected a human-readable description of what would have been accepted
     * @param fallback the value actually used instead
     */
    public record Finding(String file, String key, String invalidValue, String expected, String fallback) {

        @Override
        public String toString() {
            return file + ": key '" + key + "' had invalid value " + invalidValue
                    + " (expected " + expected + "); using " + fallback + " instead";
        }
    }

    private final String file;
    private final List<Finding> findings = new ArrayList<>();

    /**
     * @param file the configuration file being validated
     */
    public ValidationReport(String file) {
        this.file = file;
    }

    /**
     * Records a rejected value and the fallback chosen for it.
     *
     * @param key the dotted key path
     * @param invalidValue the value as it was found
     * @param expected what would have been accepted
     * @param fallback the value used instead
     */
    public void reject(String key, Object invalidValue, String expected, Object fallback) {
        findings.add(new Finding(file, key, String.valueOf(invalidValue), expected, String.valueOf(fallback)));
    }

    /** @return every finding, in the order they were discovered */
    public List<Finding> findings() {
        return Collections.unmodifiableList(findings);
    }

    /** @return true when nothing was rejected */
    public boolean isClean() {
        return findings.isEmpty();
    }

    /** @return the file this report describes */
    public String file() {
        return file;
    }

    /**
     * @return a multi-line operator-facing summary, or a single clean line when there is
     *     nothing to report
     */
    public String render() {
        if (findings.isEmpty()) {
            return file + ": validated, no problems found";
        }
        StringBuilder text = new StringBuilder(file + ": " + findings.size() + " invalid value(s), each replaced with a safe default:");
        for (Finding finding : findings) {
            text.append("\n  - key '").append(finding.key()).append("' was ").append(finding.invalidValue())
                    .append("; expected ").append(finding.expected())
                    .append("; using ").append(finding.fallback());
        }
        return text.toString();
    }
}
