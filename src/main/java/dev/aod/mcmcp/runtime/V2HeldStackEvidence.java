package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.runtime.ContainerSyncSignals.StackFingerprint;

/** Only a newer server payload matching the current hand can prove a held-item result. */
final class V2HeldStackEvidence {
    private final StackFingerprint before;
    private final long beforeRevision;
    private StackFingerprint after;

    V2HeldStackEvidence(StackFingerprint before, long beforeRevision) {
        this.before = before;
        this.beforeRevision = beforeRevision;
    }

    void observe(HotbarPayloadSyncSignals.SlotEvidence evidence, StackFingerprint current) {
        after = evidence != null && evidence.revision() > beforeRevision
                && evidence.stack().equals(current) ? evidence.stack() : null;
    }

    StackFingerprint after() { return after; }
    boolean changed() { return after != null && !after.equals(before); }
    boolean matches(String item) { return after != null && item.equals(after.itemId()); }
}
