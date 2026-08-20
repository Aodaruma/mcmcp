package dev.aodaruma.craftagent.routine;

/** Pure terminal evidence rules shared by the Minecraft adapter and focused tests. */
final class PhaseFiveWorldEvidence {
    private PhaseFiveWorldEvidence() {}

    static boolean cropGoal(int verifiedPlots, int minimumPlots, int collectionProofs) {
        return verifiedPlots >= minimumPlots && collectionProofs >= verifiedPlots;
    }

    static boolean treeGoal(
            int verifiedLogs, int declaredLogs, int replantedTrees, int declaredTrees) {
        return verifiedLogs == declaredLogs && replantedTrees == declaredTrees;
    }

    static boolean surveyGoal(int currentSamples, int minimumSamples, int requestedSamples) {
        return requestedSamples > 0 && currentSamples >= minimumSamples
                && currentSamples <= requestedSamples;
    }

    static boolean sleepGoal(boolean sleepPoseObserved, boolean wakeObserved, boolean returned) {
        return sleepPoseObserved && wakeObserved && returned;
    }
}
