package com.mwtstudios.nexuscore.module;

/**
 * A module wiring fault: a dependency cycle, a dependency that was never registered, a duplicate
 * id, or a request for a service whose module did not start.
 *
 * <p>Unchecked, because every one of these is a programming error in the bootstrap rather than a
 * condition an operator can do anything about or a caller can recover from. It is thrown at
 * startup, loudly, with the ids involved named.</p>
 */
public final class ModuleException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what was wrong, naming the module ids involved
     */
    public ModuleException(String message) {
        super(message);
    }
}
