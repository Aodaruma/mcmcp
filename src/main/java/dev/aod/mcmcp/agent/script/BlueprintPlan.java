package dev.aod.mcmcp.agent.script;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Data-only blueprint planning. Entries are validated before any game action is admitted. */
public final class BlueprintPlan {
    public static final int MAX_ENTRIES = 1_048_576;
    public static final int MAX_BATCH = 65_536;
    public static final String SOURCE = """
            let plan=read_json(name="blueprint");
            for(let i=0;i<length(value=plan.steps);i++) {
                let s=plan.steps[i];
                if(s.operation=="place") {
                    place(x=s.x,y=s.y,z=s.z,dx=s.dx,block=s.block,item=s.item,
                          properties=s.properties,max_blocks=s.count,advance=plan.advance,
                          max_ticks=plan.step_ticks,max_distance=plan.step_distance);
                } else {
                    breakBlocks(x=s.x,y=s.y,z=s.z,dx=s.dx,include_blocks=[s.block],
                                max_blocks=s.count,advance=plan.advance,
                                max_ticks=plan.step_ticks,max_distance=plan.step_distance);
                }
            }
            """;
    private static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    private final Map<String, Object> summary;
    private final List<Map<String, Object>> steps;
    private final List<Integer> ends;
    private final ScriptResources.Resource resource;
    private final int start;
    private final int total;

    private BlueprintPlan(Map<String, Object> summary, List<Map<String, Object>> steps,
                          List<Integer> ends, ScriptResources.Resource resource, int start, int total) {
        this.summary = Map.copyOf(summary);
        this.steps = List.copyOf(steps);
        this.ends = List.copyOf(ends);
        this.resource = resource;
        this.start = start;
        this.total = total;
    }

    public Map<String, Object> preview() { return summary; }
    public int commands() { return steps.size(); }
    public String dimension() { return (String) summary.get("dimension"); }
    public String sha256() { return (String) summary.get("plan_sha256"); }
    public Map<String, ScriptResources.Resource> resources() { return Map.of("blueprint", resource); }

    public Map<String, Object> progress(int completed) {
        if (completed < 0 || completed > commands()) throw new IllegalArgumentException("invalid blueprint progress");
        int next = completed == 0 ? start : ends.get(completed - 1);
        var result = new LinkedHashMap<>(summary);
        result.put("next_index", next);
        result.put("completed_entries", next - start);
        result.put("batch_complete", completed == commands());
        result.put("blueprint_complete", next == total);
        return Map.copyOf(result);
    }

    /** A successful child that skipped an incompatible target must not advance the resume cursor. */
    public boolean confirmedStep(int index, String command, Map<String, Object> result) {
        if (index < 0 || index >= steps.size()) return false;
        var step = steps.get(index);
        boolean place = step.get("operation").equals("place");
        if (!command.equals(place ? "place" : "breakBlocks")
                || !Objects.equals(result.get("kind"), place ? "place_block" : "break_block")
                || !(result.get("result") instanceof Map<?, ?> detail)) return false;
        return count(detail.get("confirmed_count")) + count(detail.get("observed_count"))
                == (int) step.get("count");
    }

    private static long count(Object value) {
        return value instanceof Number n && n.doubleValue() == n.longValue()
                && n.longValue() >= 0 && n.longValue() <= 4096 ? n.longValue() : -4097;
    }

    public static BlueprintPlan compile(ScriptResources.Resource input, String dimension, List<Integer> origin,
                                        int start, int maximum, int stepTicks, double stepDistance, boolean advance) {
        id(dimension);
        if (origin.size() != 3 || start < 0 || maximum < 1 || maximum > MAX_BATCH
                || stepTicks < 1 || stepTicks > dev.aod.mcmcp.agent.action.AgentJobLimits.MAX_TICKS
                || !Double.isFinite(stepDistance) || stepDistance < 0
                || stepDistance > dev.aod.mcmcp.agent.action.AgentJobLimits.MAX_DISTANCE)
            throw new IllegalArgumentException("invalid blueprint batch");
        origin.forEach(BlueprintPlan::coordinate);
        Map<String, Object> data = input.value() instanceof List<?> rows ? Map.of("blocks", rows) : object(input.value());
        keys(data, Set.of("blocks", "palette", "size", "origin", "dimension"));
        if (data.containsKey("dimension") && !dimension.equals(data.get("dimension")))
            throw new IllegalArgumentException("blueprint dimension mismatch");
        var offset = data.containsKey("origin") ? vector(data.get("origin")) : List.of(0, 0, 0);
        var combined = new ArrayList<Integer>();
        for (int i = 0; i < 3; i++) combined.add(coordinate((long) origin.get(i) + offset.get(i)));
        var raw = list(data.get("blocks"));
        if (raw.isEmpty() || raw.size() > MAX_ENTRIES) throw new IllegalArgumentException("blueprint entry limit");
        var cells = new ArrayList<Cell>();
        if (data.containsKey("palette")) {
            var palette = list(data.get("palette"));
            var size = vector(data.get("size"));
            int sx = size.get(0), sy = size.get(1), sz = size.get(2);
            if (sx < 1 || sy < 1 || sz < 1 || sx > MAX_ENTRIES || sy > MAX_ENTRIES || sz > MAX_ENTRIES
                    || (long) sx * sy * sz != raw.size() || palette.isEmpty())
                throw new IllegalArgumentException("blueprint palette volume mismatch");
            for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int ix = 0; ix < sx; ix++) {
                int x = (z & 1) == 0 ? ix : sx - ix - 1;
                int index = integer(raw.get((y * sz + z) * sx + x));
                if (index < 0 || index >= palette.size()) throw new IllegalArgumentException("invalid palette index");
                Object value = palette.get(index);
                var entry = new LinkedHashMap<>(value instanceof String s ? Map.of("block", s) : object(value));
                keys(entry, Set.of("operation", "block", "item", "properties"));
                entry.put("x", x); entry.put("y", y); entry.put("z", z);
                add(cells, entry, combined);
            }
        } else {
            if (data.containsKey("size")) throw new IllegalArgumentException("size requires palette");
            for (Object row : raw) add(cells, object(row), combined);
        }
        if (start > cells.size()) throw new IllegalArgumentException("start_index exceeds blueprint");
        int end = (int) Math.min(cells.size(), (long) start + maximum);
        var steps = new ArrayList<Map<String, Object>>();
        var ends = new ArrayList<Integer>();
        for (int i = start; i < end;) {
            Cell first = cells.get(i);
            int stop = i + 1, direction = 0;
            while (stop < end && stop - i < 8) {
                Cell next = cells.get(stop), previous = cells.get(stop - 1);
                int delta = next.x - previous.x;
                if (!first.sameMaterial(next) || next.y != first.y || next.z != first.z
                        || Math.abs((long) delta) != 1 || direction != 0 && delta != direction) break;
                direction = delta; stop++;
            }
            var step = new LinkedHashMap<String, Object>();
            step.put("operation", first.operation); step.put("x", first.x); step.put("y", first.y); step.put("z", first.z);
            step.put("dx", direction * (stop - i - 1)); step.put("count", stop - i);
            step.put("block", first.block); step.put("item", first.item); step.put("properties", first.properties);
            steps.add(Map.copyOf(step)); ends.add(stop); i = stop;
        }
        var materials = new TreeMap<String, Integer>();
        int places = 0, breaks = 0;
        int[] low = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] high = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Cell cell : cells) {
            if (cell.operation.equals("place")) { places++; materials.merge(cell.item, 1, Integer::sum); }
            else breaks++;
            int[] point = {cell.x, cell.y, cell.z};
            for (int axis = 0; axis < 3; axis++) { low[axis] = Math.min(low[axis], point[axis]); high[axis] = Math.max(high[axis], point[axis]); }
        }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("plan_sha256", hash("blueprint-v1\n" + input.format() + "\n" + input.sha256() + "\n"
                + dimension + "\n" + combined.get(0) + "," + combined.get(1) + "," + combined.get(2)));
        summary.put("source_sha256", input.sha256()); summary.put("dimension", dimension); summary.put("origin", List.copyOf(combined));
        summary.put("total_entries", cells.size()); summary.put("place_entries", places); summary.put("break_entries", breaks);
        summary.put("start_index", start); summary.put("end_index", end); summary.put("batch_entries", end - start);
        summary.put("commands", steps.size()); summary.put("order", data.containsKey("palette") ? "bottom_up_snake_x" : "file_order");
        if (!cells.isEmpty()) summary.put("bounds", Map.of("min", List.of(low[0], low[1], low[2]), "max", List.of(high[0], high[1], high[2])));
        var retained = new LinkedHashMap<String, Integer>();
        materials.entrySet().stream().limit(128).forEach(e -> retained.put(e.getKey(), e.getValue()));
        summary.put("materials", Map.copyOf(retained)); summary.put("materials_truncated", materials.size() > 128);
        String text = new Gson().toJson(Map.of("steps", steps, "step_ticks", stepTicks, "step_distance", stepDistance, "advance", advance));
        return new BlueprintPlan(summary, steps, ends, ScriptResources.parse("json", text, new int[]{0}), start, cells.size());
    }

    private static void add(List<Cell> cells, Map<String, Object> row, List<Integer> origin) {
        if (Thread.currentThread().isInterrupted()) throw new IllegalArgumentException("blueprint planning cancelled");
        keys(row, Set.of("operation", "x", "y", "z", "block", "item", "properties"));
        String operation = text(row, "operation", "place");
        if (!Set.of("place", "break").contains(operation)) throw new IllegalArgumentException("invalid blueprint operation");
        String block = text(row, "block", null); id(block);
        String item = text(row, "item", block); id(item);
        int x = coordinate((long) coordinate(row.get("x")) + origin.get(0));
        int y = coordinate((long) coordinate(row.get("y")) + origin.get(1));
        int z = coordinate((long) coordinate(row.get("z")) + origin.get(2));
        Object rawProperties = row.getOrDefault("properties", Map.of());
        if (rawProperties instanceof String s) rawProperties = s.isBlank() ? Map.of() : ScriptResources.parse("json", s, new int[]{0}).value();
        var properties = new TreeMap<String, String>();
        for (var entry : object(rawProperties).entrySet()) {
            if (!entry.getKey().matches("[a-z0-9_]{1,64}") || !(entry.getValue() instanceof String value)
                    || !value.matches("[a-z0-9_.-]{1,64}")) throw new IllegalArgumentException("invalid blueprint properties");
            properties.put(entry.getKey(), value);
        }
        if (properties.size() > 32) throw new IllegalArgumentException("too many blueprint properties");
        if (operation.equals("break") && (!properties.isEmpty() || !item.equals(block)))
            throw new IllegalArgumentException("break entries use block ID only");
        if (AIR.contains(block)) {
            if (operation.equals("break")) throw new IllegalArgumentException("break requires a solid block ID condition");
            return; // Air in a design is never permission to destroy existing blocks.
        }
        cells.add(new Cell(operation, x, y, z, block, item, Map.copyOf(properties)));
    }

    private record Cell(String operation, int x, int y, int z, String block, String item, Map<String, String> properties) {
        boolean sameMaterial(Cell other) { return operation.equals(other.operation) && block.equals(other.block)
                && item.equals(other.item) && properties.equals(other.properties); }
    }
    private static String text(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        if (value == null || value.equals("")) {
            if (fallback == null) throw new IllegalArgumentException("blueprint requires " + key);
            return fallback;
        }
        if (!(value instanceof String s)) throw new IllegalArgumentException("invalid blueprint " + key);
        return s;
    }
    private static void id(String value) {
        if (value == null || value.length() > 128 || !value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("invalid blueprint resource ID");
    }
    private static void keys(Map<String, Object> value, Set<String> allowed) {
        if (!allowed.containsAll(value.keySet())) throw new IllegalArgumentException("unknown blueprint field");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("blueprint object required");
        return (Map<String, Object>) map;
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> result)) throw new IllegalArgumentException("blueprint array required");
        return result;
    }
    private static List<Integer> vector(Object value) {
        var values = list(value);
        if (values.size() != 3) throw new IllegalArgumentException("blueprint vector requires 3 integers");
        return values.stream().map(BlueprintPlan::coordinate).toList();
    }
    private static int integer(Object value) {
        if (value instanceof String s && s.matches("-?[0-9]{1,10}")) value = Long.parseLong(s);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || n.doubleValue() != n.longValue()
                || n.longValue() < Integer.MIN_VALUE || n.longValue() > Integer.MAX_VALUE)
            throw new IllegalArgumentException("blueprint integer required");
        return n.intValue();
    }
    private static int coordinate(Object value) {
        int n = integer(value);
        if (Math.abs((long) n) > 30_000_000) throw new IllegalArgumentException("blueprint coordinate out of range");
        return n;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
