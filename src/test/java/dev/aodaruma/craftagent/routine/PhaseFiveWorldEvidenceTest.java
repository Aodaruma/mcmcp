package dev.aodaruma.craftagent.routine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhaseFiveWorldEvidenceTest {
    @Test
    void cropNeedsPositiveCollectionEvidenceAfterReplantVerification() {
        assertThat(PhaseFiveWorldEvidence.cropGoal(1, 1, 0)).isFalse();
        assertThat(PhaseFiveWorldEvidence.cropGoal(1, 1, 1)).isTrue();
    }

    @Test
    void treeClaimsOnlyAllDeclaredLogsAndReplantedDeclaredTrees() {
        assertThat(PhaseFiveWorldEvidence.treeGoal(2, 3, 1, 1)).isFalse();
        assertThat(PhaseFiveWorldEvidence.treeGoal(3, 3, 0, 1)).isFalse();
        assertThat(PhaseFiveWorldEvidence.treeGoal(3, 3, 1, 1)).isTrue();
    }

    @Test
    void surveyUsesCurrentSamplesAgainstTheRequestedDenominator() {
        assertThat(PhaseFiveWorldEvidence.surveyGoal(0, 1, 4)).isFalse();
        assertThat(PhaseFiveWorldEvidence.surveyGoal(1, 1, 4)).isTrue();
        assertThat(PhaseFiveWorldEvidence.surveyGoal(5, 1, 4)).isFalse();
    }

    @Test
    void sleepCannotCompleteBeforeBothServerPoseEdgesAndReturn() {
        assertThat(PhaseFiveWorldEvidence.sleepGoal(true, false, true)).isFalse();
        assertThat(PhaseFiveWorldEvidence.sleepGoal(true, true, false)).isFalse();
        assertThat(PhaseFiveWorldEvidence.sleepGoal(true, true, true)).isTrue();
    }
}
