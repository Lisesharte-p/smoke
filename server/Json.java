package server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具：不引入第三方库，只覆盖本项目接口用到的部分。
 * - 生成：Json.str() 转字符串字面量、Json.num() 转数字
 * - 解析：Json.parseObject() 把请求体 {"a":1,"b":"x"} 解析成 Map
 */
public class Json {

    /** 字符串转 JSON 字符串字面量（带引号 + 转义）；null → null */
    public static String str(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** 数字对象（BigDecimal/Double 等）转 JSON 数字；null → null */
    public static String num(Object n) {
        return n == null ? "null" : String.valueOf(n);
    }

    /**
     * 解析 JSON 对象 {"a":"x","b":123,"c":true,"d":null} → Map<key, 原始值>。
     * 字符串值会去引号转义；数字/布尔保持原文。支持值里有逗号（引号感知）。
     */
    public static Map<String, String> parseObject(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null) return map;
        String s = json.trim();
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);

        // 引号感知切分顶层键值对，避免值里的逗号/中文被误切
        List<String> pairs = new ArrayList<>();
        boolean inStr = false;
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == '\\') { if (i + 1 < s.length()) cur.append(s.charAt(++i)); }
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
                cur.append(c);
            } else if (c == '{' || c == '[') {
                depth++;
                cur.append(c);
            } else if (c == '}' || c == ']') {
                depth--;
                cur.append(c);
            } else if (c == ',' && depth == 0) {
                pairs.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) pairs.add(cur.toString());

        for (String pair : pairs) {
            int colon = pair.indexOf(':');
            if (colon <= 0) continue;
            String key = unquote(pair.substring(0, colon).trim());
            String value = unquote(pair.substring(colon + 1).trim());
            map.put(key, value);
        }
        return map;
    }

    /**
     * 从 JSON 里按 key 提取数组文本（含方括号），如取 "devices":[{...}] 里的 [{...}]。
     * 引号感知扫描，避免值里的 [ ] 被误判；key 不存在返回 null。
     */
    public static String arrayText(String json, String key) {
        if (json == null) return null;
        String marker = "\"" + key + "\"";
        int idx = json.indexOf(marker);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + marker.length());
        if (colon < 0) return null;
        int start = json.indexOf('[', colon);
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    /**
     * 解析 JSON 对象数组 [{...},{...}] → List<Map<String,String>>。
     * 逐个切出顶层对象，交给 parseObject 解析；空数组返回空列表。
     */
    public static List<Map<String, String>> parseObjectArray(String arrayText) {
        List<Map<String, String>> list = new ArrayList<>();
        if (arrayText == null) return list;
        String s = arrayText.trim();
        if (s.startsWith("[")) s = s.substring(1);
        if (s.endsWith("]")) s = s.substring(0, s.length() - 1);

        boolean inStr = false;
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                cur.append(c);
                if (c == '\\' && i + 1 < s.length()) cur.append(s.charAt(++i));
                else if (c == '"') inStr = false;
            } else if (c == '"') {
                inStr = true;
                cur.append(c);
            } else if (c == '{' || c == '[') {
                depth++;
                cur.append(c);
            } else if (c == '}' || c == ']') {
                depth--;
                cur.append(c);
                if (depth == 0) {
                    String obj = cur.toString().trim();
                    cur.setLength(0);
                    if (obj.startsWith("{")) list.add(parseObject(obj));
                }
            } else if (c == ',' && depth == 0) {
                // 对象之间的分隔符，跳过
            } else {
                cur.append(c);
            }
        }
        return list;
    }

    /** 去掉首尾引号并反转义（仅处理字符串值） */
    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1)
                    .replace("\\\"", "\"").replace("\\\\", "\\")
                    .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
        }
        return s;
    }
}
