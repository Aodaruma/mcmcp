package dev.aodaruma.craftagent.routine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Minimal typed view of the four world-operation parameter maps validated by the runtime. */
sealed interface PhaseFiveWorldSpec permits PhaseFiveWorldSpec.CropSpec,
        PhaseFiveWorldSpec.TreeSpec, PhaseFiveWorldSpec.SleepSpec,
        PhaseFiveWorldSpec.SurveySpec {
    Map<String, CropAdapter> CROPS = Map.of(
            "wheat", new CropAdapter("minecraft:wheat", "minecraft:wheat_seeds", "minecraft:wheat"),
            "carrots", new CropAdapter("minecraft:carrots", "minecraft:carrot", "minecraft:carrot"),
            "potatoes", new CropAdapter("minecraft:potatoes", "minecraft:potato", "minecraft:potato"),
            "beetroots", new CropAdapter(
                    "minecraft:beetroots", "minecraft:beetroot_seeds", "minecraft:beetroot"));
    Map<String, TreeAdapter> TREES = treeAdapters();
    Set<String> TREE_SUPPORTS = Set.of(
            "minecraft:dirt", "minecraft:grass_block", "minecraft:podzol",
            "minecraft:coarse_dirt", "minecraft:rooted_dirt", "minecraft:moss_block",
            "minecraft:mud");

    String kind();

    static PhaseFiveWorldSpec parse(PhaseFiveRequest request) {
        Objects.requireNonNull(request, "request");
        Map<?, ?> parameters = request.parameters();
        return switch (request.kind()) {
            case "tend_crop_area" -> crop(parameters);
            case "harvest_tree_area" -> trees(parameters, request);
            case "sleep_at_bed" -> sleep(parameters);
            case "survey_area" -> survey(parameters);
            default -> throw new IllegalArgumentException("not a Phase 5 world routine kind");
        };
    }

    private static CropSpec crop(Map<?, ?> parameters) {
        String adapterName = text(parameters.get("crop_adapter"));
        CropAdapter adapter = Objects.requireNonNull(CROPS.get(adapterName), "unsupported crop adapter");
        var plots = new ArrayList<CropPlot>();
        for (Map<?, ?> raw : objects(parameters.get("plots"))) {
            BlockTarget crop = position(raw.get("crop_position"));
            BlockTarget support = position(raw.get("support_position"));
            BlockStateFingerprint expectedSupport = state(raw.get("expected_support_state"));
            if (crop.x() != support.x() || crop.y() != support.y() + 1 || crop.z() != support.z()
                    || !"minecraft:farmland".equals(expectedSupport.blockId())) {
                throw new IllegalArgumentException("crop plot support is inconsistent");
            }
            plots.add(new CropPlot(text(raw.get("id")), crop, support, expectedSupport));
        }
        Map<?, ?> goal = object(parameters.get("goal"));
        return new CropSpec(adapterName, adapter, plots,
                number(goal.get("minimum_harvested_plots")), text(parameters.get("wait_policy")));
    }

    private static TreeSpec trees(Map<?, ?> parameters, PhaseFiveRequest request) {
        var trees = new ArrayList<Tree>();
        int totalLogs = 0;
        for (Map<?, ?> raw : objects(parameters.get("trees"))) {
            List<StateCell> logs = cells(raw.get("logs"));
            totalLogs += logs.size();
            TreeAdapter adapter = null;
            for (StateCell log : logs) {
                TreeAdapter current = TREES.get(log.expectedState().blockId());
                if (current == null || adapter != null && !adapter.equals(current)) {
                    throw new IllegalArgumentException("tree logs are outside the vanilla allowlist");
                }
                adapter = current;
            }
            StateCell support = cell(raw.get("support"));
            if (!TREE_SUPPORTS.contains(support.expectedState().blockId())) {
                throw new IllegalArgumentException("tree support is outside the vanilla allowlist");
            }
            Map<?, ?> sapling = object(raw.get("sapling"));
            String saplingItem = text(sapling.get("item"));
            BlockStateFingerprint expectedSapling = state(sapling.get("expected_after_state"));
            if (!adapter.saplingItem().equals(saplingItem)
                    || !adapter.saplingBlock().equals(expectedSapling.blockId())) {
                throw new IllegalArgumentException("tree sapling does not match its logs");
            }
            BlockTarget saplingPosition = new BlockTarget(
                    support.position().dimension(), support.position().x(),
                    support.position().y() + 1, support.position().z());
            if (!request.bounds().contains(saplingPosition)) {
                throw new IllegalArgumentException("tree sapling position is outside bounds");
            }
            trees.add(new Tree(text(raw.get("id")), logs, support, saplingPosition,
                    saplingItem, expectedSapling, cells(raw.get("growth_clearance")), adapter));
        }
        if (totalLogs > 64) {
            throw new IllegalArgumentException("declared tree logs exceed 64");
        }
        return new TreeSpec(trees, totalLogs);
    }

    private static SleepSpec sleep(Map<?, ?> parameters) {
        Map<?, ?> bed = object(parameters.get("bed"));
        StateCell foot = new StateCell(position(bed.get("foot_position")),
                state(bed.get("expected_foot_state")));
        StateCell head = new StateCell(position(bed.get("head_position")),
                state(bed.get("expected_head_state")));
        if (!foot.expectedState().blockId().equals(head.expectedState().blockId())
                || !foot.expectedState().blockId().endsWith("_bed")
                || distance(foot.position(), head.position()) != 1) {
            throw new IllegalArgumentException("bed halves are inconsistent");
        }
        return new SleepSpec(foot, head);
    }

    private static SurveySpec survey(Map<?, ?> parameters) {
        var waypoints = new ArrayList<Waypoint>();
        for (Map<?, ?> raw : objects(parameters.get("waypoints"))) {
            waypoints.add(new Waypoint(text(raw.get("id")),
                    position(raw.get("target")), position(raw.get("look_at"))));
        }
        var samples = new ArrayList<Sample>();
        for (Map<?, ?> raw : objects(parameters.get("samples"))) {
            samples.add(new Sample(text(raw.get("id")), position(raw.get("position"))));
        }
        Map<?, ?> goal = object(parameters.get("goal"));
        return new SurveySpec(waypoints, samples,
                number(goal.get("minimum_observed_samples")), text(parameters.get("assessment")));
    }

    private static List<StateCell> cells(Object raw) {
        return objects(raw).stream().map(PhaseFiveWorldSpec::cell).toList();
    }

    private static StateCell cell(Map<?, ?> raw) {
        return new StateCell(position(raw.get("position")), state(raw.get("expected_state")));
    }

    private static StateCell cell(Object raw) {
        return cell(object(raw));
    }

    private static BlockTarget position(Object raw) {
        Map<?, ?> value = object(raw);
        return new BlockTarget(text(value.get("dimension")), number(value.get("x")),
                number(value.get("y")), number(value.get("z")));
    }

    private static BlockStateFingerprint state(Object raw) {
        Map<?, ?> value = object(raw);
        var properties = new LinkedHashMap<String, String>();
        object(value.get("properties")).forEach((key, entry) ->
                properties.put((String) key, (String) entry));
        return new BlockStateFingerprint(text(value.get("block")), properties);
    }

    private static Map<?, ?> object(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) {
            throw new IllegalArgumentException("validated Phase 5 object is missing");
        }
        return value;
    }

    private static List<Map<?, ?>> objects(Object raw) {
        if (!(raw instanceof Collection<?> values)) {
            throw new IllegalArgumentException("validated Phase 5 array is missing");
        }
        return values.stream().map(PhaseFiveWorldSpec::object).toList();
    }

    private static String text(Object raw) {
        return Objects.requireNonNull((String) raw, "validated Phase 5 string is missing");
    }

    private static int number(Object raw) {
        return Objects.requireNonNull((Number) raw, "validated Phase 5 integer is missing").intValue();
    }

    private static int distance(BlockTarget left, BlockTarget right) {
        return Math.abs(left.x() - right.x()) + Math.abs(left.y() - right.y())
                + Math.abs(left.z() - right.z());
    }

    private static Map<String, TreeAdapter> treeAdapters() {
        var result = new LinkedHashMap<String, TreeAdapter>();
        for (String species : List.of(
                "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "cherry", "pale_oak")) {
            String sapling = "minecraft:" + species + "_sapling";
            result.put("minecraft:" + species + "_log",
                    new TreeAdapter("minecraft:" + species + "_log", sapling, sapling));
        }
        result.put("minecraft:mangrove_log", new TreeAdapter(
                "minecraft:mangrove_log", "minecraft:mangrove_propagule",
                "minecraft:mangrove_propagule"));
        return Map.copyOf(result);
    }

    record CropAdapter(String blockId, String plantingItem, String harvestItem) {}

    record CropPlot(String id, BlockTarget crop, BlockTarget support,
                    BlockStateFingerprint expectedSupport) {}

    record CropSpec(String adapterName, CropAdapter adapter, List<CropPlot> plots,
                    int minimumHarvested, String waitPolicy) implements PhaseFiveWorldSpec {
        public CropSpec { plots = List.copyOf(plots); }
        @Override public String kind() { return "tend_crop_area"; }
    }

    record TreeAdapter(String logBlock, String saplingItem, String saplingBlock) {}

    record StateCell(BlockTarget position, BlockStateFingerprint expectedState) {}

    record Tree(String id, List<StateCell> logs, StateCell support, BlockTarget saplingPosition,
                String saplingItem, BlockStateFingerprint expectedSapling,
                List<StateCell> clearance, TreeAdapter adapter) {
        public Tree { logs = List.copyOf(logs); clearance = List.copyOf(clearance); }
    }

    record TreeSpec(List<Tree> trees, int totalLogs) implements PhaseFiveWorldSpec {
        public TreeSpec { trees = List.copyOf(trees); }
        @Override public String kind() { return "harvest_tree_area"; }
    }

    record SleepSpec(StateCell foot, StateCell head) implements PhaseFiveWorldSpec {
        @Override public String kind() { return "sleep_at_bed"; }
    }

    record Waypoint(String id, BlockTarget target, BlockTarget lookAt) {}

    record Sample(String id, BlockTarget position) {}

    record SurveySpec(List<Waypoint> waypoints, List<Sample> samples,
                      int minimumObserved, String assessment) implements PhaseFiveWorldSpec {
        public SurveySpec { waypoints = List.copyOf(waypoints); samples = List.copyOf(samples); }
        @Override public String kind() { return "survey_area"; }
    }
}
