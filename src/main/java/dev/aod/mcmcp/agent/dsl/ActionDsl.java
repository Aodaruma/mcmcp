package dev.aod.mcmcp.agent.dsl;

import dev.aod.mcmcp.brewing.StandardPotionStackSpec;
import dev.aod.mcmcp.redstone.RedstoneSpec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Pure, immutable Action DSL v1 syntax tree. */
public final class ActionDsl {
    private ActionDsl() {
    }

    public record Request(int schemaVersion, Program program, Budget budget) {
        public Request {
            Objects.requireNonNull(program, "program");
            Objects.requireNonNull(budget, "budget");
        }
    }

    public record Program(
            int dslVersion,
            Optional<String> name,
            Set<Capability> capabilities,
            List<Node> body) {
        public Program {
            name = Objects.requireNonNull(name, "name");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
            body = List.copyOf(Objects.requireNonNull(body, "body"));
        }
    }

    public sealed interface Node permits NavigateToKnown, ApproachKnownSurface,
            ApproachKnownPlacement,
            FaceKnownPosition, FaceKnownBlockFace, BreakKnownFace, BreakKnownBlock,
            OperateKnownCobblestoneGenerator,
            HoldBoundedInputs,
            TillKnownBlock, TillKnownBatch, PlantKnownWheat, PlantKnownWheatBatch,
            HarvestKnownWheat, HarvestKnownWheatBatch, ApplyKnownBlockPlan,
            ClearKnownBlockPlan, PillarUpKnown,
            ApplyKnownRedstoneSpec,
            OpenKnownFenceGate,
            OpenKnownPassage, InspectKnownContainer, TakeKnownContainerStack,
            StoreKnownContainerStack,
            RemoveVisibleFrameItem, InsertVisibleFrameItem,
            CraftKnownRecipe,
            SmeltKnownRecipe,
            OperateKnownMenu,
            BrewKnownPotionBatch,
            CollectVisibleItem, CollectVisibleItemBatch,
            CastKnownFishingRod, ReelKnownFishingSession,
            OperateKillZone,
            WaitTicks, WaitUntil, If, Repeat {
        String id();
    }

    public record NavigateToKnown(String id, Position target, double tolerance) implements Node {
        public NavigateToKnown {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * Moves to a policy-known safe feet cell from which the observed block is geometrically
     * within normal interaction reach. The caller supplies a block position, never a derived
     * navigation coordinate; a fresh observation is still required before a later mutation.
     */
    public record ApproachKnownSurface(
            String id, Position target, String expectedBlock) implements Node {
        public ApproachKnownSurface {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
        }
    }

    /**
     * Moves to one policy-known safe feet cell from which every entry in a later stationary
     * placement plan has a reachable support ray and a settlement-safe placement heading.
     */
    public record ApproachKnownPlacement(
            String id,
            Position anchor,
            BlockPlanTransform transform,
            List<BlockPlanEntry> entries) implements Node {
        public ApproachKnownPlacement {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(transform, "transform");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    /**
     * Turns toward a policy-delivered coordinate so a later observation can refresh its rays.
     * This node changes only the camera; every later interaction still needs current evidence.
     */
    public record FaceKnownPosition(String id, Position target) implements Node {
        public FaceKnownPosition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
        }
    }

    /**
     * Turns toward the center of one exact block face backed by unexpired delivered evidence.
     * This node changes only the camera and never authorizes a block interaction or mutation.
     */
    public record FaceKnownBlockFace(
            String id,
            Position target,
            BlockFace face,
            String expectedBlock) implements Node {
        public FaceKnownBlockFace {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
        }
    }

    public record BreakKnownFace(
            String id,
            Position target,
            BlockFace face,
            String expectedBlock,
            String toolItem) implements Node {
        public BreakKnownFace {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
            Objects.requireNonNull(toolItem, "toolItem");
        }
    }

    /**
     * Breaks one exact, policy-approved visible block and confirms both the authoritative air
     * transition and the declared inventory pickup postcondition.
     */
    public record BreakKnownBlock(
            String id,
            Position target,
            BlockFace face,
            BlockStateSpec expectedState,
            String toolItem,
            String expectedDrop,
            int minimumInventoryCount) implements Node {
        public BreakKnownBlock {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(toolItem, "toolItem");
            Objects.requireNonNull(expectedDrop, "expectedDrop");
        }
    }

    /**
     * Operates one already-built, stationary cobblestone generator until an absolute inventory
     * goal is reached. The runtime may hold attack only while the exact declared block face is
     * current cobblestone; every acknowledged break is checkpointed before regeneration wait.
     */
    public record OperateKnownCobblestoneGenerator(
            String id,
            Position target,
            BlockFace face,
            BlockStateSpec expectedState,
            String toolItem,
            String expectedDrop,
            int minimumInventoryCount,
            int maxBreaks,
            int regenerationWaitTicks,
            long maxOperationDurationTicks) implements Node {
        public OperateKnownCobblestoneGenerator {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(toolItem, "toolItem");
            Objects.requireNonNull(expectedDrop, "expectedDrop");
        }
    }

    /**
     * Holds a closed set of semantic Minecraft inputs for a finite duration. Attack and use
     * are never raw/unscoped: they require one exact, pre-aimed block guard and selected item.
     */
    public record HoldBoundedInputs(
            String id,
            List<BoundedInput> inputs,
            long durationTicks,
            Optional<ExactBlockTargetGuard> targetGuard,
            Optional<String> selectedItem) implements Node {
        public HoldBoundedInputs {
            Objects.requireNonNull(id, "id");
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
            Objects.requireNonNull(targetGuard, "targetGuard");
            Objects.requireNonNull(selectedItem, "selectedItem");
        }
    }

    /** Exact live crosshair guard required by bounded attack/use input holds. */
    public record ExactBlockTargetGuard(
            Position target,
            BlockFace face,
            BlockStateSpec expectedState) {
        public ExactBlockTargetGuard {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(expectedState, "expectedState");
        }
    }

    public record TillKnownBlock(
            String id,
            Position target,
            String expectedBlock,
            String hoeItem) implements Node {
        public TillKnownBlock {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
            Objects.requireNonNull(hoeItem, "hoeItem");
        }
    }

    /** Jointly planned, bounded set of independent till operations. */
    public record TillKnownBatch(
            String id,
            List<Position> targets,
            String expectedBlock,
            String hoeItem) implements Node {
        public TillKnownBatch {
            Objects.requireNonNull(id, "id");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            Objects.requireNonNull(expectedBlock, "expectedBlock");
            Objects.requireNonNull(hoeItem, "hoeItem");
        }
    }

    /** Plants wheat into target air using the known farmland block directly below it. */
    public record PlantKnownWheat(
            String id,
            Position target,
            Position support,
            String seedItem) implements Node {
        public PlantKnownWheat {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(support, "support");
            Objects.requireNonNull(seedItem, "seedItem");
        }
    }

    public record PlantPlot(Position target, Position support) {
        public PlantPlot {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(support, "support");
        }
    }

    /** Jointly planned, bounded set of independent wheat placements. */
    public record PlantKnownWheatBatch(
            String id,
            List<PlantPlot> targets,
            String seedItem) implements Node {
        public PlantKnownWheatBatch {
            Objects.requireNonNull(id, "id");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
            Objects.requireNonNull(seedItem, "seedItem");
        }
    }

    /** Breaks only a currently mature (age=7) wheat block. */
    public record HarvestKnownWheat(String id, Position target) implements Node {
        public HarvestKnownWheat {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
        }
    }

    /** Jointly planned, bounded set of independent mature-wheat breaks. */
    public record HarvestKnownWheatBatch(String id, List<Position> targets) implements Node {
        public HarvestKnownWheatBatch {
            Objects.requireNonNull(id, "id");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        }
    }

    /**
     * Places a bounded, ordered projection of complete observed block states. Offsets and
     * directional state are transformed by the runtime, never by the policy.
     */
    public record ApplyKnownBlockPlan(
            String id,
            Position anchor,
            BlockPlanTransform transform,
            List<BlockPlanEntry> entries) implements Node {
        public ApplyKnownBlockPlan {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(transform, "transform");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    public record BlockPlanEntry(
            String id,
            Offset offset,
            Optional<BlockStateSpec> sourceState,
            Optional<String> item,
            Optional<String> placementStateRef,
            PlacementSupport support) {
        public BlockPlanEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(sourceState, "sourceState");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(placementStateRef, "placementStateRef");
            Objects.requireNonNull(support, "support");
            boolean inline = sourceState.isPresent() && item.isPresent();
            if (sourceState.isPresent() != item.isPresent()
                    || inline == placementStateRef.isPresent()) {
                throw new IllegalArgumentException(
                        "construction entry must select inline source or placement_state_ref");
            }
        }

        public BlockPlanEntry(
                String id,
                Offset offset,
                BlockStateSpec sourceState,
                String item,
                PlacementSupport support) {
            this(id, offset, Optional.of(sourceState), Optional.of(item), Optional.empty(), support);
        }
    }

    /** Clears exact, currently visible safe construction states at transformed offsets. */
    public record ClearKnownBlockPlan(
            String id,
            Position anchor,
            BlockPlanTransform transform,
            List<ClearBlockPlanEntry> entries) implements Node {
        public ClearKnownBlockPlan {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(transform, "transform");
            entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        }
    }

    public record ClearBlockPlanEntry(
            String id,
            Offset offset,
            BlockStateSpec expectedBefore) {
        public ClearBlockPlanEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(offset, "offset");
            Objects.requireNonNull(expectedBefore, "expectedBefore");
        }
    }

    /** A complete wire-level BlockState copied from visible_surface.state. */
    public record BlockStateSpec(String block, Map<String, String> properties) {
        public BlockStateSpec {
            Objects.requireNonNull(block, "block");
            properties = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(properties, "properties")));
        }
    }

    /** Places exactly one full block below the jumping player and lands one block higher. */
    public record PillarUpKnown(
            String id,
            Position support,
            BlockStateSpec expectedSupport,
            Optional<BlockStateSpec> sourceState,
            Optional<String> item,
            Optional<String> placementStateRef) implements Node {
        public PillarUpKnown {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(support, "support");
            Objects.requireNonNull(expectedSupport, "expectedSupport");
            Objects.requireNonNull(sourceState, "sourceState");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(placementStateRef, "placementStateRef");
            boolean inline = sourceState.isPresent() && item.isPresent();
            if (sourceState.isPresent() != item.isPresent()
                    || inline == placementStateRef.isPresent()) {
                throw new IllegalArgumentException(
                        "pillar source must select inline identity or placement_state_ref");
            }
        }

        public PillarUpKnown(
                String id,
                Position support,
                BlockStateSpec expectedSupport,
                BlockStateSpec sourceState,
                String item) {
            this(id, support, expectedSupport,
                    Optional.of(sourceState), Optional.of(item), Optional.empty());
        }
    }

    /** Exactly one of expectedState and dependencyEntryId is present. */
    public record PlacementSupport(
            Position position,
            BlockFace face,
            Optional<BlockStateSpec> expectedState,
            Optional<String> dependencyEntryId) {
        public PlacementSupport {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(dependencyEntryId, "dependencyEntryId");
        }
    }

    public record Offset(int x, int y, int z) {
    }

    /** Fixed direct, fan-out, or one-dust lever-to-lamp identity circuit. */
    public record ApplyKnownRedstoneSpec(
            String id,
            Position anchor,
            int rotation,
            List<RedstoneSpec.Component> components,
            List<RedstoneSpec.TruthRow> truthTable,
            RedstoneSpec.Footprint footprint,
            RedstoneTiming timing) implements Node {
        public ApplyKnownRedstoneSpec {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(anchor, "anchor");
            components = List.copyOf(Objects.requireNonNull(components, "components"));
            truthTable = List.copyOf(Objects.requireNonNull(truthTable, "truthTable"));
            Objects.requireNonNull(footprint, "footprint");
            Objects.requireNonNull(timing, "timing");
        }
    }

    public record RedstoneTiming(int settleTicks) {
    }

    /** Wire-level mirror followed by clockwise Y-axis rotation. */
    public record BlockPlanTransform(BlockPlanRotation rotation, BlockPlanMirror mirror) {
        public BlockPlanTransform {
            Objects.requireNonNull(rotation, "rotation");
            Objects.requireNonNull(mirror, "mirror");
        }

        public Offset apply(Offset input) {
            Objects.requireNonNull(input, "input");
            int x = mirror == BlockPlanMirror.X ? -input.x() : input.x();
            int z = mirror == BlockPlanMirror.Z ? -input.z() : input.z();
            return switch (rotation) {
                case DEGREES_0 -> new Offset(x, input.y(), z);
                case DEGREES_90 -> new Offset(-z, input.y(), x);
                case DEGREES_180 -> new Offset(-x, input.y(), -z);
                case DEGREES_270 -> new Offset(z, input.y(), -x);
            };
        }
    }

    public enum BlockPlanRotation {
        DEGREES_0(0),
        DEGREES_90(90),
        DEGREES_180(180),
        DEGREES_270(270);

        private final int degrees;

        BlockPlanRotation(int degrees) {
            this.degrees = degrees;
        }

        public int degrees() {
            return degrees;
        }
    }

    /** x is Minecraft FRONT_BACK; z is Minecraft LEFT_RIGHT. */
    public enum BlockPlanMirror {
        NONE("none"),
        X("x"),
        Z("z");

        private final String wireName;

        BlockPlanMirror(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    /** Opens one currently closed, visible oak fence gate. */
    public record OpenKnownFenceGate(String id, Position target) implements Node {
        public OpenKnownFenceGate {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
        }
    }

    /** Opens one currently closed, visible wooden door, trapdoor, or fence gate. */
    public record OpenKnownPassage(
            String id, Position target, String expectedBlock) implements Node {
        public OpenKnownPassage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
        }
    }

    /** Opens a visible allowlisted Vanilla chest/barrel and records its server-synchronized item totals. */
    public record InspectKnownContainer(
            String id,
            Position target,
            String expectedBlock,
            Optional<RoutingLabel> routingLabel) implements Node {
        public InspectKnownContainer {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
            Objects.requireNonNull(routingLabel, "routingLabel");
        }

        public InspectKnownContainer(String id, Position target, String expectedBlock) {
            this(id, target, expectedBlock, Optional.empty());
        }
    }

    /** Moves bounded whole matching stacks from a visible allowlisted Vanilla chest/barrel. */
    public record TakeKnownContainerStack(
            String id,
            Position target,
            String expectedBlock,
            String item,
            String stackPolicy,
            int minimumInventoryCount,
            Optional<RoutingLabel> routingLabel,
            int maxStacks,
            int maxTransferCount) implements Node {
        public TakeKnownContainerStack {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(stackPolicy, "stackPolicy");
            Objects.requireNonNull(routingLabel, "routingLabel");
        }

        public TakeKnownContainerStack(String id, Position target, String expectedBlock,
                String item, String stackPolicy, int minimumInventoryCount,
                Optional<RoutingLabel> routingLabel) {
            this(id, target, expectedBlock, item, stackPolicy, minimumInventoryCount,
                    routingLabel, 1, 64);
        }

        public TakeKnownContainerStack(
                String id,
                Position target,
                String expectedBlock,
                String item,
                String stackPolicy,
                int minimumInventoryCount) {
            this(id, target, expectedBlock, item, stackPolicy, minimumInventoryCount,
                    Optional.empty());
        }
    }

    /** Removes one displayed item from a visible Vanilla frame; pickup is a separate Action. */
    public record RemoveVisibleFrameItem(String id, String entityRef, String expectedItem) implements Node {
        public RemoveVisibleFrameItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(entityRef, "entityRef");
            Objects.requireNonNull(expectedItem, "expectedItem");
        }
    }

    /** Inserts one held item into a currently visible empty Vanilla frame without rotating it. */
    public record InsertVisibleFrameItem(String id, String entityRef, String item) implements Node {
        public InsertVisibleFrameItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(entityRef, "entityRef");
            Objects.requireNonNull(item, "item");
        }
    }

    /** Moves bounded whole matching stacks into a visible allowlisted Vanilla chest/barrel. */
    public record StoreKnownContainerStack(
            String id,
            Position target,
            String expectedBlock,
            String item,
            String stackPolicy,
            int minimumContainerCount,
            Optional<RoutingLabel> routingLabel,
            int maxStacks,
            int maxTransferCount) implements Node {
        public StoreKnownContainerStack {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(stackPolicy, "stackPolicy");
            Objects.requireNonNull(routingLabel, "routingLabel");
        }

        public StoreKnownContainerStack(String id, Position target, String expectedBlock,
                String item, String stackPolicy, int minimumContainerCount,
                Optional<RoutingLabel> routingLabel) {
            this(id, target, expectedBlock, item, stackPolicy, minimumContainerCount,
                    routingLabel, 1, 64);
        }

        public StoreKnownContainerStack(
                String id,
                Position target,
                String expectedBlock,
                String item,
                String stackPolicy,
                int minimumContainerCount) {
            this(id, target, expectedBlock, item, stackPolicy, minimumContainerCount,
                    Optional.empty());
        }
    }

    /** A current delivered item-frame label used only to route a container operation. */
    public record RoutingLabel(String entityRef, String item) {
        public RoutingLabel {
            Objects.requireNonNull(entityRef, "entityRef");
            Objects.requireNonNull(item, "item");
        }
    }

    /** Crafts a bounded current recipe at one visible crafting table to an absolute item goal. */
    public record CraftKnownRecipe(
            String id,
            String recipeRef,
            String recipeFingerprint,
            String goalItem,
            String stackPolicy,
            int minimumInventoryCount,
            String stationKind,
            Position target,
            BlockStateSpec expectedState,
            int maxCrafts) implements Node {
        public CraftKnownRecipe {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(recipeRef, "recipeRef");
            Objects.requireNonNull(recipeFingerprint, "recipeFingerprint");
            Objects.requireNonNull(goalItem, "goalItem");
            Objects.requireNonNull(stackPolicy, "stackPolicy");
            Objects.requireNonNull(stationKind, "stationKind");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
        }
    }

    /** Smelts one exact full input stack from one current furnace-family recipe. */
    public record SmeltKnownRecipe(
            String id,
            String recipeRef,
            String recipeFingerprint,
            String goalItem,
            String stackPolicy,
            int minimumInventoryCount,
            String stationKind,
            Position target,
            BlockStateSpec expectedState,
            String fuelItem,
            String fuelStackPolicy,
            int maxSmelts) implements Node {
        public SmeltKnownRecipe {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(recipeRef, "recipeRef");
            Objects.requireNonNull(recipeFingerprint, "recipeFingerprint");
            Objects.requireNonNull(goalItem, "goalItem");
            Objects.requireNonNull(stackPolicy, "stackPolicy");
            Objects.requireNonNull(stationKind, "stationKind");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedState, "expectedState");
            Objects.requireNonNull(fuelItem, "fuelItem");
            Objects.requireNonNull(fuelStackPolicy, "fuelStackPolicy");
        }
    }

    /** Executes one current opaque operation against its session-bound known menu. */
    public record OperateKnownMenu(String id, String operationRef) implements Node {
        public OperateKnownMenu {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(operationRef, "operationRef");
        }
    }

    /** Brews one declared batch of 1..3 component-exact standard Vanilla potions. */
    public record BrewKnownPotionBatch(
            String id,
            Position target,
            String expectedBlock,
            StandardPotionStackSpec input,
            String ingredientItem,
            String fuelItem,
            StandardPotionStackSpec expectedOutput) implements Node {
        public BrewKnownPotionBatch {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
            Objects.requireNonNull(input, "input");
            Objects.requireNonNull(ingredientItem, "ingredientItem");
            Objects.requireNonNull(fuelItem, "fuelItem");
            Objects.requireNonNull(expectedOutput, "expectedOutput");
        }
    }

    /** Collects the visible item entity identified by its rendered item and observed position. */
    public record CollectVisibleItem(
            String id, String displayedItem, WorldPosition target) implements Node {
        public CollectVisibleItem {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(displayedItem, "displayedItem");
            Objects.requireNonNull(target, "target");
        }
    }

    public record CollectTarget(String displayedItem, WorldPosition target) {
        public CollectTarget {
            Objects.requireNonNull(displayedItem, "displayedItem");
            Objects.requireNonNull(target, "target");
        }
    }

    /** Collects a bounded listed sequence while retaining batch-wide pickup evidence. */
    public record CollectVisibleItemBatch(
            String id, List<CollectTarget> targets) implements Node {
        public CollectVisibleItemBatch {
            Objects.requireNonNull(id, "id");
            targets = List.copyOf(Objects.requireNonNull(targets, "targets"));
        }
    }

    /** Casts only an exact held Vanilla fishing rod toward one fresh visible source-water face. */
    public record CastKnownFishingRod(
            String id,
            String hand,
            String rodItem,
            Position target,
            BlockFace face,
            BlockStateSpec expectedState) implements Node {
        public CastKnownFishingRod {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(hand, "hand");
            Objects.requireNonNull(rodItem, "rodItem");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(face, "face");
            Objects.requireNonNull(expectedState, "expectedState");
        }
    }

    /** Reels one live, world-session-local bobber issued by cast_known_fishing_rod. */
    public record ReelKnownFishingSession(
            String id,
            String fishingSessionRef,
            String hand,
            String rodItem) implements Node {
        public ReelKnownFishingSession {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(fishingSessionRef, "fishingSessionRef");
            Objects.requireNonNull(hand, "hand");
            Objects.requireNonNull(rodItem, "rodItem");
        }
    }

    /** Runs one physically approved, finite, stationary hostile-mob kill-zone operation. */
    public record OperateKillZone(
            String id,
            WorldBounds targetKillZoneBounds,
            List<String> entityTypeAllowlist,
            String mainHandItem,
            Optional<String> consentRef,
            int maxAttacks,
            long minimumIntervalTicks,
            long maxOperationDurationTicks) implements Node {
        public OperateKillZone {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(targetKillZoneBounds, "targetKillZoneBounds");
            entityTypeAllowlist = List.copyOf(
                    Objects.requireNonNull(entityTypeAllowlist, "entityTypeAllowlist"));
            Objects.requireNonNull(mainHandItem, "mainHandItem");
            Objects.requireNonNull(consentRef, "consentRef");
        }
    }

    public record WaitTicks(String id, int ticks) implements Node {
        public WaitTicks {
            Objects.requireNonNull(id, "id");
        }
    }

    public record WaitUntil(
            String id,
            WaitCondition condition,
            int maxTicks) implements Node {
        public WaitUntil {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(condition, "condition");
        }
    }

    public sealed interface WaitCondition permits CropMatureCondition, SoundClueCondition {
    }

    public record CropMatureCondition(Position target) implements WaitCondition {
        public CropMatureCondition {
            Objects.requireNonNull(target, "target");
        }
    }

    /** Exact actual-playback clue, bounded in time and world-space by the caller. */
    public record SoundClueCondition(
            String soundEvent,
            long sinceTick,
            WorldBounds bounds) implements WaitCondition {
        public SoundClueCondition {
            Objects.requireNonNull(soundEvent, "soundEvent");
            Objects.requireNonNull(bounds, "bounds");
        }
    }

    public record WorldBounds(String dimension, WorldPoint min, WorldPoint max) {
        public WorldBounds {
            Objects.requireNonNull(dimension, "dimension");
            Objects.requireNonNull(min, "min");
            Objects.requireNonNull(max, "max");
        }
    }

    public record WorldPoint(double x, double y, double z) {
    }

    public record If(
            String id,
            Predicate condition,
            List<Node> thenBranch,
            List<Node> elseBranch) implements Node {
        public If {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(condition, "condition");
            thenBranch = List.copyOf(Objects.requireNonNull(thenBranch, "thenBranch"));
            elseBranch = List.copyOf(Objects.requireNonNull(elseBranch, "elseBranch"));
        }
    }

    public record Repeat(String id, int count, List<Node> body) implements Node {
        public Repeat {
            Objects.requireNonNull(id, "id");
            body = List.copyOf(Objects.requireNonNull(body, "body"));
        }
    }

    public record Position(String dimension, int x, int y, int z) {
        public Position {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    /** Dimension-qualified continuous coordinate copied from visible_entity.position. */
    public record WorldPosition(String dimension, double x, double y, double z) {
        public WorldPosition {
            Objects.requireNonNull(dimension, "dimension");
        }
    }

    public enum Capability {
        MOVEMENT("movement"),
        CAMERA("camera"),
        BLOCK_BREAK("block_break"),
        BLOCK_INTERACT("block_interact"),
        BLOCK_PLACE("block_place"),
        INVENTORY_TRANSFER("inventory_transfer"),
        ITEM_USE("item_use"),
        ENTITY_ATTACK("entity_attack");

        private final String wireName;

        Capability(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum BoundedInput {
        FORWARD("forward"),
        BACK("back"),
        LEFT("left"),
        RIGHT("right"),
        JUMP("jump"),
        SNEAK("sneak"),
        ATTACK("attack"),
        USE("use");

        private final String wireName;

        BoundedInput(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum BlockFace {
        DOWN("down"),
        UP("up"),
        NORTH("north"),
        SOUTH("south"),
        WEST("west"),
        EAST("east");

        private final String wireName;

        BlockFace(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public sealed interface Predicate permits AtomicPredicate, LogicalPredicate {
    }

    public sealed interface AtomicPredicate extends Predicate
            permits NumericPredicate, BooleanPredicate, InventoryPredicate, StatusPredicate {
    }

    public record NumericPredicate(
            NumericField field,
            Comparison comparison,
            double value) implements AtomicPredicate {
        public NumericPredicate {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(comparison, "comparison");
        }
    }

    public record BooleanPredicate(
            BooleanField field,
            Comparison comparison,
            boolean value) implements AtomicPredicate {
        public BooleanPredicate {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(comparison, "comparison");
        }
    }

    public record InventoryPredicate(
            String item,
            Comparison comparison,
            int value) implements AtomicPredicate {
        public InventoryPredicate {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(comparison, "comparison");
        }
    }

    public record StatusPredicate(
            String effect,
            Comparison comparison,
            boolean value) implements AtomicPredicate {
        public StatusPredicate {
            Objects.requireNonNull(effect, "effect");
            Objects.requireNonNull(comparison, "comparison");
        }
    }

    public record LogicalPredicate(LogicalOperator operator, List<AtomicPredicate> operands)
            implements Predicate {
        public LogicalPredicate {
            Objects.requireNonNull(operator, "operator");
            operands = List.copyOf(Objects.requireNonNull(operands, "operands"));
        }
    }

    public enum NumericField {
        HEALTH("health"),
        HUNGER("hunger"),
        AIR("air");

        private final String wireName;

        NumericField(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum BooleanField {
        ON_FIRE("on_fire"),
        SUBMERGED("submerged");

        private final String wireName;

        BooleanField(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum Comparison {
        LT("lt"),
        LTE("lte"),
        EQ("eq"),
        GTE("gte"),
        GT("gt");

        private final String wireName;

        Comparison(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public enum LogicalOperator {
        ALL("all"),
        ANY("any");

        private final String wireName;

        LogicalOperator(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    public record Budget(
            long maxDurationMillis,
            long maxTicks,
            double maxDistanceBlocks,
            double maxCameraDegrees,
            long maxInteractions,
            long maxBlocksBroken,
            long maxBlocksPlaced) {
    }
}
