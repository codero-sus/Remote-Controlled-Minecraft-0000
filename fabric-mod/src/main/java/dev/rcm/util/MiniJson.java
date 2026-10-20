package dev.rcm.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-contained RFC 8259 JSON parser and serializer compatible with Java 8 through Java 25+.
 */
public final class MiniJson {
	private MiniJson() {
	}

	public static Object parse(String json) {
		if (json == null) {
			return null;
		}
		Parser p = new Parser(json);
		Object value = p.parseValue();
		p.skipWhitespace();
		return value;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String json) {
		Object parsed = parse(json);
		if (parsed instanceof Map) {
			return (Map<String, Object>) parsed;
		}
		return new LinkedHashMap<String, Object>();
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> asMap(Object obj) {
		if (obj instanceof Map) {
			return (Map<String, Object>) obj;
		}
		return Collections.emptyMap();
	}

	@SuppressWarnings("unchecked")
	public static List<Object> asList(Object obj) {
		if (obj instanceof List) {
			return (List<Object>) obj;
		}
		return Collections.emptyList();
	}

	public static String getString(Map<String, Object> map, String key, String fallback) {
		if (map == null) {
			return fallback;
		}
		Object v = map.get(key);
		return v == null ? fallback : String.valueOf(v);
	}

	public static long getLong(Map<String, Object> map, String key, long fallback) {
		if (map == null) {
			return fallback;
		}
		Object v = map.get(key);
		if (v instanceof Number) {
			return ((Number) v).longValue();
		}
		if (v != null) {
			try {
				return Long.parseLong(String.valueOf(v).trim());
			} catch (NumberFormatException ignored) {
			}
		}
		return fallback;
	}

	public static int getInt(Map<String, Object> map, String key, int fallback) {
		return (int) getLong(map, key, fallback);
	}

	public static double getDouble(Map<String, Object> map, String key, double fallback) {
		if (map == null) {
			return fallback;
		}
		Object v = map.get(key);
		if (v instanceof Number) {
			return ((Number) v).doubleValue();
		}
		if (v != null) {
			try {
				return Double.parseDouble(String.valueOf(v).trim());
			} catch (NumberFormatException ignored) {
			}
		}
		return fallback;
	}

	public static boolean getBoolean(Map<String, Object> map, String key, boolean fallback) {
		if (map == null) {
			return fallback;
		}
		Object v = map.get(key);
		if (v instanceof Boolean) {
			return ((Boolean) v).booleanValue();
		}
		if (v != null) {
			String s = String.valueOf(v).trim();
			if ("true".equalsIgnoreCase(s)) {
				return true;
			}
			if ("false".equalsIgnoreCase(s)) {
				return false;
			}
		}
		return fallback;
	}

	public static String stringify(Object value) {
		StringBuilder sb = new StringBuilder();
		writeValue(sb, value, false, 0);
		return sb.toString();
	}

	public static String stringifyPretty(Object value) {
		StringBuilder sb = new StringBuilder();
		writeValue(sb, value, true, 0);
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static void writeValue(StringBuilder sb, Object value, boolean pretty, int depth) {
		if (value == null) {
			sb.append("null");
		} else if (value instanceof Boolean) {
			sb.append(((Boolean) value).booleanValue() ? "true" : "false");
		} else if (value instanceof Number) {
			double d = ((Number) value).doubleValue();
			if (Double.isNaN(d) || Double.isInfinite(d)) {
				sb.append("0");
			} else {
				sb.append(value.toString());
			}
		} else if (value instanceof String) {
			writeEscapedString(sb, (String) value);
		} else if (value instanceof Map) {
			Map<Object, Object> map = (Map<Object, Object>) value;
			if (map.isEmpty()) {
				sb.append("{}");
				return;
			}
			sb.append('{');
			if (pretty) {
				sb.append('\n');
			}
			Iterator<Map.Entry<Object, Object>> it = map.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Object, Object> entry = it.next();
				if (pretty) {
					indent(sb, depth + 1);
				}
				writeEscapedString(sb, String.valueOf(entry.getKey()));
				sb.append(pretty ? ": " : ":");
				writeValue(sb, entry.getValue(), pretty, depth + 1);
				if (it.hasNext()) {
					sb.append(',');
				}
				if (pretty) {
					sb.append('\n');
				}
			}
			if (pretty) {
				indent(sb, depth);
			}
			sb.append('}');
		} else if (value instanceof Collection) {
			Collection<Object> col = (Collection<Object>) value;
			if (col.isEmpty()) {
				sb.append("[]");
				return;
			}
			sb.append('[');
			if (pretty) {
				sb.append('\n');
			}
			Iterator<Object> it = col.iterator();
			while (it.hasNext()) {
				if (pretty) {
					indent(sb, depth + 1);
				}
				writeValue(sb, it.next(), pretty, depth + 1);
				if (it.hasNext()) {
					sb.append(',');
				}
				if (pretty) {
					sb.append('\n');
				}
			}
			if (pretty) {
				indent(sb, depth);
			}
			sb.append(']');
		} else {
			writeEscapedString(sb, String.valueOf(value));
		}
	}

	private static void indent(StringBuilder sb, int depth) {
		for (int i = 0; i < depth; i++) {
			sb.append("  ");
		}
	}

	public static void writeEscapedString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\b':
					sb.append("\\b");
					break;
				case '\f':
					sb.append("\\f");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", Integer.valueOf((int) c)));
					} else {
						sb.append(c);
					}
					break;
			}
		}
		sb.append('"');
	}

	private static final class Parser {
		private final String src;
		private int pos;

		Parser(String src) {
			this.src = src;
			this.pos = 0;
		}

		void skipWhitespace() {
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
					pos++;
				} else {
					break;
				}
			}
		}

		Object parseValue() {
			skipWhitespace();
			if (pos >= src.length()) {
				return null;
			}
			char c = src.charAt(pos);
			switch (c) {
				case '{':
					return parseObject();
				case '[':
					return parseArray();
				case '"':
					return parseString();
				case 't':
				case 'f':
					return parseBoolean();
				case 'n':
					return parseNull();
				default:
					return parseNumber();
			}
		}

		Map<String, Object> parseObject() {
			Map<String, Object> map = new LinkedHashMap<String, Object>();
			pos++; // '{'
			skipWhitespace();
			if (pos < src.length() && src.charAt(pos) == '}') {
				pos++;
				return map;
			}
			while (pos < src.length()) {
				skipWhitespace();
				String key = parseString();
				skipWhitespace();
				if (pos < src.length() && src.charAt(pos) == ':') {
					pos++;
				}
				Object val = parseValue();
				map.put(key, val);
				skipWhitespace();
				if (pos < src.length() && src.charAt(pos) == ',') {
					pos++;
				} else if (pos < src.length() && src.charAt(pos) == '}') {
					pos++;
					break;
				} else {
					break;
				}
			}
			return map;
		}

		List<Object> parseArray() {
			List<Object> list = new ArrayList<Object>();
			pos++; // '['
			skipWhitespace();
			if (pos < src.length() && src.charAt(pos) == ']') {
				pos++;
				return list;
			}
			while (pos < src.length()) {
				list.add(parseValue());
				skipWhitespace();
				if (pos < src.length() && src.charAt(pos) == ',') {
					pos++;
				} else if (pos < src.length() && src.charAt(pos) == ']') {
					pos++;
					break;
				} else {
					break;
				}
			}
			return list;
		}

		String parseString() {
			if (src.charAt(pos) == '"') {
				pos++;
			}
			StringBuilder sb = new StringBuilder();
			while (pos < src.length()) {
				char c = src.charAt(pos++);
				if (c == '"') {
					break;
				}
				if (c == '\\' && pos < src.length()) {
					char esc = src.charAt(pos++);
					switch (esc) {
						case '"':
							sb.append('"');
							break;
						case '\\':
							sb.append('\\');
							break;
						case '/':
							sb.append('/');
							break;
						case 'b':
							sb.append('\b');
							break;
						case 'f':
							sb.append('\f');
							break;
						case 'n':
							sb.append('\n');
							break;
						case 'r':
							sb.append('\r');
							break;
						case 't':
							sb.append('\t');
							break;
						case 'u':
							if (pos + 4 <= src.length()) {
								String hex = src.substring(pos, pos + 4);
								pos += 4;
								sb.append((char) Integer.parseInt(hex, 16));
							}
							break;
						default:
							sb.append(esc);
							break;
					}
				} else {
					sb.append(c);
				}
			}
			return sb.toString();
		}

		Boolean parseBoolean() {
			if (src.startsWith("true", pos)) {
				pos += 4;
				return Boolean.TRUE;
			}
			if (src.startsWith("false", pos)) {
				pos += 5;
				return Boolean.FALSE;
			}
			throw new IllegalArgumentException("Unexpected token at " + pos);
		}

		Object parseNull() {
			if (src.startsWith("null", pos)) {
				pos += 4;
				return null;
			}
			throw new IllegalArgumentException("Unexpected token at " + pos);
		}

		Number parseNumber() {
			int start = pos;
			if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
				pos++;
			}
			boolean floating = false;
			while (pos < src.length()) {
				char c = src.charAt(pos);
				if (c >= '0' && c <= '9') {
					pos++;
				} else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
					floating = true;
					pos++;
				} else {
					break;
				}
			}
			String numStr = src.substring(start, pos);
			if (floating) {
				return Double.valueOf(Double.parseDouble(numStr));
			}
			return Long.valueOf(Long.parseLong(numStr));
		}
	}
}
