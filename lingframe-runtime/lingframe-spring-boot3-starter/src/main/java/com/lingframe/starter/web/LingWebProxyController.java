package com.lingframe.starter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingframe.api.security.AccessType;
import com.lingframe.core.kernel.GovernanceKernel;
import com.lingframe.core.kernel.InvocationContext;
import com.lingframe.core.plugin.PluginManager;
import com.lingframe.core.plugin.PluginSlot;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
public class LingWebProxyController {

    private final WebInterfaceManager webInterfaceManager;
    private final PluginManager pluginManager;
    private final GovernanceKernel governanceKernel; // 🔥 注入内核
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    // 默认的 ObjectMapper，用于兜底（比如插件没配 Jackson）
    private final ObjectMapper fallbackMapper = new ObjectMapper();

    @ResponseBody
    public void dispatch(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String uri = request.getRequestURI();

        // 路由匹配
        WebInterfaceMetadata meta = webInterfaceManager.match(uri);
        if (meta == null) {
            response.sendError(404, "Interface not found: " + uri);
            return;
        }

        // 获取插件槽位
        PluginSlot slot = pluginManager.getSlot(meta.getPluginId());
        if (slot == null) {
            response.sendError(503, "Plugin not loaded: " + meta.getPluginId());
            return;
        }

        // 构建上下文
        InvocationContext ctx = InvocationContext.builder()
                .traceId(request.getHeader("X-Trace-Id"))
                .callerPluginId("host-gateway") // 标记来源
                .pluginId(meta.getPluginId())
                .resourceType("HTTP")
                .resourceId(meta.getUrlPattern())
                .operation(request.getMethod())
                // 🔥 填入扫描阶段算好的智能元数据
                .requiredPermission(meta.getRequiredPermission())
                .shouldAudit(meta.isShouldAudit())
                .auditAction(meta.getAuditAction())
                .accessType(AccessType.EXECUTE)
                // args 暂时为空，稍后在 executor 里回填
                .build();

        // 委托内核执行
        governanceKernel.invoke(slot, meta.getTargetMethod(), ctx, () -> {
            ClassLoader originalCL = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(meta.getClassLoader());

            try {
                // 获取插件 ObjectMapper(关键：保持序列化行为一致)
                ObjectMapper pluginMapper = getPluginObjectMapper(meta);

                // 解析参数 (此时已在插件 CL 环境)
                Object[] args = new Object[meta.getParameters().size()];
                for (int i = 0; i < meta.getParameters().size(); i++) {
                    WebInterfaceMetadata.ParamDef def = meta.getParameters().get(i);
                    if (def.getSourceType() == WebInterfaceMetadata.ParamType.REQUEST_BODY) {
                        // 直接流式读取，省内存
                        args[i] = pluginMapper.readValue(request.getInputStream(), def.getType());
                    } else if (def.getSourceType() == WebInterfaceMetadata.ParamType.PATH_VARIABLE) {
                        Map<String, String> vars = pathMatcher.extractUriTemplateVariables(meta.getUrlPattern(), uri);
                        args[i] = convert(vars.get(def.getName()), def.getType(), pluginMapper);
                    } else if (def.getSourceType() == WebInterfaceMetadata.ParamType.REQUEST_PARAM) {
                        String val = request.getParameter(def.getName());
                        args[i] = convert(val, def.getType(), pluginMapper);
                    } else if (def.getSourceType() == WebInterfaceMetadata.ParamType.SERVLET_REQUEST) {
                        args[i] = request;
                    } else if (def.getSourceType() == WebInterfaceMetadata.ParamType.SERVLET_RESPONSE) {
                        args[i] = response;
                    }
                }
                // 回填 args 以便审计
                ctx.setArgs(args);
                // 反射调用
                Object result = meta.getTargetMethod().invoke(meta.getTargetBean(), args);
                // 处理返回值
                if (result != null) {
                    response.setContentType("application/json;charset=UTF-8");
                    String jsonResult = pluginMapper.writeValueAsString(result);
                    response.getWriter().write(jsonResult);
                }
                return result;// 返回给 Kernel 做记录
            } catch (Exception e) {
                throw new RuntimeException(e);
            } finally {
                Thread.currentThread().setContextClassLoader(originalCL);
            }
        });
    }

    /**
     * 从插件容器中捞取 ObjectMapper
     */
    private ObjectMapper getPluginObjectMapper(WebInterfaceMetadata meta) {
        try {
            // 从插件自己的容器里拿，保持插件的配置
            if (meta.getPluginApplicationContext() != null) {
                return meta.getPluginApplicationContext().getBean(ObjectMapper.class);
            }
        } catch (Exception e) {
        }
        // 兜底
        return fallbackMapper;
    }

    // 简单的类型转换器
    private Object convert(String val, Class<?> type, ObjectMapper mapper) {
        if (val == null) return null;
        if (type == String.class) return val;
        // 借用 Jackson 做基础类型转换，它很擅长这个
        try {
            return mapper.convertValue(val, type);
        } catch (Exception e) {
            // 降级处理
            if (type == Integer.class || type == int.class) return Integer.valueOf(val);
            if (type == Long.class || type == long.class) return Long.valueOf(val);
            if (type == Boolean.class || type == boolean.class) return Boolean.valueOf(val);
            return val;
        }
    }
}