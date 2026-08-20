package dev.aodaruma.craftagent.runtime;

import dev.aodaruma.craftagent.mixin.client.ClientLevelPredictionAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Client-thread bridge between vanilla block prediction and server confirmation.
 *
 * <p>A predicted local state is never confirmation. A successful confirmation requires both
 * a block-change acknowledgement covering an issued prediction sequence and a subsequent
 * {@link ClientLevel#setServerVerifiedBlockState(BlockPos, BlockState, int)} observation for the
 * exact target. Callers must still supply the semantic postcondition for that server state.</p>
 */
public final class ClientPredictionSignals {
    public static final String SUPPORTED_MINECRAFT_VERSION = "26.2";
    public static final int MAX_ACTIVE_ATTEMPTS_PER_LEVEL = 32;

    private static final ClientPredictionSignals GLOBAL = new ClientPredictionSignals();

    private final Object gate = new Object();
    private final Map<ClientLevel, LevelChannel> channels = new WeakHashMap<>();

    public static ClientPredictionSignals global() {
        return GLOBAL;
    }

    /** Called only by the required client mixin after a ClientLevel was fully constructed. */
    public void onBridgeReady(ClientLevel level, String minecraftVersion) {
        Objects.requireNonNull(level, "level");
        synchronized (gate) {
            var old = channels.put(level, new LevelChannel(
                    SUPPORTED_MINECRAFT_VERSION.equals(minecraftVersion), minecraftVersion));
            if (old != null) {
                old.closeAll();
            }
        }
    }

    /** Called by the client mixin after vanilla accepted a server-supplied state. */
    public void onServerVerifiedBlockState(ClientLevel level, BlockPos position, BlockState state) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(state, "state");
        synchronized (gate) {
            var channel = channels.get(level);
            if (channel == null || !channel.compatible) {
                return;
            }
            final int currentSequence;
            try {
                currentSequence = currentSequence(level, channel);
            } catch (PredictionBridgeException failure) {
                return;
            }
            for (var attempt : channel.attempts) {
                if (attempt.target.equals(position)) {
                    try {
                        if (attempt.latch.observeCurrentSequence(currentSequence)) {
                            attempt.latch.serverVerified(state);
                        }
                    } catch (PredictionBridgeException failure) {
                        channel.disable();
                        return;
                    }
                }
            }
        }
    }

    /** Called by the client mixin after vanilla retired predictions through {@code sequence}. */
    public void onBlockChangedAck(ClientLevel level, int sequence) {
        Objects.requireNonNull(level, "level");
        synchronized (gate) {
            var channel = channels.get(level);
            if (channel == null || !channel.compatible) {
                return;
            }
            final int currentSequence;
            try {
                currentSequence = currentSequence(level, channel);
            } catch (PredictionBridgeException failure) {
                return;
            }
            for (var attempt : channel.attempts) {
                try {
                    if (attempt.latch.observeCurrentSequence(currentSequence)) {
                        attempt.latch.acknowledge(sequence);
                    }
                } catch (PredictionBridgeException failure) {
                    channel.disable();
                    return;
                }
            }
        }
    }

    public PredictionAttempt begin(ClientLevel level, BlockPos target, long clientTick) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(target, "target");
        assertCurrentClientLevel(level);
        synchronized (gate) {
            var channel = requireCompatibleChannel(level);
            if (channel.attempts.size() >= MAX_ACTIVE_ATTEMPTS_PER_LEVEL) {
                throw new PredictionBridgeException("too many active prediction attempts");
            }
            // Reading through the accessor here proves that the required Mixin is actually live
            // before a routine transitions into its mutating phase.
            int initialSequence = currentSequence(level, channel);
            var state = new AttemptState(
                    target.immutable(), new ConfirmationLatch<>(clientTick, initialSequence));
            channel.attempts.add(state);
            return new PredictionAttempt(this, new WeakReference<>(level), channel, state);
        }
    }

    /** Explicit lifecycle fence for disconnects and dimension replacement. */
    public void closeLevel(ClientLevel level) {
        if (level == null) {
            return;
        }
        synchronized (gate) {
            var channel = channels.remove(level);
            if (channel != null) {
                channel.closeAll();
            }
        }
    }

    private int currentSequence(ClientLevel level, LevelChannel expectedChannel) {
        assertCurrentClientLevel(level);
        synchronized (gate) {
            if (channels.get(level) != expectedChannel || !expectedChannel.compatible) {
                throw new PredictionBridgeException("prediction bridge is no longer active for this level");
            }
            try {
                if (!(level instanceof ClientLevelPredictionAccessor accessor)) {
                    throw new LinkageError("ClientLevel prediction accessor was not applied");
                }
                return accessor.craftagent$getBlockStatePredictionHandler().currentSequence();
            } catch (RuntimeException | LinkageError failure) {
                expectedChannel.disable();
                throw new PredictionBridgeException("prediction bridge is incompatible", failure);
            }
        }
    }

    private int sequenceBeforePrediction(
            ClientLevel level,
            LevelChannel expectedChannel,
            AttemptState state) {
        int current = currentSequence(level, expectedChannel);
        synchronized (gate) {
            if (!expectedChannel.attempts.contains(state)) {
                throw new PredictionBridgeException("prediction attempt is closed");
            }
            state.latch.beforePrediction(current);
            return current;
        }
    }

    private int captureIssuedPredictions(
            ClientLevel level,
            LevelChannel expectedChannel,
            AttemptState state) {
        int currentSequence = currentSequence(level, expectedChannel);
        synchronized (gate) {
            if (!expectedChannel.attempts.contains(state)) {
                throw new PredictionBridgeException("prediction attempt is closed");
            }
            return state.latch.captureIssuedPredictions(currentSequence);
        }
    }

    private Confirmation<BlockState> confirmation(
            LevelChannel expectedChannel,
            AttemptState state,
            Predicate<BlockState> postcondition) {
        synchronized (gate) {
            if (!expectedChannel.compatible) {
                return state.latch.incompatibleConfirmation();
            }
            return state.latch.confirmation(postcondition);
        }
    }

    private void close(LevelChannel expectedChannel, AttemptState state) {
        synchronized (gate) {
            expectedChannel.attempts.remove(state);
            state.latch.close();
        }
    }

    private LevelChannel requireCompatibleChannel(ClientLevel level) {
        var channel = channels.get(level);
        if (channel == null) {
            throw new PredictionBridgeException(
                    "prediction bridge is unavailable; the required client mixin did not bind this level");
        }
        if (!channel.compatible) {
            throw new PredictionBridgeException(
                    "prediction bridge only supports Minecraft " + SUPPORTED_MINECRAFT_VERSION
                            + " (detected " + sanitizeVersion(channel.detectedVersion) + ")");
        }
        return channel;
    }

    private static String sanitizeVersion(String version) {
        if (version == null || version.isBlank()) {
            return "unknown";
        }
        var normalized = version.replaceAll("[\\p{Cntrl}]", " ").strip();
        return normalized.substring(0, Math.min(normalized.length(), 32));
    }

    private static void assertCurrentClientLevel(ClientLevel level) {
        var minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            throw new PredictionBridgeException("prediction bridge must run on the Minecraft client thread");
        }
        if (minecraft.level != level) {
            throw new PredictionBridgeException("prediction bridge level is not the current client level");
        }
    }

    public enum ConfirmationStatus {
        NO_PREDICTION,
        WAITING_SERVER_STATE,
        WAITING_ACK,
        SERVER_STATE_MISMATCH,
        CONFIRMED,
        INCOMPATIBLE,
        CLOSED
    }

    public record Confirmation<S>(
            ConfirmationStatus status,
            Integer issuedSequence,
            Integer acknowledgedSequence,
            Integer stateRequiredSequence,
            S serverState,
            boolean postconditionObserved,
            long startedClientTick) {
        public boolean serverConfirmed() {
            return status == ConfirmationStatus.CONFIRMED;
        }
    }

    /** One target-scoped confirmation window; callers close it on every terminal path. */
    public static final class PredictionAttempt implements AutoCloseable {
        private final ClientPredictionSignals owner;
        private final WeakReference<ClientLevel> level;
        private final LevelChannel channel;
        private final AttemptState state;
        private boolean closed;

        private PredictionAttempt(
                ClientPredictionSignals owner,
                WeakReference<ClientLevel> level,
                LevelChannel channel,
                AttemptState state) {
            this.owner = owner;
            this.level = level;
            this.channel = channel;
            this.state = state;
        }

        /** Sample immediately before invoking a vanilla action which should start one prediction. */
        public int sequenceBeforePrediction() {
            requireOpen();
            return owner.sequenceBeforePrediction(requireLevel(), channel, state);
        }

        /**
         * Samples vanilla's current prediction sequence after the leased attack input was handled.
         * Vanilla may delay the first prediction owned by this attempt for a few ticks (for
         * example while its attack delay expires), so samples may remain stable until the first
         * owned advance. Every advance must still be exactly one sequence. After a regenerated
         * same-position target, that first owned advance can itself be STOP_DESTROY_BLOCK.
         */
        public int captureIssuedPredictions() {
            requireOpen();
            return owner.captureIssuedPredictions(requireLevel(), channel, state);
        }

        public Confirmation<BlockState> confirmation(Predicate<BlockState> postcondition) {
            Objects.requireNonNull(postcondition, "postcondition");
            requireOpen();
            return owner.confirmation(channel, state, postcondition);
        }

        public BlockPos target() {
            return state.target;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.close(channel, state);
        }

        private ClientLevel requireLevel() {
            var current = level.get();
            if (current == null) {
                close();
                throw new PredictionBridgeException("prediction level is no longer available");
            }
            return current;
        }

        private void requireOpen() {
            if (closed) {
                throw new PredictionBridgeException("prediction attempt is closed");
            }
        }
    }

    /** Package-private pure state machine to keep acknowledgement semantics unit-testable. */
    static final class ConfirmationLatch<S> {
        private static final int MAX_SERVER_STATES_PER_PREDICTION = 32;

        private final long startedClientTick;
        private final ArrayList<StateEvidence<S>> serverStates = new ArrayList<>();
        private Integer issuedSequence;
        private Integer acknowledgedSequence;
        private Integer stateRequiredSequence;
        private S serverState;
        private Integer expectedBeforeSequence;
        private boolean incompatible;
        private boolean closed;

        ConfirmationLatch(long startedClientTick) {
            this(startedClientTick, null);
        }

        ConfirmationLatch(long startedClientTick, Integer initialSequence) {
            this.startedClientTick = startedClientTick;
            expectedBeforeSequence = initialSequence;
        }

        void beforePrediction(int sequence) {
            if (closed || incompatible) {
                throw new PredictionBridgeException("prediction confirmation is not active");
            }
            if (expectedBeforeSequence != null && expectedBeforeSequence != sequence) {
                incompatible = true;
                throw new PredictionBridgeException(
                        "an untracked prediction changed the sequence; refusing action");
            }
        }

        void predictionIssued(int sequenceBefore, int sequenceAfter) {
            if (closed || incompatible) {
                throw new PredictionBridgeException("prediction confirmation is not active");
            }
            if ((expectedBeforeSequence != null && expectedBeforeSequence != sequenceBefore)
                    || sequenceAfter != sequenceBefore + 1) {
                incompatible = true;
                throw new PredictionBridgeException(
                        "prediction sequence did not advance exactly once; refusing confirmation");
            }
            issuedSequence = sequenceAfter;
            expectedBeforeSequence = sequenceAfter;
            // A later prediction (notably STOP_DESTROY_BLOCK after START_DESTROY_BLOCK)
            // supersedes state evidence from the earlier action. ACK watermark may remain,
            // but it cannot cover a later state's required sequence.
            stateRequiredSequence = null;
            serverState = null;
            serverStates.clear();
        }

        int captureIssuedPredictions(int currentSequence) {
            if (closed || incompatible) {
                throw new PredictionBridgeException("prediction confirmation is not active");
            }
            if (issuedSequence == null) {
                int before = Objects.requireNonNull(expectedBeforeSequence, "initial prediction sequence");
                if (currentSequence == before) {
                    // The attack key is owned, but vanilla has not emitted this attempt's first
                    // prediction yet (which may be START or a regenerated target's STOP).
                    // Do not accept ACK or block-state evidence until the sequence actually advances.
                    return currentSequence;
                }
                int expected = before + 1;
                if (currentSequence != expected) {
                    incompatible = true;
                    throw new PredictionBridgeException(
                            "initial prediction sequence advanced outside the owned attack; refusing confirmation");
                }
                predictionIssued(before, currentSequence);
                return currentSequence;
            }
            if (currentSequence == issuedSequence) {
                return currentSequence;
            }
            if (currentSequence != issuedSequence + 1) {
                incompatible = true;
                throw new PredictionBridgeException(
                        "prediction sequence advanced outside the owned attack; refusing confirmation");
            }
            predictionIssued(issuedSequence, currentSequence);
            return currentSequence;
        }

        boolean observeCurrentSequence(int currentSequence) {
            if (closed || incompatible) {
                return false;
            }
            if (issuedSequence == null
                    && Objects.equals(expectedBeforeSequence, currentSequence)) {
                return false;
            }
            captureIssuedPredictions(currentSequence);
            return true;
        }

        void acknowledge(int sequence) {
            // An ACK observed before this attempt issued anything is unrelated evidence.
            if (closed || incompatible || issuedSequence == null) {
                return;
            }
            if (acknowledgedSequence == null
                    || Integer.compareUnsigned(sequence, acknowledgedSequence) > 0) {
                acknowledgedSequence = sequence;
            }
        }

        void serverVerified(S state) {
            if (closed || incompatible || issuedSequence == null) {
                return;
            }
            serverState = Objects.requireNonNull(state, "state");
            if (serverStates.size() < MAX_SERVER_STATES_PER_PREDICTION) {
                serverStates.add(new StateEvidence<>(serverState, issuedSequence));
            }
            stateRequiredSequence = issuedSequence;
        }

        Confirmation<S> confirmation(Predicate<S> postcondition) {
            Objects.requireNonNull(postcondition, "postcondition");
            if (incompatible) {
                return incompatibleConfirmation();
            }
            if (closed) {
                return snapshot(ConfirmationStatus.CLOSED);
            }
            if (issuedSequence == null) {
                return snapshot(ConfirmationStatus.NO_PREDICTION);
            }
            if (stateRequiredSequence == null) {
                return snapshot(ConfirmationStatus.WAITING_SERVER_STATE);
            }
            StateEvidence<S> matchingState = null;
            for (var observed : serverStates) {
                if (postcondition.test(observed.state())) {
                    matchingState = observed;
                    break;
                }
            }
            int requiredSequence = matchingState == null
                    ? stateRequiredSequence
                    : matchingState.requiredSequence();
            if (acknowledgedSequence == null
                    || Integer.compareUnsigned(acknowledgedSequence, requiredSequence) < 0) {
                return matchingState == null
                        ? snapshot(ConfirmationStatus.WAITING_ACK)
                        : snapshot(
                                ConfirmationStatus.WAITING_ACK,
                                matchingState.requiredSequence(),
                                matchingState.state(),
                                true);
            }
            if (matchingState != null) {
                return snapshot(
                        ConfirmationStatus.CONFIRMED,
                        matchingState.requiredSequence(),
                        matchingState.state(),
                        true);
            }
            return snapshot(ConfirmationStatus.SERVER_STATE_MISMATCH);
        }

        Confirmation<S> incompatibleConfirmation() {
            return snapshot(ConfirmationStatus.INCOMPATIBLE);
        }

        void incompatible() {
            incompatible = true;
        }

        void close() {
            closed = true;
        }

        private Confirmation<S> snapshot(ConfirmationStatus status) {
            return snapshot(status, stateRequiredSequence, serverState, false);
        }

        private Confirmation<S> snapshot(
                ConfirmationStatus status,
                Integer requiredSequence,
                S reportedState,
                boolean postconditionObserved) {
            Integer reportedSequence = requiredSequence == null ? issuedSequence : requiredSequence;
            return new Confirmation<>(status, reportedSequence, acknowledgedSequence,
                    requiredSequence, reportedState, postconditionObserved, startedClientTick);
        }

        private record StateEvidence<S>(S state, int requiredSequence) {
        }
    }

    private static final class LevelChannel {
        private boolean compatible;
        private final String detectedVersion;
        private final Set<AttemptState> attempts = Collections.newSetFromMap(new IdentityHashMap<>());

        private LevelChannel(boolean compatible, String detectedVersion) {
            this.compatible = compatible;
            this.detectedVersion = detectedVersion;
        }

        private void disable() {
            compatible = false;
            for (var attempt : attempts) {
                attempt.latch.incompatible();
            }
        }

        private void closeAll() {
            for (var attempt : new ArrayList<>(attempts)) {
                attempt.latch.close();
            }
            attempts.clear();
        }
    }

    private static final class AttemptState {
        private final BlockPos target;
        private final ConfirmationLatch<BlockState> latch;

        private AttemptState(BlockPos target, ConfirmationLatch<BlockState> latch) {
            this.target = target;
            this.latch = latch;
        }
    }

    public static final class PredictionBridgeException extends IllegalStateException {
        public PredictionBridgeException(String message) {
            super(message);
        }

        public PredictionBridgeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
