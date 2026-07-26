package com.mwtstudios.nexuscore.storage;

/**
 * Raised when a storage operation cannot be completed safely.
 *
 * <p>This is deliberately unchecked and deliberately never swallowed. §2.1 Rule 4 makes
 * catching a failure and continuing as though it succeeded a truth-rule violation, so a
 * caller either handles this and reports the failure to the operator, or lets it
 * propagate.</p>
 */
public class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message what could not be done, and to which path
     */
    public StorageException(String message) {
        super(message);
    }

    /**
     * @param message what could not be done, and to which path
     * @param cause the underlying I/O failure
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
