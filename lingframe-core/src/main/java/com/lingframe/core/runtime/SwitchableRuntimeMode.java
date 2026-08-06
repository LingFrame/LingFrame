package com.lingframe.core.runtime;

import lombok.extern.slf4j.Slf4j;

import com.lingframe.core.config.LingFrameConfig;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可切换运行时模式实现（dev/prod 运行时切换）。
 * <p>
 * 设计目标：在保持 {@link LingFrameConfig} 不可变的前提下，
 * 通过 volatile 覆盖值实现运行时 dev/prod 切换，下沉到 core 层并强制密码二次认证。
 * <p>
 * 安全语义：
 * <ul>
 *   <li>密码未配置（null/空）→ 切换功能关闭（fail-closed），拒绝一切运行时切换请求</li>
 *   <li>密码已配置 → 切换前必须提供正确密码，防止 Dashboard 被攻破后直接降级安全防线</li>
 *   <li>密码以 SHA-256(salt + password) 形式存储，明文不驻留内存</li>
 *   <li>密码比较使用常量时间算法（{@link MessageDigest#isEqual}），防御时序攻击</li>
 *   <li>连续认证失败 {@value #MAX_FAIL_ATTEMPTS} 次后锁定 {@value #LOCK_DURATION_MINUTES} 分钟，防御暴力破解</li>
 * </ul>
 * 线程安全：override、lockUntil 为 volatile，switchMode 同步保护认证+切换的复合操作。
 * <p>
 * 非 Spring 环境使用：直接 {@code new SwitchableRuntimeMode(devMode, password)} 构造，
 * 传给 {@link LingFrameConfig.Builder#runtimeMode(RuntimeMode)}。
 */
@Slf4j
public final class SwitchableRuntimeMode implements RuntimeMode {

    /** 认证失败达到此次数后锁定 */
    private static final int MAX_FAIL_ATTEMPTS = 5;

    /** 锁定时长（分钟） */
    private static final long LOCK_DURATION_MINUTES = 5;

    /** 锁定时长（毫秒） */
    private static final long LOCK_DURATION_MS = LOCK_DURATION_MINUTES * 60 * 1000L;

    /** 盐值长度（字节） */
    private static final int SALT_LENGTH = 16;

    /**
     * 复用 SecureRandom，避免 SpotBugs DMI_RANDOM_USED_ONLY_ONCE
     * （每次 new SecureRandom 仅 nextBytes 一次）。
     * SecureRandom 线程安全，可作为静态实例共享。
     */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** 运行时 devMode 覆盖值：null 表示未覆盖（使用配置基线），非 null 表示运行时切换后的值 */
    private volatile Boolean override = null;

    /** 配置基线 devMode（构造时确定，不可变） */
    private final boolean configDevMode;

    /** 密码哈希：null 表示切换功能关闭（fail-closed） */
    private final byte[] passwordHash;

    /** 密码盐值（启动时随机生成） */
    private final byte[] salt;

    /** 认证失败计数 */
    private final AtomicInteger failCount = new AtomicInteger(0);

    /** 锁定截止时间戳（System.currentTimeMillis()），0 表示未锁定 */
    private volatile long lockUntil = 0;

    /**
     * 构造可切换运行时模式。
     *
     * @param configDevMode 配置基线 devMode
     * @param password      模式切换密码，null/空表示关闭切换功能（fail-closed）
     */
    public SwitchableRuntimeMode(boolean configDevMode, String password) {
        this.configDevMode = configDevMode;
        if (password != null && !password.isEmpty()) {
            this.salt = generateSalt();
            this.passwordHash = hashPassword(password, this.salt);
            log.info("[RuntimeMode] Mode switch enabled (initial mode from config: {})",
                    configDevMode ? "DEV" : "PROD");
        } else {
            this.salt = null;
            this.passwordHash = null;
            log.info("[RuntimeMode] Mode switch disabled (fail-closed), initial mode: {}",
                    configDevMode ? "DEV" : "PROD");
        }
    }

    @Override
    public boolean isDev() {
        Boolean current = override;
        return current != null ? current : configDevMode;
    }

    @Override
    public boolean isSwitchEnabled() {
        return passwordHash != null;
    }

    /**
     * 切换运行时模式（需密码认证）。
     *
     * @param dev      目标模式：true=开发模式，false=生产模式
     * @param password 模式切换密码
     * @throws SecurityException 密码未配置、密码错误、或处于锁定状态
     */
    public synchronized void switchMode(boolean dev, String password) {
        if (passwordHash == null) {
            throw new SecurityException("运行时模式切换未启用：未配置 lingframe.mode-switch-password");
        }

        long now = System.currentTimeMillis();
        if (now < lockUntil) {
            long remainingSec = (lockUntil - now) / 1000;
            throw new SecurityException("模式切换已被锁定，请 " + remainingSec + " 秒后重试");
        }

        if (!verifyPassword(password)) {
            int fails = failCount.incrementAndGet();
            log.warn("[RuntimeMode] Mode switch authentication failed (attempt {}/{})", fails, MAX_FAIL_ATTEMPTS);
            if (fails >= MAX_FAIL_ATTEMPTS) {
                lockUntil = now + LOCK_DURATION_MS;
                failCount.set(0);
                log.warn("[RuntimeMode] Mode switch locked for {} minutes due to repeated auth failures",
                        LOCK_DURATION_MINUTES);
                throw new SecurityException("认证失败次数过多，模式切换已锁定 " + LOCK_DURATION_MINUTES + " 分钟");
            }
            throw new SecurityException("模式切换认证失败：密码错误");
        }

        // 认证成功，重置计数
        failCount.set(0);
        override = dev;
        log.info("[RuntimeMode] Mode switched to: {} (authenticated)", dev ? "DEV" : "PROD");
    }

    /**
     * 重置运行时状态（仅用于测试 teardown）。
     * <p>
     * 清除覆盖值与失败计数，但不改变密码配置。
     */
    void reset() {
        override = null;
        failCount.set(0);
        lockUntil = 0;
    }

    /**
     * 当前是否处于锁定状态。
     *
     * @return true 表示处于锁定窗口内
     */
    public boolean isLocked() {
        return System.currentTimeMillis() < lockUntil;
    }

    /**
     * 当前认证失败次数（用于监控/展示）。
     *
     * @return 失败次数
     */
    public int getFailCount() {
        return failCount.get();
    }

    private boolean verifyPassword(String input) {
        if (input == null || salt == null || passwordHash == null) {
            return false;
        }
        byte[] inputHash = hashPassword(input, salt);
        try {
            return MessageDigest.isEqual(inputHash, passwordHash);
        } finally {
            // 清理临时哈希，避免内存残留
            Arrays.fill(inputHash, (byte) 0);
        }
    }

    private static byte[] hashPassword(String password, byte[] salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            return digest.digest(password.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 标准算法，理论上不会缺失
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    private static byte[] generateSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }
}
