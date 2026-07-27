package com.mwtstudios.nexuscore.storage;

import java.util.ArrayList;
import java.util.List;

/**
 * A set of document writes that take effect together or not at all (§11.1).
 *
 * <p>Build one with {@link JournalService#begin()}, add every document the operation touches, then
 * {@link #commit()}. Either every document is on disk afterwards or none of them is — including
 * across a crash, a power loss, or a kill in the middle of the write.</p>
 *
 * <pre>{@code
 * Transaction txn = journal.begin();
 * txn.put("economy/accounts.json", accounts);
 * txn.put("economy/ledger.json", ledger);
 * txn.commit();
 * }</pre>
 *
 * <p>Nothing is written until {@link #commit()}, so an abandoned transaction costs nothing and
 * needs no rollback call. Single-document writes should keep using {@link JsonStore#write}, which
 * is already atomic on its own; this exists for the case where two files must agree.</p>
 *
 * <p>Not thread-safe. Build and commit a transaction on one thread.</p>
 */
public final class Transaction {

    private final JournalService journal;
    private final String id;
    private final List<String> targets = new ArrayList<>();
    private final List<Object> documents = new ArrayList<>();
    private boolean committed;

    Transaction(JournalService journal, String id) {
        this.journal = journal;
        this.id = id;
    }

    /** @return this transaction's id, which names its journal record */
    public String id() {
        return id;
    }

    /**
     * Adds a document to the transaction.
     *
     * @param name file name relative to the data root, as {@link JsonStore#write} takes
     * @param document the document to serialise
     * @return this transaction, for chaining
     * @throws StorageException if the transaction is already committed, or the same name is added twice
     */
    public Transaction put(String name, Object document) {
        if (committed) {
            throw new StorageException("transaction " + id + " is already committed and cannot be added to");
        }
        if (name != null && name.contains(JournalService.STAGING_INFIX)) {
            // Recovery deletes every leftover file whose name contains this marker, on the
            // reasoning that it is scratch nobody owns. A target carrying the marker would be
            // real data caught by that sweep, so the name is refused rather than the sweep
            // weakened — the marker is an internal detail and no caller needs it in a file name.
            throw new StorageException("a document name may not contain '" + JournalService.STAGING_INFIX
                    + "', which is reserved for journal staging files, got: " + name);
        }
        if (targets.contains(name)) {
            // Silently keeping the last value would make which write survives depend on call
            // order in a way no caller could see. A transaction that writes one file twice is a
            // bug in the caller, so it is reported as one.
            throw new StorageException("transaction " + id + " already writes " + name
                    + "; a transaction must name each document once");
        }
        targets.add(name);
        documents.add(document);
        return this;
    }

    /**
     * Commits the transaction durably.
     *
     * <p>On return, every document is on disk. If this throws, either nothing was written, or the
     * transaction reached its commit point and will be completed at the next start — the message
     * says which, because for a caller moving money the difference decides whether to retry.</p>
     *
     * @throws StorageException if the transaction cannot be staged or applied
     */
    public void commit() {
        if (committed) {
            throw new StorageException("transaction " + id + " is already committed");
        }
        committed = true;
        journal.commit(id, targets, documents);
    }
}
