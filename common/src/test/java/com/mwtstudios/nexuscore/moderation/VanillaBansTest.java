package com.mwtstudios.nexuscore.moderation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.mwtstudios.nexuscore.storage.JsonStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two-system lift and the merged ban list.
 *
 * <p>The defect: NexusCore shadows {@code /pardon} and {@code /banlist}, but a server that
 * predates NexusCore carries bans in vanilla's list, which vanilla still enforces at login. The
 * takeover pardon lifted only NexusCore records — so a pre-takeover ban could never be lifted by
 * anyone — and the takeover banlist hid entries that were actively keeping players out.</p>
 */
class VanillaBansTest {

    /** Vanilla ban state a test can put entries into. */
    private static final class FakeVanilla implements VanillaBans {
        final List<String> names = new ArrayList<>();
        final List<String> ips = new ArrayList<>();
        final List<UUID> uuids = new ArrayList<>();
        int pardons;

        void ban(UUID uuid, String name) {
            uuids.add(uuid);
            names.add(name);
        }

        @Override
        public boolean pardon(UUID uuid, String name) {
            int index = uuids.indexOf(uuid);
            if (index < 0) {
                return false;
            }
            uuids.remove(index);
            names.remove(index);
            pardons++;
            return true;
        }

        @Override
        public Optional<UUID> uuidOfBanned(String name) {
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))) {
                    return Optional.of(uuids.get(i));
                }
            }
            return Optional.empty();
        }

        @Override
        public List<String> bannedNames() {
            return List.copyOf(names);
        }

        @Override
        public List<String> bannedIps() {
            return List.copyOf(ips);
        }
    }

    @TempDir
    Path directory;

    private ModerationService moderation;
    private FakeVanilla vanilla;
    private final UUID target = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private final AtomicLong now = new AtomicLong(1_000_000L);

    @BeforeEach
    void setUp() {
        moderation = new ModerationService(new JsonStore(directory), now::get);
        vanilla = new FakeVanilla();
    }

    @Test
    @DisplayName("regression: a pre-takeover vanilla ban can be lifted at all")
    void aPreTakeoverVanillaBanIsLiftable() {
        vanilla.ban(target, "alice");

        VanillaBans.LiftOutcome outcome =
                VanillaBans.liftBan(moderation, vanilla, target, "alice", actor, "Mod");

        assertEquals(VanillaBans.LiftOutcome.VANILLA_ONLY, outcome,
                "the shadowed vanilla pardon was the only command able to lift this ban, "
                        + "and it no longer exists — the takeover pardon must do it");
        assertTrue(vanilla.bannedNames().isEmpty(), "the vanilla entry must actually be removed");
    }

    @Test
    @DisplayName("a ban held by both systems is lifted from both, or the player stays banned")
    void aBanInBothSystemsIsLiftedFromBoth() {
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "grief", Long.MAX_VALUE);
        vanilla.ban(target, "alice");

        VanillaBans.LiftOutcome outcome =
                VanillaBans.liftBan(moderation, vanilla, target, "alice", actor, "Mod");

        assertEquals(VanillaBans.LiftOutcome.BOTH, outcome);
        assertTrue(moderation.activeBan(target).isEmpty(), "the NexusCore ban must be lifted");
        assertTrue(vanilla.bannedNames().isEmpty(),
                "vanilla still enforces its list at login — lifting only NexusCore's record "
                        + "reports success about a player who remains banned");
    }

    @Test
    @DisplayName("nothing anywhere is still a refusal")
    void nothingToLiftAnywhere() {
        assertEquals(VanillaBans.LiftOutcome.NOTHING_TO_LIFT,
                VanillaBans.liftBan(moderation, vanilla, target, "alice", actor, "Mod"));
        assertEquals(0, vanilla.pardons);
    }

    @Test
    @DisplayName("a NexusCore-only ban lifts exactly as before")
    void aNexusOnlyBanLiftsAsBefore() {
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "grief", Long.MAX_VALUE);

        assertEquals(VanillaBans.LiftOutcome.NEXUS_ONLY,
                VanillaBans.liftBan(moderation, vanilla, target, "alice", actor, "Mod"));
        assertTrue(moderation.activeBan(target).isEmpty());
    }

    @Test
    @DisplayName("regression: the ban list shows vanilla entries NexusCore does not hold")
    void banListShowsVanillaOnlyEntries() {
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "grief", Long.MAX_VALUE);
        vanilla.ban(UUID.randomUUID(), "bob");

        List<String> extra = VanillaBans.vanillaOnlyNames(moderation.activeBans(), vanilla);

        assertEquals(List.of("bob"), extra,
                "an entry vanilla enforces at login was invisible to the takeover /banlist");
    }

    @Test
    @DisplayName("a player banned in both systems is listed once, not twice")
    void aDoubleBannedPlayerIsListedOnce() {
        moderation.issue(ModerationService.Type.BAN, target, "alice", actor, "Mod", "grief", Long.MAX_VALUE);
        vanilla.ban(target, "Alice"); // vanilla kept a different case

        assertTrue(VanillaBans.vanillaOnlyNames(moderation.activeBans(), vanilla).isEmpty(),
                "names are matched case-insensitively, or every migrated ban shows twice");
    }

    @Test
    @DisplayName("the uuid of a pre-takeover ban resolves locally from vanilla's own entry")
    void uuidResolvesFromTheVanillaEntry() {
        vanilla.ban(target, "alice");

        assertEquals(Optional.of(target), vanilla.uuidOfBanned("ALICE"),
                "vanilla's entry is often the only local record of a player who never joined "
                        + "while NexusCore was installed — demanding a Mojang lookup to pardon "
                        + "them would be absurd");
    }
}
