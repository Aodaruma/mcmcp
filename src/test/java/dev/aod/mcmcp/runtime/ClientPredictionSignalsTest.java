package dev.aod.mcmcp.runtime;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientPredictionSignalsTest {
    @Test
    void acknowledgementWithoutPredictionCannotConfirm() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.acknowledge(7);
        latch.serverVerified("air");

        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.NO_PREDICTION);
    }

    @Test
    void staleAcknowledgementBeforePredictionIsNotReused() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.acknowledge(7);
        latch.predictionIssued(6, 7);
        latch.serverVerified("air");

        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.WAITING_ACK);
    }

    @Test
    void predictionAndAcknowledgementStillRequireServerState() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.predictionIssued(6, 7);
        latch.acknowledge(7);

        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.WAITING_SERVER_STATE);
    }

    @Test
    void predictionAndServerStateStillRequireCoveringAcknowledgement() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.predictionIssued(6, 7);
        latch.serverVerified("air");
        latch.acknowledge(6);

        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.WAITING_ACK);
    }

    @Test
    void confirmsOnlyMatchingServerStateAfterCoveredPrediction() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.predictionIssued(6, 7);
        latch.acknowledge(9);
        latch.serverVerified("air");

        var confirmation = latch.confirmation("air"::equals);
        assertThat(confirmation.status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.CONFIRMED);
        assertThat(confirmation.serverConfirmed()).isTrue();
        assertThat(confirmation.issuedSequence()).isEqualTo(7);
        assertThat(confirmation.stateRequiredSequence()).isEqualTo(7);
    }

    @Test
    void reportsServerStateMismatchInsteadOfSuccess() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.predictionIssued(6, 7);
        latch.serverVerified("stone");
        latch.acknowledge(7);

        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.SERVER_STATE_MISMATCH);
    }

    @Test
    void retainsAConfirmedTransitionWhenTheBlockRegeneratesBeforePolling() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.predictionIssued(6, 7);
        latch.serverVerified("air");
        latch.serverVerified("cobblestone");
        latch.acknowledge(7);

        var confirmation = latch.confirmation("air"::equals);
        assertThat(confirmation.status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.CONFIRMED);
        assertThat(confirmation.serverState()).isEqualTo("air");
    }

    @Test
    void exposesASeenTransitionBeforeAckSoAttackInputCanStop() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.predictionIssued(6, 7);
        latch.serverVerified("air");
        latch.serverVerified("cobblestone");

        var confirmation = latch.confirmation("air"::equals);
        assertThat(confirmation.status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.WAITING_ACK);
        assertThat(confirmation.postconditionObserved()).isTrue();
        assertThat(confirmation.serverState()).isEqualTo("air");
    }

    @Test
    void ignoresServerStateObservedBeforeAnyIssuedPrediction() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.serverVerified("air");
        latch.predictionIssued(6, 7);
        latch.acknowledge(7);

        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.WAITING_SERVER_STATE);
    }

    @Test
    void unexpectedSequenceAdvanceFailsClosed() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        assertThatThrownBy(() -> latch.predictionIssued(6, 8))
                .isInstanceOf(ClientPredictionSignals.PredictionBridgeException.class)
                .hasMessageContaining("exactly once");
        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.INCOMPATIBLE);
    }

    @Test
    void untrackedPredictionBetweenRenewalsFailsClosed() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12, 6);
        latch.beforePrediction(6);
        latch.predictionIssued(6, 7);

        assertThatThrownBy(() -> latch.beforePrediction(8))
                .isInstanceOf(ClientPredictionSignals.PredictionBridgeException.class);
        assertThat(latch.confirmation("air"::equals).serverConfirmed()).isFalse();
    }

    @Test
    void completionTransitionRequiresTheLaterStopPredictionAck() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        // START_DESTROY_BLOCK is acknowledged first, but a non-instant block is actually
        // broken by a later STOP_DESTROY_BLOCK prediction.
        latch.predictionIssued(6, 7);
        latch.acknowledge(7);
        latch.predictionIssued(7, 8);
        latch.serverVerified("air");

        var waiting = latch.confirmation("air"::equals);
        assertThat(waiting.status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.WAITING_ACK);
        assertThat(waiting.issuedSequence()).isEqualTo(8);
        assertThat(waiting.stateRequiredSequence()).isEqualTo(8);
        assertThat(waiting.postconditionObserved()).isTrue();

        latch.acknowledge(8);
        var confirmed = latch.confirmation("air"::equals);
        assertThat(confirmed.status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.CONFIRMED);
        assertThat(confirmed.issuedSequence()).isEqualTo(8);
    }

    @Test
    void laterPredictionDiscardsStateButRetainsTheCumulativeAckWatermark() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.predictionIssued(6, 7);
        latch.serverVerified("air");
        latch.acknowledge(8);
        assertThat(latch.confirmation("air"::equals).serverConfirmed()).isTrue();

        latch.predictionIssued(7, 8);
        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.WAITING_SERVER_STATE);

        latch.serverVerified("air");
        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.CONFIRMED);
        assertThat(latch.confirmation("air"::equals).stateRequiredSequence()).isEqualTo(8);
    }

    @Test
    void stopTransitionSurvivesImmediateRegenerationUntilItsAck() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12);

        latch.predictionIssued(6, 7);
        latch.acknowledge(7);
        latch.predictionIssued(7, 8);
        latch.serverVerified("air");
        latch.serverVerified("cobblestone");

        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.WAITING_ACK);

        latch.acknowledge(8);
        var confirmed = latch.confirmation("air"::equals);
        assertThat(confirmed.status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.CONFIRMED);
        assertThat(confirmed.serverState()).isEqualTo("air");
        assertThat(confirmed.stateRequiredSequence()).isEqualTo(8);
    }

    @Test
    void postTickSamplingAllowsOnlyOneOwnedAdvanceAtATime() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12, 6);

        assertThat(latch.captureIssuedPredictions(7)).isEqualTo(7);
        assertThat(latch.captureIssuedPredictions(7)).isEqualTo(7);
        assertThat(latch.captureIssuedPredictions(8)).isEqualTo(8);

        assertThatThrownBy(() -> latch.captureIssuedPredictions(10))
                .isInstanceOf(ClientPredictionSignals.PredictionBridgeException.class)
                .hasMessageContaining("outside the owned attack");
        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.INCOMPATIBLE);
    }

    @Test
    void delayedInitialPredictionIgnoresEvidenceUntilTheFirstSingleAdvance() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12, 6);

        assertThat(latch.captureIssuedPredictions(6)).isEqualTo(6);
        assertThat(latch.captureIssuedPredictions(6)).isEqualTo(6);
        latch.acknowledge(7);
        latch.serverVerified("air");
        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.NO_PREDICTION);

        assertThat(latch.captureIssuedPredictions(7)).isEqualTo(7);
        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.WAITING_SERVER_STATE);
    }

    @Test
    void delayedInitialPredictionStillRejectsSkippingASequence() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12, 6);

        assertThat(latch.captureIssuedPredictions(6)).isEqualTo(6);
        assertThatThrownBy(() -> latch.captureIssuedPredictions(8))
                .isInstanceOf(ClientPredictionSignals.PredictionBridgeException.class)
                .hasMessageContaining("outside the owned attack");
        assertThat(latch.confirmation("air"::equals).status())
                .isEqualTo(ClientPredictionSignals.ConfirmationStatus.INCOMPATIBLE);
    }

    @Test
    void ackOnlyBarrierRequiresTheIssuedUseSequenceToBeCovered() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12, 6);
        latch.beforePrediction(6);
        latch.predictionIssued(6, 7);

        assertThat(latch.acknowledgement().status())
                .isEqualTo(ClientPredictionSignals.AcknowledgementStatus.WAITING_ACK);
        latch.acknowledge(6);
        assertThat(latch.acknowledgement().status())
                .isEqualTo(ClientPredictionSignals.AcknowledgementStatus.WAITING_ACK);
        latch.acknowledge(7);
        assertThat(latch.acknowledgement().status())
                .isEqualTo(ClientPredictionSignals.AcknowledgementStatus.ACKNOWLEDGED);
    }

    @Test
    void ackOnlyBarrierTreatsLevelIdentityLossAsReleaseProof() {
        var latch = new ClientPredictionSignals.ConfirmationLatch<String>(12, 6);
        latch.predictionIssued(6, 7);

        latch.releaseIdentity();

        assertThat(latch.acknowledgement().status())
                .isEqualTo(ClientPredictionSignals.AcknowledgementStatus.IDENTITY_RELEASED);
    }
}
