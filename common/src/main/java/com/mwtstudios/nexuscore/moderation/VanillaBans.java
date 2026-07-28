package com.mwtstudios.nexuscore.moderation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.UserBanList;

/**
 * Vanilla's own ban state, behind a seam.
 *
 * <p>NexusCore takes over {@code /ban}, {@code /pardon} and {@code /banlist}, but a server that
 * existed before NexusCore carries bans in <em>vanilla's</em> list ({@code banned-players.json}),
 * which vanilla still enforces at login. A takeover {@code /pardon} that only lifts NexusCore
 * records leaves those players banned with no command able to free them — the shadowed vanilla
 * pardon was the only thing that could, and it no longer exists.</p>
 *
 * <p>This is an interface for the same reason {@code IdentityService.ProfileLookup} is: the
 * production implementation needs a running {@link MinecraftServer}, and the composition logic
 * deserves tests that do not.</p>
 */
public interface VanillaBans {

    /**
     * Removes a vanilla ban entry, if one exists.
     *
     * @param uuid the player
     * @param name their name, for the profile vanilla keys the entry by
     * @return true when an entry existed and was removed
     */
    boolean pardon(UUID uuid, String name);

    /**
     * Finds the UUID vanilla's ban list holds for a name. A pre-takeover ban is often the
     * <em>only</em> local record of a player who never joined while NexusCore was installed, so
     * this is consulted before any network lookup.
     *
     * @param name the name as typed
     * @return the banned player's UUID, when vanilla has an entry under that name
     */
    Optional<UUID> uuidOfBanned(String name);

    /** @return the names on vanilla's player ban list */
    List<String> bannedNames();

    /** @return the addresses on vanilla's IP ban list, which NexusCore does not take over */
    List<String> bannedIps();

    /**
     * The real thing, over the running server's lists.
     *
     * @param server the running server
     * @return vanilla's ban state
     */
    static VanillaBans of(MinecraftServer server) {
        return new VanillaBans() {
            @Override
            public boolean pardon(UUID uuid, String name) {
                UserBanList bans = server.getPlayerList().getBans();
                GameProfile profile = new GameProfile(uuid, name);
                if (bans.get(profile) == null) {
                    return false;
                }
                bans.remove(profile);
                return true;
            }

            @Override
            public Optional<UUID> uuidOfBanned(String name) {
                // StoredUserEntry.getUser() is not public, so the entries cannot be walked
                // through the API. The list persists to banned-players.json in the server
                // directory; reading vanilla's own file is local, non-blocking, and the same
                // bytes vanilla enforces from at login.
                java.nio.file.Path file = server.getServerDirectory().resolve("banned-players.json");
                if (!java.nio.file.Files.isRegularFile(file)) {
                    return Optional.empty();
                }
                try {
                    var array = new com.google.gson.Gson().fromJson(
                            java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8),
                            com.google.gson.JsonArray.class);
                    if (array == null) {
                        return Optional.empty();
                    }
                    for (var element : array) {
                        var entry = element.getAsJsonObject();
                        // get()-null checks rather than has(): SafeModeGuardTest scans every
                        // main source for has("...") calls to keep module-id guards honest,
                        // and a Gson has() would read to it as a module id.
                        var bannedName = entry.get("name");
                        var bannedUuid = entry.get("uuid");
                        if (bannedName != null && bannedUuid != null
                                && bannedName.getAsString().toLowerCase(Locale.ROOT)
                                        .equals(name.toLowerCase(Locale.ROOT))) {
                            return Optional.of(UUID.fromString(bannedUuid.getAsString()));
                        }
                    }
                } catch (RuntimeException | java.io.IOException e) {
                    // An unreadable vanilla file must not break /pardon for everyone else;
                    // the caller falls back to the async lookup path.
                    return Optional.empty();
                }
                return Optional.empty();
            }

            @Override
            public List<String> bannedNames() {
                return List.of(server.getPlayerList().getBans().getUserList());
            }

            @Override
            public List<String> bannedIps() {
                return List.of(server.getPlayerList().getIpBans().getUserList());
            }
        };
    }

    /** How a lift attempt fell across the two ban systems. */
    enum LiftOutcome {
        /** No ban anywhere — the refusal case. */
        NOTHING_TO_LIFT,
        /** A NexusCore punishment was lifted; vanilla held no entry. */
        NEXUS_ONLY,
        /** Only vanilla held an entry — a pre-takeover ban, now freed. */
        VANILLA_ONLY,
        /** Both systems held one; both are lifted, so the player is genuinely free. */
        BOTH
    }

    /**
     * Lifts a ban from <em>both</em> systems, which is what an operator typing {@code /pardon}
     * means. Lifting only NexusCore's record while vanilla still refuses the login would report
     * success about a player who remains banned — the defect this exists to close.
     *
     * @param moderation NexusCore's punishment records
     * @param vanilla vanilla's ban state
     * @param target the player
     * @param name their name as typed
     * @param actor the lifting operator's UUID, null for the console
     * @param actorName the lifting operator's display name
     * @return where the lift landed
     */
    static LiftOutcome liftBan(ModerationService moderation, VanillaBans vanilla,
            UUID target, String name, UUID actor, String actorName) {
        boolean nexus = moderation.lift(ModerationService.Type.BAN, target, actor, actorName).isPresent();
        boolean legacy = vanilla.pardon(target, name);
        if (nexus && legacy) {
            return LiftOutcome.BOTH;
        }
        if (nexus) {
            return LiftOutcome.NEXUS_ONLY;
        }
        return legacy ? LiftOutcome.VANILLA_ONLY : LiftOutcome.NOTHING_TO_LIFT;
    }

    /**
     * The names vanilla's list holds that NexusCore's active bans do not — the entries a
     * records-only {@code /banlist} silently hid.
     *
     * @param nexusBans NexusCore's active bans
     * @param vanilla vanilla's ban state
     * @return vanilla-only names, in vanilla's order
     */
    static List<String> vanillaOnlyNames(List<ModerationService.Record> nexusBans, VanillaBans vanilla) {
        List<String> extra = new ArrayList<>();
        for (String name : vanilla.bannedNames()) {
            boolean known = nexusBans.stream().anyMatch(record -> record.targetName() != null
                    && record.targetName().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)));
            if (!known) {
                extra.add(name);
            }
        }
        return extra;
    }
}
