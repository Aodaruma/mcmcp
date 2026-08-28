package dev.aod.mcmcp.agent.dsl;

import java.util.List;
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

    public sealed interface Node permits NavigateToKnown, FaceKnownPosition, BreakKnownFace,
            TillKnownBlock, TillKnownBatch, PlantKnownWheat, PlantKnownWheatBatch,
            HarvestKnownWheat, HarvestKnownWheatBatch, OpenKnownFenceGate,
            OpenKnownPassage, InspectKnownContainer, TakeKnownContainerStack,
            CollectVisibleItem, WaitTicks, WaitUntil, If, Repeat {
        String id();
    }

    public record NavigateToKnown(String id, Position target, double tolerance) implements Node {
        public NavigateToKnown {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
        }
    }

    public record FaceKnownPosition(String id, Position target) implements Node {
        public FaceKnownPosition {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
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

    /** Opens a visible single chest/barrel and returns its server-synchronized item summary. */
    public record InspectKnownContainer(
            String id, Position target, String expectedBlock) implements Node {
        public InspectKnownContainer {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
        }
    }

    /** Moves at most one whole matching stack from a visible single chest/barrel. */
    public record TakeKnownContainerStack(
            String id,
            Position target,
            String expectedBlock,
            String item,
            String stackPolicy,
            int minimumInventoryCount) implements Node {
        public TakeKnownContainerStack {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(expectedBlock, "expectedBlock");
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(stackPolicy, "stackPolicy");
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

    public record WaitTicks(String id, int ticks) implements Node {
        public WaitTicks {
            Objects.requireNonNull(id, "id");
        }
    }

    public record WaitUntil(
            String id,
            CropMatureCondition condition,
            int maxTicks) implements Node {
        public WaitUntil {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(condition, "condition");
        }
    }

    public record CropMatureCondition(Position target) {
        public CropMatureCondition {
            Objects.requireNonNull(target, "target");
        }
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
        INVENTORY_TRANSFER("inventory_transfer");

        private final String wireName;

        Capability(String wireName) {
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
