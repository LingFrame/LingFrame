package com.lingframe.api.security;

/**
 * 访问类型枚举
 * 定义了权限检查时的操作类型，支持层级比较。
 * <p>
 * 权限层级：READ(1) < WRITE(2) < EXECUTE(3)
 * </p>
 * <p>
 * 规则：高级别权限自动包含低级别权限。
 * 例如：拥有 WRITE 权限的插件自动拥有 READ 权限。
 * </p>
 *
 * @author LingFrame
 */
public enum AccessType {
    /**
     * 明确拒绝 - 表示权限被明确禁止
     * 用于治理中心主动关闭权限的场景
     */
    NONE(0),

    /**
     * 读取权限 - 最低级别
     */
    READ(1),

    /**
     * 写入权限 - 包含 READ
     */
    WRITE(2),

    /**
     * 执行权限 - 最高级别，包含 READ 和 WRITE
     * 用于执行脚本、存储过程等危险操作
     */
    EXECUTE(3);

    private final int level;

    AccessType(int level) {
        this.level = level;
    }

    /**
     * 获取权限级别
     *
     * @return 权限级别数值
     */
    public int getLevel() {
        return level;
    }

    /**
     * 判断当前权限是否满足所需权限
     * <p>
     * 例如：WRITE.satisfies(READ) = true（有写权限就有读权限）
     * </p>
     *
     * @param required 所需的权限类型
     * @return 如果当前权限级别 >= 所需权限级别，返回 true
     */
    public boolean satisfies(AccessType required) {
        // NONE 不满足任何权限需求（包括 NONE 本身）
        if (this == NONE) {
            return false;
        }
        return this.level >= required.level;
    }

    /**
     * 判断当前权限级别是否至少达到指定级别
     *
     * @param other 比较的权限类型
     * @return 如果当前权限级别 >= 指定权限级别，返回 true
     */
    public boolean isAtLeast(AccessType other) {
        return this.level >= other.level;
    }

    /**
     * 获取两个权限中级别较高的那个
     *
     * @param other 另一个权限类型
     * @return 级别较高的权限类型
     */
    public AccessType max(AccessType other) {
        return this.level >= other.level ? this : other;
    }

    /**
     * 获取两个权限中级别较低的那个
     *
     * @param other 另一个权限类型
     * @return 级别较低的权限类型
     */
    public AccessType min(AccessType other) {
        return this.level <= other.level ? this : other;
    }
}
