package logisimmcp;

import java.util.*;

/**
 * Pure Java, dependency-free JSON parser and serializer.
 * Preserves integer vs floating-point distinction and enforces MAX_DEPTH.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private Json() {}

    public static String stringify(Object obj) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, obj, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object obj, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nesting exceeds max depth");
        }
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof Boolean) {
            sb.append(((Boolean) obj) ? "true" : "false");
        } else if (obj instanceof Integer || obj instanceof Long || obj instanceof Short || obj instanceof Byte) {
            sb.append(obj.toString());
        } else if (obj instanceof Number) {
            double d = ((Number) obj).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                sb.append("null");
            } else {
                sb.append(obj.toString());
            }
        } else if (obj instanceof String) {
            writeString(sb, (String) obj);
        } else if (obj instanceof Map<?, ?>) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                write(sb, entry.getValue(), depth + 1);
            }
            sb.append('}');
        } else if (obj instanceof Collection<?>) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Collection<?>) obj) {
                if (!first) sb.append(',');
                first = false;
                write(sb, item, depth + 1);
            }
            sb.append(']');
        } else if (obj.getClass().isArray()) {
            sb.append('[');
            int len = java.lang.reflect.Array.getLength(obj);
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(',');
                write(sb, java.lang.reflect.Array.get(obj, i), depth + 1);
            }
            sb.append(']');
        } else {
            writeString(sb, obj.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("Null JSON input");
        Parser p = new Parser(text);
        Object val = p.parseValue(0);
        p.skipWhitespace();
        if (!p.eof()) {
            throw new IllegalArgumentException("Unexpected trailing characters at position " + p.pos);
        }
        return val;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected JSON object");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private final int len;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.len = s.length();
            this.pos = 0;
        }

        boolean eof() { return pos >= len; }

        void skipWhitespace() {
            while (pos < len) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }

        Object parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("JSON exceeds max nesting depth " + MAX_DEPTH);
            }
            skipWhitespace();
            if (pos >= len) throw new IllegalArgumentException("Unexpected end of JSON");
            char c = s.charAt(pos);
            if (c == '{') return parseObject(depth);
            if (c == '[') return parseArray(depth);
            if (c == '"') return parseString();
            if (c == 't') return expectLiteral("true", Boolean.TRUE);
            if (c == 'f') return expectLiteral("false", Boolean.FALSE);
            if (c == 'n') return expectLiteral("null", null);
            if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
            throw new IllegalArgumentException("Unexpected character '" + c + "' at position " + pos);
        }

        Object expectLiteral(String lit, Object val) {
            if (s.regionMatches(pos, lit, 0, lit.length())) {
                pos += lit.length();
                return val;
            }
            throw new IllegalArgumentException("Invalid literal at position " + pos);
        }

        Map<String, Object> parseObject(int depth) {
            pos++; // '{'
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (pos < len && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (pos >= len || s.charAt(pos) != '"') {
                    throw new IllegalArgumentException("Expected string key at position " + pos);
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= len || s.charAt(pos) != ':') {
                    throw new IllegalArgumentException("Expected ':' at position " + pos);
                }
                pos++;
                Object val = parseValue(depth + 1);
                map.put(key, val);
                skipWhitespace();
                if (pos >= len) throw new IllegalArgumentException("Unterminated object");
                char c = s.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or '}' at position " + (pos - 1));
            }
        }

        List<Object> parseArray(int depth) {
            pos++; // '['
            List<Object> list = new ArrayList<Object>();
            skipWhitespace();
            if (pos < len && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue(depth + 1));
                skipWhitespace();
                if (pos >= len) throw new IllegalArgumentException("Unterminated array");
                char c = s.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw new IllegalArgumentException("Expected ',' or ']' at position " + (pos - 1));
            }
        }

        String parseString() {
            pos++; // '"'
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= len) throw new IllegalArgumentException("Unterminated escape");
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > len) throw new IllegalArgumentException("Invalid unicode escape");
                            int cp = Integer.parseInt(s.substring(pos, pos + 4), 16);
                            sb.append((char) cp);
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Invalid escape '\\" + esc + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unterminated string");
        }

        Number parseNumber() {
            int start = pos;
            if (s.charAt(pos) == '-') pos++;
            if (pos >= len) throw new IllegalArgumentException("Invalid number");
            if (s.charAt(pos) == '0') {
                pos++;
            } else if (s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
                while (pos < len && Character.isDigit(s.charAt(pos))) pos++;
            } else {
                throw new IllegalArgumentException("Invalid number at position " + start);
            }
            boolean isFloat = false;
            if (pos < len && s.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                if (pos >= len || !Character.isDigit(s.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid fraction at position " + pos);
                }
                while (pos < len && Character.isDigit(s.charAt(pos))) pos++;
            }
            if (pos < len && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < len && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
                if (pos >= len || !Character.isDigit(s.charAt(pos))) {
                    throw new IllegalArgumentException("Invalid exponent at position " + pos);
                }
                while (pos < len && Character.isDigit(s.charAt(pos))) pos++;
            }
            String numStr = s.substring(start, pos);
            if (isFloat) {
                return Double.valueOf(Double.parseDouble(numStr));
            }
            return Long.valueOf(Long.parseLong(numStr));
        }
    }
}
