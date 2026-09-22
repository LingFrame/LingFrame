package com.lingframe.core.util;


/**
 * 语义版本号比较工具。
 * <p>
 * 提供基于语义版本（Semantic Versioning）的比较，避免字符串字典序导致的
 * 版本顺序错误（例如字典序下 "1.9.0" &gt; "1.10.0"，与语义版本约定相反）。
 */
public final class VersionUtils {

    private VersionUtils() {
    }

    /**
     * 语义版本号降序比较：返回值约定同 {@link Comparator#compare(Object, Object)}，
     * 即 v1 &gt; v2 时返回负数（v1 排前），实现「新版本优先」。
     * <p>
     * 按 {@code .} 分段，每段尝试解析为数字：
     * <ul>
     *   <li>纯数字段按数值比较（1.10 &gt; 1.9，避免字典序陷阱）；</li>
     *   <li>非数字段（如 alpha/beta/RC1）回退字典序，保证有确定顺序；</li>
     *   <li>段数不一致时，缺失段视为 0（1.2 等价于 1.2.0）。</li>
     * </ul>
     * 非法或 null 输入兜底为相等，避免拖垮调用方（如选举流程）。
     *
     * @param v1 版本号 1
     * @param v2 版本号 2
     * @return v1 &gt; v2 返回负数，v1 &lt; v2 返回正数，相等返回 0
     */
    public static int compareDescending(String v1, String v2) {
        if (v1 == null || v2 == null) {
            return 0;
        }
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            String s1 = i < p1.length ? p1[i] : "";
            String s2 = i < p2.length ? p2[i] : "";
            int n1 = tryParseInt(s1);
            int n2 = tryParseInt(s2);
            int cmp;
            if (n1 != Integer.MIN_VALUE && n2 != Integer.MIN_VALUE) {
                // 两段都是数字：按数值比较
                cmp = Integer.compare(n1, n2);
            } else {
                // 至少一段非数字：回退字典序（空串排前）
                cmp = s1.compareTo(s2);
            }
            if (cmp != 0) {
                return -cmp;   // 取负实现降序
            }
        }
        return 0;
    }

    private static int tryParseInt(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;   // 哨兵值表示非数字
        }
    }
}