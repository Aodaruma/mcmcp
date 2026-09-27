package dev.aod.mcmcp.agent.script;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;

import static dev.aod.mcmcp.agent.script.ActionScript.*;

/** Closed grammar, parsed in full before dispatch. No engine, host lookup, or code expansion. */
final class ActionScriptParser {
    record Node(String kind, String text, Object value, List<Node> children, int depth) {}
    private record Token(String kind, String text, int offset) {}
    private static final Set<String> RESERVED = Set.of(
            "let", "if", "else", "for", "repeat", "true", "false", "null", "while", "do",
            "function", "return", "break", "continue", "const", "var", "new", "this", "class",
            "async", "await", "Promise", "import", "export", "require", "Java", "eval",
            "throw", "try", "catch", "finally", "switch", "typeof", "delete", "instanceof");
    private final String source;
    private final Set<String> commands;
    private final BooleanSupplier cancelled;
    private int offset, nodes, nesting;
    private Token token;

    ActionScriptParser(String source, Set<String> commands, BooleanSupplier cancelled) {
        if (source == null) throw new Stop(Status.INVALID, "Source required");
        if (source.length() > MAX_SOURCE) throw new Stop(Status.LIMIT, "Source limit");
        this.source = source;
        this.commands = commands;
        this.cancelled = cancelled;
        advance();
    }

    Node parse() {
        var statements = new ArrayList<Node>();
        while (!token.kind().equals("end")) statements.add(statement());
        return node("block", "", null, statements);
    }

    private Node statement() {
        enter();
        try {
            if (take("let")) {
                Node result = binding("let");
                expect(";");
                return result;
            }
            if (take("if")) {
                expect("(");
                Node condition = expression(0);
                expect(")");
                Node yes = block();
                return take("else") ? node("if", "", null, condition, yes, block())
                        : node("if", "", null, condition, yes);
            }
            if (take("repeat")) {
                expect("(");
                Node count = expression(0);
                expect(")");
                return node("repeat", "", null, count, block());
            }
            if (take("for")) {
                expect("(");
                expect("let");
                Node init = binding("let");
                expect(";");
                Node condition = expression(0);
                expect(";");
                String name = identifier();
                Node update;
                if (take("++")) {
                    update = node("assign", name, null, node("binary", "+", null,
                            node("variable", name, null), node("literal", "", 1.0)));
                } else {
                    expect("=");
                    update = node("assign", name, null, expression(0));
                }
                if (!name.equals(init.text())) invalid("For update must target its local counter");
                expect(")");
                return node("for", "", null, init, condition, update, block());
            }
            String name = identifier();
            if (take("=")) {
                Node result = node("assign", name, null, expression(0));
                expect(";");
                return result;
            }
            if (!commands.contains(name)) invalid("Unknown command: " + name);
            expect("(");
            var args = new ArrayList<Node>();
            var names = new HashSet<String>();
            if (!is(")")) {
                do {
                    String key = identifier();
                    if (!names.add(key)) invalid("Duplicate argument: " + key);
                    expect("=");
                    args.add(node("argument", key, null, expression(0)));
                } while (take(","));
            }
            expect(")");
            expect(";");
            return node("call", name, null, args);
        } finally {
            nesting--;
        }
    }

    private Node binding(String kind) {
        String name = identifier();
        expect("=");
        return node(kind, name, null, expression(0));
    }

    private Node block() {
        expect("{");
        var statements = new ArrayList<Node>();
        while (!is("}")) statements.add(statement());
        expect("}");
        return node("block", "", null, statements);
    }

    private Node expression(int minimum) {
        enter();
        try {
            Node left = primary();
            while (precedence(token.text()) >= minimum && token.kind().equals("symbol")) {
                String operator = token.text();
                int precedence = precedence(operator);
                advance();
                left = node("binary", operator, null, left, expression(precedence + 1));
            }
            return left;
        } finally {
            nesting--;
        }
    }

    private Node primary() {
        if (take("[")) {
            var values = new ArrayList<Node>();
            if (!is("]")) {
                do { values.add(expression(0)); } while (take(","));
            }
            expect("]");
            return node("array", "", null, values);
        }
        if (take("{")) {
            var values = new ArrayList<Node>();
            var keys = new HashSet<String>();
            if (!is("}")) {
                do {
                    String key;
                    if (token.kind().equals("string")) { key = token.text(); advance(); }
                    else key = identifier();
                    if (!keys.add(key)) invalid("Duplicate object key");
                    expect(":");
                    values.add(node("property", key, null, expression(0)));
                } while (take(","));
            }
            expect("}");
            return node("object", "", null, values);
        }
        if (is("!") || is("-") || is("+")) {
            String operator = token.text();
            advance();
            return node("unary", operator, null, expression(7));
        }
        if (take("(")) {
            Node result = expression(0);
            expect(")");
            return result;
        }
        if (token.kind().equals("number")) {
            String number = token.text();
            advance();
            try {
                return node("literal", "", finite(Double.parseDouble(number)));
            } catch (NumberFormatException failure) {
                throw new Stop(Status.INVALID, "Invalid number");
            }
        }
        if (token.kind().equals("string")) {
            String value = token.text();
            advance();
            return node("literal", "", value);
        }
        if (take("true")) return node("literal", "", true);
        if (take("false")) return node("literal", "", false);
        if (take("null")) return node("literal", "", null);
        return node("variable", identifier(), null);
    }

    private static int precedence(String operator) {
        return switch (operator) {
            case "||" -> 0;
            case "&&" -> 1;
            case "==", "!=" -> 2;
            case "<", "<=", ">", ">=" -> 3;
            case "+", "-" -> 4;
            case "*", "/", "%" -> 5;
            default -> -1;
        };
    }

    private Node node(String kind, String text, Object value, Node... children) {
        return node(kind, text, value, List.of(children));
    }
    private Node node(String kind, String text, Object value, List<Node> children) {
        if (++nodes > MAX_NODES) throw new Stop(Status.LIMIT, "AST node limit");
        int depth = 1;
        for (Node child : children) depth = Math.max(depth, child.depth() + 1);
        if (depth > MAX_DEPTH) throw new Stop(Status.LIMIT, "AST depth limit");
        return new Node(kind, text, value, List.copyOf(children), depth);
    }
    private void enter() {
        if (++nesting > MAX_DEPTH) throw new Stop(Status.LIMIT, "Syntax depth limit");
    }
    private String identifier() {
        if (!token.kind().equals("identifier") || RESERVED.contains(token.text())) invalid("Identifier required");
        String name = token.text();
        advance();
        return name;
    }
    private boolean is(String text) {
        return !token.kind().equals("string") && token.text().equals(text);
    }
    private boolean take(String text) {
        if (!is(text)) return false;
        advance();
        return true;
    }
    private void expect(String text) { if (!take(text)) invalid("Expected " + text); }
    private void invalid(String detail) { throw new Stop(Status.INVALID, detail + " at " + token.offset()); }

    private void advance() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new Stop(Status.CANCELLED, "Cancelled while parsing");
        while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) offset++;
        int start = offset;
        if (offset == source.length()) {
            token = new Token("end", "", offset);
            return;
        }
        char c = source.charAt(offset++);
        if (letter(c)) {
            while (offset < source.length() && (letter(source.charAt(offset)) || digit(source.charAt(offset)))) offset++;
            token = new Token("identifier", source.substring(start, offset), start);
        } else if (digit(c)) {
            while (offset < source.length() && digit(source.charAt(offset))) offset++;
            if (offset < source.length() && source.charAt(offset) == '.') {
                offset++;
                while (offset < source.length() && digit(source.charAt(offset))) offset++;
            }
            if (offset < source.length() && (source.charAt(offset) == 'e' || source.charAt(offset) == 'E')) {
                offset++;
                if (offset < source.length() && (source.charAt(offset) == '+' || source.charAt(offset) == '-')) offset++;
                while (offset < source.length() && digit(source.charAt(offset))) offset++;
            }
            token = new Token("number", source.substring(start, offset), start);
        } else if (c == '\'' || c == '"') {
            var value = new StringBuilder();
            boolean closed = false;
            while (offset < source.length()) {
                char next = source.charAt(offset++);
                if (next == c) { closed = true; break; }
                if (next < 32) throw new Stop(Status.INVALID, "Control character in string");
                if (next == '\\') {
                    if (offset == source.length()) break;
                    next = switch (source.charAt(offset++)) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case '\\' -> '\\';
                        case '\'' -> '\'';
                        case '"' -> '"';
                        default -> throw new Stop(Status.INVALID, "Invalid string escape");
                    };
                }
                value.append(next);
            }
            if (!closed) throw new Stop(Status.INVALID, "Unterminated string");
            token = new Token("string", value.toString(), start);
        } else {
            String symbol = String.valueOf(c);
            if (offset < source.length()) {
                String pair = symbol + source.charAt(offset);
                if (Set.of("==", "!=", "<=", ">=", "&&", "||", "++").contains(pair)) {
                    symbol = pair;
                    offset++;
                }
            }
            if ("(){}[]:;,=+-*/%!<>".indexOf(c) < 0 && !symbol.equals("&&") && !symbol.equals("||"))
                throw new Stop(Status.INVALID, "Forbidden character at " + start);
            token = new Token("symbol", symbol, start);
        }
    }
    private static boolean letter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_'; }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
}
