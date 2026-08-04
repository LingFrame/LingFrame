package com.lingframe.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.regex.Pattern;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private boolean success;
    private String message;
    private T data;
    private long timestamp;

    /**
     * 异常消息脱敏正则：剥离常见堆栈帧行与内部包路径，避免把实现细节/堆栈回显给前端。
     * <p>
     * 控制器 catch 分支直接拼接 {@code e.getMessage()} 时，若消息含堆栈线索（如
     * {@code " at com.lingframe..."} 或 {@code java.lang.XXX}）会被一并返回。
     * 这里做集中清洗，一处改动覆盖所有调用点，前端契约（success=false + message）保持不变。
     * <p>
     * 覆盖范围（防御性，非穷举）：
     * <ul>
     *   <li>{@code " at <fqcn>(<file>:<line>)"} 堆栈帧</li>
     *   <li>{@code "java.lang.XXX"} / {@code "com.lingframe.XXX"} 内部包前缀类名</li>
     *   <li>{@code "Caused by: ..."} / {@code "... N more"} 堆栈尾部</li>
     * </ul>
     * 纯文本异常消息（如 {@code "contractId must not be null"}）不受影响。
     */
    private static final Pattern STACK_FRAME_PATTERN = Pattern.compile(
            "\\s+at\\s+[\\w.$]+\\([^)]*\\)"
                    + "|\\s+java\\.lang\\.[\\w.$]+"
                    + "|\\s+com\\.lingframe\\.[\\w.$]+"
                    + "|Caused by:\\s.*"
                    + "|\\.\\.\\.\\s+\\d+\\s+more");

    public static <T> ApiResponse<T> ok(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .message(message)
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(sanitize(message))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 基于异常的错误响应：通用异常返回通用提示，不泄漏敏感内部细节。
     * <p>
     * 与 {@link com.lingframe.dashboard.config.DashboardExceptionHandler} 的脱敏姿态一致：
     * 可预期的业务异常（IllegalArgumentException / IllegalStateException）返回其消息，
     * 其余异常统一返回通用提示，避免把堆栈/内部状态回显给前端。
     */
    public static <T> ApiResponse<T> error(Throwable throwable) {
        if (throwable instanceof IllegalArgumentException
                || throwable instanceof IllegalStateException) {
            return error(throwable.getMessage());
        }
        return error("服务内部错误，请稍后重试");
    }

    private static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        return STACK_FRAME_PATTERN.matcher(message).replaceAll("").trim();
    }
}
