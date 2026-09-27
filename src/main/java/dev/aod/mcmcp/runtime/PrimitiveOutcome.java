package dev.aod.mcmcp.runtime;

import dev.aod.mcmcp.agent.action.AgentActionStore;
import java.util.List;

/** Primitiveの進行結果。terminal公開と次nodeへの遷移はActionの調整役が行う。 */
record PrimitiveOutcome(boolean complete, AgentActionStore.Failure failure, String replanEvidence) {
    static PrimitiveOutcome running() { return new PrimitiveOutcome(false, null, null); }
    static PrimitiveOutcome succeeded() { return new PrimitiveOutcome(true, null, null); }
    static PrimitiveOutcome replan(String evidence) { return new PrimitiveOutcome(false, null, evidence); }
    static PrimitiveOutcome failed(AgentActionStore.FailureCode code, boolean recoverable,
            String evidence, String... diagnostics) {
        var details = new java.util.ArrayList<String>(1 + diagnostics.length);
        details.add(evidence);
        details.addAll(List.of(diagnostics));
        return new PrimitiveOutcome(false,
                new AgentActionStore.Failure(code, recoverable, details), null);
    }
}
