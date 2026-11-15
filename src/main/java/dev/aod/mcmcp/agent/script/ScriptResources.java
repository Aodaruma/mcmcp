package dev.aod.mcmcp.agent.script;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.Strictness;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;

/** Immutable, explicitly registered data. The interpreter never resolves host paths. */
public final class ScriptResources {
    public record Resource(String format, Object value, String sha256) { }
    public static final int MAX_NODES = 8_000_000;
    private ScriptResources() { }

    public static String read(Path root, String relative, long[] remaining) throws IOException {
        if (relative == null || relative.isBlank() || relative.length() > 512 || relative.contains(":")
                || relative.startsWith("/") || relative.startsWith("\\") || relative.contains("\\"))
            throw new IllegalArgumentException("resource requires a relative slash-separated path");
        for (String part : relative.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" "))
                throw new IllegalArgumentException("invalid resource path component");
        root = root.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("resource outside root");
        verifyPath(root, file);
        var before = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() > remaining[0]) throw new IllegalArgumentException("resource size limit");
        byte[] bytes;
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (channel.size() > remaining[0] || channel.size() > Integer.MAX_VALUE - 1)
                throw new IllegalArgumentException("resource size limit");
            var buffer = ByteBuffer.allocate((int) channel.size() + 1);
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("resource read cancelled");
            }
            if (!buffer.hasRemaining()) throw new IllegalArgumentException("resource changed during read");
            bytes = Arrays.copyOf(buffer.array(), buffer.position());
        }
        verifyPath(root, file);
        var after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!Objects.equals(before.fileKey(), after.fileKey()) || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime()))
            throw new IllegalArgumentException("resource changed during read");
        remaining[0] -= bytes.length;
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        return text.startsWith("\ufeff") ? text.substring(1) : text;
    }

    private static void verifyPath(Path root, Path file) throws IOException {
        if (!root.toRealPath().equals(root)) throw new IllegalArgumentException("resource root must not be a link");
        Path current = root;
        for (Path part : root.relativize(file)) {
            current = current.resolve(part);
            var attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther() || !current.toRealPath().equals(current))
                throw new IllegalArgumentException("resource links are not allowed");
        }
    }

    public static Resource parse(String format, String text, int[] nodes) {
        try {
            Object value = switch (format) {
                case "json" -> json(text, nodes);
                case "csv" -> csv(text, nodes);
                default -> throw new IllegalArgumentException("resource format must be json or csv");
            };
            return new Resource(format, value, java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))));
        } catch (IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("invalid resource data", failure);
        }
    }

    private static Object json(String text, int[] nodes) throws IOException {
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            Object result = jsonValue(reader, 0, nodes);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException("trailing JSON data");
            return result;
        }
    }
    private static void budget(int[] nodes, int depth) {
        if (++nodes[0] > MAX_NODES || depth > 64 || Thread.currentThread().isInterrupted())
            throw new IllegalArgumentException("resource complexity limit");
    }
    private static Object jsonValue(JsonReader reader, int depth, int[] nodes) throws IOException {
        budget(nodes, depth);
        return switch (reader.peek()) {
            case BEGIN_ARRAY -> {
                var result = new ArrayList<Object>();
                reader.beginArray();
                while (reader.hasNext()) result.add(jsonValue(reader, depth + 1, nodes));
                reader.endArray();
                yield Collections.unmodifiableList(result);
            }
            case BEGIN_OBJECT -> {
                var result = new LinkedHashMap<String, Object>();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (result.containsKey(key)) throw new IllegalArgumentException("duplicate JSON key");
                    result.put(key, jsonValue(reader, depth + 1, nodes));
                }
                reader.endObject();
                yield Collections.unmodifiableMap(result);
            }
            case STRING -> reader.nextString();
            case NUMBER -> {
                double value = reader.nextDouble();
                if (!Double.isFinite(value)) throw new IllegalArgumentException("nonfinite JSON number");
                yield value;
            }
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> { reader.nextNull(); yield null; }
            default -> throw new IllegalArgumentException("invalid JSON value");
        };
    }

    private static Object csv(String text, int[] nodes) {
        var records = new ArrayList<Object>();
        List<String> headers = null;
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i <= text.length(); i++) {
            if ((i & 4095) == 0 && Thread.currentThread().isInterrupted())
                throw new IllegalArgumentException("resource parse cancelled");
            char c = i == text.length() ? '\n' : text.charAt(i);
            if (quoted) {
                if (i == text.length()) throw new IllegalArgumentException("unterminated CSV quote");
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else cell.append(c);
                continue;
            }
            if (c == '"' && cell.isEmpty() && !closed) { quoted = true; continue; }
            if (c != ',' && c != '\n' && c != '\r') {
                if (closed || c == '"') throw new IllegalArgumentException("invalid CSV quote");
                cell.append(c);
                continue;
            }
            if (i == text.length() && row.isEmpty() && cell.isEmpty() && !closed) break;
            budget(nodes, 1);
            row.add(cell.toString()); cell.setLength(0); closed = false;
            if (row.size() > 256) throw new IllegalArgumentException("CSV column limit");
            if (c == ',') continue;
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            if (headers == null) {
                if (new HashSet<>(row).size() != row.size() || row.stream().anyMatch(String::isBlank))
                    throw new IllegalArgumentException("CSV headers must be unique and nonempty");
                headers = List.copyOf(row);
            } else {
                if (row.size() != headers.size()) throw new IllegalArgumentException("CSV row width mismatch");
                var value = new LinkedHashMap<String, Object>();
                for (int col = 0; col < row.size(); col++) value.put(headers.get(col), row.get(col));
                records.add(Collections.unmodifiableMap(value));
            }
            row.clear();
        }
        if (headers == null) throw new IllegalArgumentException("CSV header required");
        return Collections.unmodifiableList(records);
    }
}
