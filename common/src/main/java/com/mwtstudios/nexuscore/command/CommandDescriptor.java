package com.mwtstudios.nexuscore.command;

/**
 * One command, described once, for every purpose that needs describing.
 *
 * <p>§12.5 warns that hand-maintained command documentation drifts, and it was right: before this
 * record existed, `docs/admin/commands.md` still told operators that NexusCore does <b>not</b>
 * override {@code /ban}, {@code /kick} and {@code /banlist} — which stopped being true at v1.0 when
 * the vanilla takeover shipped. The document had been wrong for two releases, in the one section it
 * told the reader to read first.</p>
 *
 * <p>Descriptors are the single source for the permission-filtered {@code /nexus help} output and
 * for the generated reference document, so the two cannot disagree, and tests assert the descriptor
 * set matches the permission nodes the code actually checks.</p>
 *
 * @param canonical the full canonical form, for example {@code /nexus teleport home [name]}
 * @param alias the short form if one is registered, else null. Aliases fall back to an {@code n}
 *     prefix when vanilla or another mod owns the name, so this is what NexusCore <em>asks</em> for
 * @param node the permission node checked on the server
 * @param summary a lower-case phrase completing "this command will …"
 * @param module the owning module id, so safe mode can say which commands are unavailable
 */
public record CommandDescriptor(String canonical, String alias, String node, String summary, String module) {

    /** Module id for commands that are always present. */
    public static final String CORE = "core";

    /**
     * @param canonical the canonical form
     * @param alias the short form
     * @param node the permission node
     * @param summary the description
     * @param module the owning module
     * @return a descriptor
     */
    public static CommandDescriptor of(String canonical, String alias, String node, String summary, String module) {
        return new CommandDescriptor(canonical, alias, node, summary, module);
    }

    /**
     * @param canonical the canonical form
     * @param node the permission node
     * @param summary the description
     * @param module the owning module
     * @return a descriptor with no short alias
     */
    public static CommandDescriptor of(String canonical, String node, String summary, String module) {
        return new CommandDescriptor(canonical, null, node, summary, module);
    }

    /** @return true when a short alias is registered for this command */
    public boolean hasAlias() {
        return alias != null;
    }
}
