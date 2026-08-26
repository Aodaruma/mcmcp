package dev.aod.mcmcp.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** Strict JSON reader that enforces endpoint-wide resource limits before dispatch. */
final class McpJson {
    private static final BigDecimal MAX_ABSOLUTE_NUMBER = BigDecimal.ONE.scaleByPowerOfTen(100);

    private McpJson() {
    }

    static JsonElement parse(byte[] bytes, McpHttpServerConfig config) throws IOException, LimitException {
        String text = decodeUtf8(bytes);
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            JsonElement result = read(reader, config, 1);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("Trailing JSON content");
            }
            return result;
        } catch (IllegalStateException | NumberFormatException failure) {
            throw new IOException("Malformed JSON", failure);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private static JsonElement read(JsonReader reader, McpHttpServerConfig config, int depth)
            throws IOException, LimitException {
        JsonToken token = reader.peek();
        if ((token == JsonToken.BEGIN_OBJECT || token == JsonToken.BEGIN_ARRAY)
                && depth > config.maxJsonDepth()) {
            throw new LimitException("JsonTooDeep");
        }
        return switch (token) {
            case BEGIN_OBJECT -> readObject(reader, config, depth);
            case BEGIN_ARRAY -> readArray(reader, config, depth);
            case STRING -> new JsonPrimitive(boundedString(reader.nextString(), config));
            case NUMBER -> readNumber(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Expected one JSON value");
        };
    }

    private static JsonObject readObject(JsonReader reader, McpHttpServerConfig config, int depth)
            throws IOException, LimitException {
        reader.beginObject();
        var result = new JsonObject();
        var names = new HashSet<String>();
        int count = 0;
        while (reader.hasNext()) {
            if (++count > config.maxJsonCollectionItems()) {
                throw new LimitException("JsonObjectTooLarge");
            }
            String name = boundedString(reader.nextName(), config);
            if (!names.add(name)) {
                throw new LimitException("DuplicateJsonMember");
            }
            result.add(name, read(reader, config, depth + 1));
        }
        reader.endObject();
        return result;
    }

    private static JsonArray readArray(JsonReader reader, McpHttpServerConfig config, int depth)
            throws IOException, LimitException {
        reader.beginArray();
        var result = new JsonArray();
        while (reader.hasNext()) {
            if (result.size() >= config.maxJsonCollectionItems()) {
                throw new LimitException("JsonArrayTooLong");
            }
            result.add(read(reader, config, depth + 1));
        }
        reader.endArray();
        return result;
    }

    private static String boundedString(String value, McpHttpServerConfig config) throws LimitException {
        if (value.length() > config.maxJsonStringChars()) {
            throw new LimitException("JsonStringTooLong");
        }
        return value;
    }

    private static JsonPrimitive readNumber(String text) throws LimitException {
        if (text.length() > 128) {
            throw new LimitException("JsonNumberOutOfRange");
        }
        BigDecimal value = new BigDecimal(text);
        if (value.precision() > 128 || value.abs().compareTo(MAX_ABSOLUTE_NUMBER) > 0) {
            throw new LimitException("JsonNumberOutOfRange");
        }
        return new JsonPrimitive(value);
    }

    static final class LimitException extends Exception {
        private final String code;

        LimitException(String code) {
            super(code);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
