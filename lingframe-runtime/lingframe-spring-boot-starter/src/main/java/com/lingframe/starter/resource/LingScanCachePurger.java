package com.lingframe.starter.resource;

/**
 * 灵元注册扫描后的有界静态缓存清理入口。
 * <p>
 * 仅清理与注解/反射扫描直接相关的 Spring 静态 Map 中「键属于该灵元 ClassLoader」的条目，
 * 用于缩短 {@link com.lingframe.starter.web.LingWebMetadataExtractor} 写入缓存的存活窗口。
 * <p>
 * <b>不替代</b>卸载时的 {@link SpringStaticCacheCleaner#clearStablePublicCaches} 全量清扫。
 */
public final class LingScanCachePurger {

    private static final SpringStaticCacheCleaner CLEANER = new SpringStaticCacheCleaner();

    private LingScanCachePurger() {
    }

    /**
     * Web 元数据提取并注册完成后调用。
     *
     * @param lingId          灵元 ID（日志）
     * @param lingClassLoader 灵元 ClassLoader；null 则 no-op
     */
    public static void purgeAnnotationCachesAfterMetadataExtract(String lingId, ClassLoader lingClassLoader) {
        if (lingClassLoader == null) {
            return;
        }
        String id = lingId != null ? lingId : "unknown";
        CLEANER.purgeAnnotationCachesAfterScan(id, lingClassLoader);
    }
}
