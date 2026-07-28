package com.mwtstudios.nexuscore.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mwtstudios.nexuscore.storage.JsonStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Hash chaining, tamper detection, and write-time redaction (§15.2). */
class AuditServiceTest {

    @TempDir
    Path directory;

    private JsonStore store;
    private AuditService audit;

    @BeforeEach
    void setUp() {
        store = new JsonStore(directory);
        audit = new AuditService(store, "0.2.0");
    }

    private void write(String action, String target) {
        audit.record(UUID.randomUUID(), "Tester", action, "player", target, "allowed", "because",
                Map.of("k", "v"), UUID.randomUUID().toString());
    }

    @Test
    @DisplayName("an empty log verifies as intact")
    void emptyLogVerifies() {
        assertTrue(audit.verify().intact());
        assertEquals(0, audit.count());
    }

    @Test
    @DisplayName("a written chain verifies as intact")
    void writtenChainVerifies() {
        for (int i = 0; i < 5; i++) {
            write("moderation.ban", "target" + i);
        }

        assertEquals(5, audit.count());
        assertTrue(audit.verify().intact(), audit.verify().detail());
    }

    @Test
    @DisplayName("the first record chains from the genesis hash")
    void firstRecordChainsFromGenesis() {
        write("moderation.ban", "alice");

        assertTrue(store.readLines(AuditService.FILE).get(0).contains(AuditService.GENESIS_HASH));
    }

    @Test
    @DisplayName("editing a record in the middle is detected, and the position is named")
    void editedRecordDetected() throws IOException {
        for (int i = 0; i < 5; i++) {
            write("moderation.ban", "target" + i);
        }
        Path file = directory.resolve(AuditService.FILE);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.set(2, lines.get(2).replace("target2", "somebody-else"));
        Files.write(file, lines, StandardCharsets.UTF_8);

        AuditService.Verification verification = audit.verify();

        assertFalse(verification.intact(), "altering a record must break the chain");
        assertEquals(3, verification.firstBrokenIndex(),
                "the break shows at the record after the edited one, whose predecessor hash no longer matches");
        assertTrue(verification.detail().contains("altered or removed"));
    }

    @Test
    @DisplayName("deleting a record is detected")
    void deletedRecordDetected() throws IOException {
        for (int i = 0; i < 5; i++) {
            write("moderation.ban", "target" + i);
        }
        Path file = directory.resolve(AuditService.FILE);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.remove(2);
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertFalse(audit.verify().intact(), "removing a record must break the chain");
    }

    @Test
    @DisplayName("a record appended by hand without a valid predecessor hash is detected")
    void forgedAppendDetected() throws IOException {
        write("moderation.ban", "alice");
        Files.writeString(directory.resolve(AuditService.FILE),
                "{\"action\":\"moderation.unban\",\"previous_hash\":\"" + "f".repeat(64) + "\"}\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);

        AuditService.Verification verification = audit.verify();

        assertFalse(verification.intact());
        assertEquals(1, verification.firstBrokenIndex());
    }

    @Test
    @DisplayName("the chain continues correctly across a restart")
    void chainContinuesAcrossRestart() {
        write("moderation.ban", "alice");
        write("moderation.ban", "bob");

        AuditService reopened = new AuditService(store, "0.2.0");
        reopened.record(null, "CONSOLE", "moderation.unban", "player", "alice", "allowed", null,
                Map.of(), UUID.randomUUID().toString());

        assertEquals(3, reopened.count());
        assertTrue(reopened.verify().intact(), "a reopened log must continue the existing chain, not restart it");
    }

    @Test
    @DisplayName("sensitive parameters are redacted at write time, so they never reach the file")
    void sensitiveParametersRedactedAtWriteTime() {
        audit.record(UUID.randomUUID(), "Tester", "moderation.banip", "player", "alice", "allowed", null,
                Map.of("ip", "203.0.113.42", "password", "hunter2", "token", "abc123", "reason", "griefing"),
                UUID.randomUUID().toString());

        String line = store.readLines(AuditService.FILE).get(0);

        assertFalse(line.contains("203.0.113.42"), "an IP address must never be written to the audit log");
        assertFalse(line.contains("hunter2"));
        assertFalse(line.contains("abc123"));
        assertTrue(line.contains("[redacted]"));
        assertTrue(line.contains("griefing"), "non-sensitive parameters must still be recorded");
    }

    @Test
    @DisplayName("each record is exactly one line, so the chain is well defined")
    void eachRecordIsOneLine() {
        audit.record(UUID.randomUUID(), "Tester", "test", "player", "alice", "allowed",
                "a reason\nwith a newline", Map.of("multi", "line\nvalue"), UUID.randomUUID().toString());

        assertEquals(1, store.readLines(AuditService.FILE).size());
    }

    @Test
    @DisplayName("disabling the audit service stops writes but leaves verification working")
    void disablingStopsWrites() {
        write("moderation.ban", "alice");
        audit.setEnabled(false);
        write("moderation.ban", "bob");

        assertEquals(1, audit.count());
        assertTrue(audit.verify().intact());
    }

    @Test
    @DisplayName("tail returns the most recent records, newest first")
    void tailReturnsNewestFirst() {
        for (int i = 0; i < 5; i++) {
            write("moderation.ban", "target" + i);
        }

        List<String> tail = audit.tail(2);

        assertEquals(2, tail.size());
        assertTrue(tail.get(0).contains("target4"));
        assertTrue(tail.get(1).contains("target3"));
    }

    // ---- rotation (1.1.4) --------------------------------------------------------------

    /** Writes until the log has been sealed {@code segments} times. */
    private void writeUntilRotated(int segments) {
        audit.setMaxSegmentBytes(1024L);
        for (int i = 0; i < 400 && audit.sealedSegments().size() < segments; i++) {
            write("action-" + i, "target-" + i);
        }
        assertTrue(audit.sealedSegments().size() >= segments,
                "the log should have rotated; got " + audit.sealedSegments().size() + " segment(s)");
    }

    @Test
    @DisplayName("the hash chain survives rotation and verifies across every segment")
    void chainSurvivesRotation() {
        writeUntilRotated(2);
        write("after", "rotation");

        AuditService.Verification result = audit.verify();

        assertTrue(result.intact(), "the chain must cross the segment boundary: " + result.detail());
        assertTrue(result.detail().contains("log file(s)"), "got: " + result.detail());
    }

    @Test
    @DisplayName("a record edited inside a SEALED segment is still detected")
    void tamperingInASealedSegmentIsDetected() throws IOException {
        writeUntilRotated(1);
        write("after", "rotation");
        assertTrue(audit.verify().intact(), "precondition: the chain starts intact");

        // The whole point of the word chain-aware. Verifying only the live log would report
        // "chain intact" over a history whose older half had been rewritten — answering the
        // question wrongly, which is worse than not answering it.
        String sealed = audit.sealedSegments().get(0);
        List<String> lines = Files.readAllLines(directory.resolve(sealed), StandardCharsets.UTF_8);
        lines.set(0, lines.get(0).replace("\"allowed\"", "\"denied\""));
        Files.write(directory.resolve(sealed), lines, StandardCharsets.UTF_8);

        AuditService.Verification result = audit.verify();

        assertFalse(result.intact(), "an edit inside a sealed segment must break verification");
        assertTrue(result.detail().contains(sealed), "the report must name the segment: " + result.detail());
    }

    @Test
    @DisplayName("a deleted sealed segment is detected, not skipped over")
    void deletedSegmentIsDetected() throws IOException {
        writeUntilRotated(2);
        write("after", "rotation");

        Files.delete(directory.resolve(audit.sealedSegments().get(0)));

        assertFalse(audit.verify().intact(), "removing a whole segment must break the chain");
    }

    @Test
    @DisplayName("a restart after rotation resumes the chain rather than starting a new one")
    void restartAfterRotationResumesTheChain() {
        writeUntilRotated(1);
        write("before", "restart");
        long sequenceBefore = audit.count();

        AuditService reopened = new AuditService(store, "0.2.0");
        reopened.setMaxSegmentBytes(1024L);
        reopened.record(UUID.randomUUID(), "Tester", "after", "player", "restart", "allowed", "because",
                Map.of(), UUID.randomUUID().toString());

        assertTrue(reopened.verify().intact(), "a reopened service must chain onto the existing log");
        assertEquals(sequenceBefore + 1, reopened.count(), "numbering must continue, not restart");
    }

    @Test
    @DisplayName("tail reads back across a segment boundary")
    void tailSpansSegments() {
        writeUntilRotated(1);
        write("newest", "record");

        List<String> recent = audit.tail(200);

        assertTrue(recent.size() > 1, "tail must reach past the live log into the sealed segment");
        assertTrue(recent.get(0).contains("newest"), "newest first");
    }

    @Test
    @DisplayName("rotation is off when the limit is zero, so an operator can turn it off")
    void rotationCanBeDisabled() {
        audit.setMaxSegmentBytes(0L);
        for (int i = 0; i < 200; i++) {
            write("action-" + i, "target-" + i);
        }

        assertEquals(List.of(), audit.sealedSegments());
        assertTrue(audit.verify().intact());
    }
}
