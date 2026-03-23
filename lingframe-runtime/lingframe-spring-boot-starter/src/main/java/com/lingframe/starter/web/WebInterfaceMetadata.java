package com.lingframe.starter.web;

import lombok.Builder;
import lombok.Data;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 灵元 Controller 使用的 Web 接口元数据。
 *
 * <p>该对象会强持有稳定的路由信息，但会把运行期对象引用降级为弱引用或延迟查找，
 * 以避免宿主长期持有灵元 Controller 实例和 ApplicationContext。</p>
 */
@Data
@Builder
public class WebInterfaceMetadata {

    private static final Map<String, Class<?>> PRIMITIVE_TYPES = new HashMap<>();

    static {
        PRIMITIVE_TYPES.put("boolean", boolean.class);
        PRIMITIVE_TYPES.put("byte", byte.class);
        PRIMITIVE_TYPES.put("char", char.class);
        PRIMITIVE_TYPES.put("short", short.class);
        PRIMITIVE_TYPES.put("int", int.class);
        PRIMITIVE_TYPES.put("long", long.class);
        PRIMITIVE_TYPES.put("float", float.class);
        PRIMITIVE_TYPES.put("double", double.class);
        PRIMITIVE_TYPES.put("void", void.class);
    }

    private String lingId;
    private Object targetBean;
    private Method targetMethod;
    private ClassLoader classLoader;
    private ApplicationContext lingApplicationContext;

    private String targetBeanName;
    private String targetClassName;
    private String targetMethodName;
    private String[] targetMethodParameterTypeNames;
    private String springDocBeanName;

    private String urlPattern;
    private String httpMethod;
    private String[] params;
    private String[] headers;
    private String[] consumes;
    private String[] produces;

    private String requiredPermission;
    private boolean shouldAudit;
    private String auditAction;
    private String version;
    private String opSummary;
    private String opDescription;
    private String[] opTags;
    private transient RequestMappingInfo requestMappingInfo;

    private transient WeakReference<Object> targetBeanRef;
    private transient WeakReference<Method> targetMethodRef;
    private transient WeakReference<ClassLoader> classLoaderRef;
    private transient WeakReference<ApplicationContext> lingApplicationContextRef;

    public Object getTargetBean() {
        if (targetBean != null) {
            return targetBean;
        }
        Object bean = targetBeanRef != null ? targetBeanRef.get() : null;
        if (bean != null) {
            return bean;
        }
        ApplicationContext context = getLingApplicationContext();
        if (context == null || targetBeanName == null || targetBeanName.isEmpty()) {
            return null;
        }
        if (!context.containsBean(targetBeanName)) {
            return null;
        }
        bean = context.getBean(targetBeanName);
        if (bean != null) {
            targetBeanRef = new WeakReference<>(bean);
        }
        return bean;
    }

    public Method getTargetMethod() {
        if (targetMethod != null) {
            return targetMethod;
        }
        Method method = targetMethodRef != null ? targetMethodRef.get() : null;
        if (method != null) {
            return method;
        }

        Class<?> targetClass = resolveTargetClass();
        if (targetClass == null || targetMethodName == null || targetMethodName.isEmpty()) {
            return null;
        }

        Class<?>[] parameterTypes = resolveParameterTypes(targetClass.getClassLoader());
        if (parameterTypes != null) {
            method = ReflectionUtils.findMethod(targetClass, targetMethodName, parameterTypes);
        }
        if (method == null) {
            for (Method candidate : targetClass.getMethods()) {
                if (!candidate.getName().equals(targetMethodName)) {
                    continue;
                }
                if (matchesParameterTypes(candidate.getParameterTypes(), targetMethodParameterTypeNames)) {
                    method = candidate;
                    break;
                }
            }
        }
        if (method != null) {
            targetMethodRef = new WeakReference<>(method);
        }
        return method;
    }

    public Class<?> getTargetClass() {
        return resolveTargetClass();
    }

    public ClassLoader getClassLoader() {
        if (classLoader != null) {
            return classLoader;
        }
        return classLoaderRef != null ? classLoaderRef.get() : null;
    }

    public ApplicationContext getLingApplicationContext() {
        if (lingApplicationContext != null) {
            return lingApplicationContext;
        }
        return lingApplicationContextRef != null ? lingApplicationContextRef.get() : null;
    }

    public void minimizeHostReferences() {
        Class<?> targetClass = resolveUserClass(targetBean);
        if (targetClass != null) {
            targetClassName = targetClass.getName();
        }
        if (targetMethod != null) {
            targetMethodRef = new WeakReference<>(targetMethod);
            if (targetMethodName == null || targetMethodName.isEmpty()) {
                targetMethodName = targetMethod.getName();
            }
            if (targetClassName == null || targetClassName.isEmpty()) {
                targetClassName = targetMethod.getDeclaringClass().getName();
            }
            if (targetMethodParameterTypeNames == null) {
                targetMethodParameterTypeNames = resolveParameterTypeNames(targetMethod);
            }
            targetMethod = null;
        }
        if (targetBean != null) {
            targetBeanRef = new WeakReference<>(targetBean);
            targetBean = null;
        }
        // 🔥 classLoader 不降级为 WeakReference：卸载时依赖 ClassLoader 身份匹配，
        // 若被 GC 则无法正确识别待移除的元数据，导致路由残留
        if (lingApplicationContext != null) {
            lingApplicationContextRef = new WeakReference<>(lingApplicationContext);
            lingApplicationContext = null;
        }
    }

    public void clearReferences() {
        targetBean = null;
        targetMethod = null;
        classLoader = null;
        lingApplicationContext = null;
        targetBeanRef = null;
        targetMethodRef = null;
        classLoaderRef = null;
        lingApplicationContextRef = null;
        // 彻底擦除签名信息，防止持有 ClassLoader 中的 String (常量池引用)
        targetBeanName = null;
        targetClassName = null;
        targetMethodName = null;
        targetMethodParameterTypeNames = null;
        springDocBeanName = null;
        opSummary = null;
        opDescription = null;
        opTags = null;
        requestMappingInfo = null;
    }

    public boolean matchesHandler(Object bean, Method method) {
        String expectedMethodName = resolveStoredTargetMethodName();
        if (method == null || expectedMethodName == null || !expectedMethodName.equals(method.getName())) {
            return false;
        }
        if (!matchesParameterTypes(method.getParameterTypes(), resolveStoredTargetMethodParameterTypeNames())) {
            return false;
        }

        if (bean instanceof String) {
            return Objects.equals(bean, springDocBeanName);
        }

        String expectedClassName = resolveStoredTargetClassName();
        if (bean != null && expectedClassName != null) {
            Class<?> userClass = AopUtils.getTargetClass(bean);
            if (userClass == null || !expectedClassName.equals(userClass.getName())) {
                return false;
            }
            ClassLoader expectedLoader = getClassLoader();
            return expectedLoader == null || userClass.getClassLoader() == expectedLoader;
        }
        return false;
    }

    private Class<?> resolveUserClass(Object bean) {
        return bean != null ? AopUtils.getTargetClass(bean) : null;
    }

    public boolean hasSameTargetSignature(WebInterfaceMetadata other) {
        if (other == null) {
            return false;
        }
        return Objects.equals(resolveStoredTargetClassName(), other.resolveStoredTargetClassName())
                && Objects.equals(resolveStoredTargetMethodName(), other.resolveStoredTargetMethodName())
                && Arrays.equals(resolveStoredTargetMethodParameterTypeNames(),
                other.resolveStoredTargetMethodParameterTypeNames());
    }

    public String buildRouteKey() {
        String base = httpMethod + "#" + urlPattern;
        String signature = buildConditionSignature();
        return signature.isEmpty() ? base : base + "|" + signature;
    }

    public boolean matchesRequest(Object request) {
        if (request == null) {
            return true;
        }
        return matchesParams(request)
                && matchesHeaders(request)
                && matchesConsumes(request)
                && matchesProduces(request);
    }

    public int compareRequestSpecificity(WebInterfaceMetadata other, Object request) {
        if (other == null) {
            return 0;
        }
        int thisWeight = requestConditionWeight();
        int otherWeight = other.requestConditionWeight();
        return Integer.compare(otherWeight, thisWeight);
    }

    public WebInterfaceMetadata snapshotForRequest() {
        return WebInterfaceMetadata.builder()
                .lingId(lingId)
                .targetBean(getTargetBean())
                .targetMethod(getTargetMethod())
                .classLoader(getClassLoader())
                .lingApplicationContext(getLingApplicationContext())
                .targetBeanName(targetBeanName)
                .targetClassName(resolveStoredTargetClassName())
                .targetMethodName(resolveStoredTargetMethodName())
                .targetMethodParameterTypeNames(copyParameterTypeNames(resolveStoredTargetMethodParameterTypeNames()))
                .springDocBeanName(springDocBeanName)
                .urlPattern(urlPattern)
                .httpMethod(httpMethod)
                .params(copyStringArray(params))
                .headers(copyStringArray(headers))
                .consumes(copyStringArray(consumes))
                .produces(copyStringArray(produces))
                .requiredPermission(requiredPermission)
                .shouldAudit(shouldAudit)
                .auditAction(auditAction)
                .version(version)
                .opSummary(opSummary)
                .opDescription(opDescription)
                .opTags(opTags != null ? Arrays.copyOf(opTags, opTags.length) : null)
                .requestMappingInfo(requestMappingInfo)
                .build();
    }

    private Class<?> resolveTargetClass() {
        Object bean = getTargetBean();
        if (bean != null) {
            return AopUtils.getTargetClass(bean);
        }
        String className = resolveStoredTargetClassName();
        if (className == null || className.isEmpty()) {
            return null;
        }
        ClassLoader loader = getClassLoader();
        if (loader == null) {
            return null;
        }
        try {
            return ClassUtils.forName(className, loader);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    private Class<?>[] resolveParameterTypes(ClassLoader loader) {
        if (targetMethodParameterTypeNames == null) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[targetMethodParameterTypeNames.length];
        for (int i = 0; i < targetMethodParameterTypeNames.length; i++) {
            String name = targetMethodParameterTypeNames[i];
            if (PRIMITIVE_TYPES.containsKey(name)) {
                types[i] = PRIMITIVE_TYPES.get(name);
                continue;
            }
            if (loader == null) {
                return null;
            }
            try {
                types[i] = ClassUtils.forName(name, loader);
            } catch (ClassNotFoundException ex) {
                return null;
            }
        }
        return types;
    }

    private boolean matchesParameterTypes(Class<?>[] actualTypes, String[] expectedTypeNames) {
        if (expectedTypeNames == null) {
            return actualTypes == null || actualTypes.length == 0;
        }
        if (actualTypes == null || actualTypes.length != expectedTypeNames.length) {
            return false;
        }
        for (int i = 0; i < actualTypes.length; i++) {
            if (!actualTypes[i].getName().equals(expectedTypeNames[i])) {
                return false;
            }
        }
        return true;
    }

    private String[] resolveParameterTypeNames(Method method) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        String[] names = new String[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            names[i] = parameterTypes[i].getName();
        }
        return names;
    }

    private String[] copyParameterTypeNames(String[] parameterTypeNames) {
        if (parameterTypeNames == null) {
            return null;
        }
        return Arrays.copyOf(parameterTypeNames, parameterTypeNames.length);
    }

    private String[] copyStringArray(String[] source) {
        if (source == null) {
            return null;
        }
        return Arrays.copyOf(source, source.length);
    }

    private String buildConditionSignature() {
        StringBuilder signature = new StringBuilder();
        appendCondition(signature, "params", params);
        appendCondition(signature, "headers", headers);
        appendCondition(signature, "consumes", consumes);
        appendCondition(signature, "produces", produces);
        return signature.toString();
    }

    private void appendCondition(StringBuilder signature, String label, String[] values) {
        if (values == null || values.length == 0) {
            return;
        }
        if (signature.length() > 0) {
            signature.append(';');
        }
        signature.append(label).append('=').append(Arrays.toString(values));
    }

    private boolean matchesParams(Object request) {
        if (params == null || params.length == 0) {
            return true;
        }
        for (String expression : params) {
            if (!matchesExpression(expression, name -> readRequestParameter(request, name))) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesHeaders(Object request) {
        if (headers == null || headers.length == 0) {
            return true;
        }
        for (String expression : headers) {
            if (!matchesExpression(expression, name -> readRequestHeader(request, name))) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesConsumes(Object request) {
        if (consumes == null || consumes.length == 0) {
            return true;
        }
        String contentType = readContentType(request);
        if (contentType == null || contentType.isEmpty()) {
            return false;
        }
        try {
            MediaType actual = MediaType.parseMediaType(contentType);
            for (String expression : consumes) {
                if (expression == null || expression.trim().isEmpty()) {
                    continue;
                }
                MediaType expected = MediaType.parseMediaType(expression);
                if (expected.includes(actual)) {
                    return true;
                }
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return false;
    }

    private boolean matchesProduces(Object request) {
        if (produces == null || produces.length == 0) {
            return true;
        }
        String accept = readRequestHeader(request, "Accept");
        List<MediaType> acceptedTypes;
        try {
            acceptedTypes = (accept == null || accept.trim().isEmpty())
                    ? Arrays.asList(MediaType.ALL)
                    : MediaType.parseMediaTypes(accept);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        for (String expression : produces) {
            if (expression == null || expression.trim().isEmpty()) {
                continue;
            }
            MediaType expected;
            try {
                expected = MediaType.parseMediaType(expression);
            } catch (IllegalArgumentException ignored) {
                return false;
            }
            for (MediaType acceptedType : acceptedTypes) {
                if (acceptedType.includes(expected) || expected.includes(acceptedType)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean matchesExpression(String expression, StringValueLookup lookup) {
        if (expression == null || expression.trim().isEmpty()) {
            return true;
        }
        String trimmed = expression.trim();
        int notEqualsIndex = trimmed.indexOf("!=");
        if (notEqualsIndex >= 0) {
            String name = trimmed.substring(0, notEqualsIndex);
            String value = trimmed.substring(notEqualsIndex + 2);
            String actual = lookup.get(name);
            return actual != null && !actual.equals(value);
        }
        int equalsIndex = trimmed.indexOf('=');
        if (equalsIndex >= 0) {
            String name = trimmed.substring(0, equalsIndex);
            String value = trimmed.substring(equalsIndex + 1);
            String actual = lookup.get(name);
            return actual != null && actual.equals(value);
        }
        if (trimmed.startsWith("!")) {
            return lookup.get(trimmed.substring(1)) == null;
        }
        return lookup.get(trimmed) != null;
    }

    private String readRequestParameter(Object request, String name) {
        return readRequestString(request, "getParameter", name);
    }

    private String readRequestHeader(Object request, String name) {
        return readRequestString(request, "getHeader", name);
    }

    private String readContentType(Object request) {
        Object value = invokeNoArgMethod(request, "getContentType");
        return value instanceof String ? (String) value : null;
    }

    private String readRequestString(Object request, String methodName, String name) {
        if (request == null || methodName == null || name == null) {
            return null;
        }
        Method method = ReflectionUtils.findMethod(request.getClass(), methodName, String.class);
        if (method == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(method);
        Object value = ReflectionUtils.invokeMethod(method, request, name);
        return value instanceof String ? (String) value : null;
    }

    private Object invokeNoArgMethod(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        Method method = ReflectionUtils.findMethod(target.getClass(), methodName);
        if (method == null) {
            return null;
        }
        ReflectionUtils.makeAccessible(method);
        return ReflectionUtils.invokeMethod(method, target);
    }

    private int requestConditionWeight() {
        return conditionWeight(params) * 8
                + conditionWeight(headers) * 4
                + conditionWeight(consumes) * 2
                + conditionWeight(produces);
    }

    private int conditionWeight(String[] expressions) {
        return expressions == null ? 0 : expressions.length;
    }

    private interface StringValueLookup {
        String get(String name);
    }

    private String resolveStoredTargetClassName() {
        if (targetClassName != null && !targetClassName.isEmpty()) {
            return targetClassName;
        }
        Method method = targetMethod != null ? targetMethod : (targetMethodRef != null ? targetMethodRef.get() : null);
        if (method != null) {
            return method.getDeclaringClass().getName();
        }
        Object bean = getTargetBean();
        if (bean == null) {
            return null;
        }
        Class<?> userClass = AopUtils.getTargetClass(bean);
        return userClass != null ? userClass.getName() : null;
    }

    private String resolveStoredTargetMethodName() {
        if (targetMethodName != null && !targetMethodName.isEmpty()) {
            return targetMethodName;
        }
        Method method = targetMethod != null ? targetMethod : (targetMethodRef != null ? targetMethodRef.get() : null);
        return method != null ? method.getName() : null;
    }

    private String[] resolveStoredTargetMethodParameterTypeNames() {
        if (targetMethodParameterTypeNames != null) {
            return targetMethodParameterTypeNames;
        }
        Method method = targetMethod != null ? targetMethod : (targetMethodRef != null ? targetMethodRef.get() : null);
        return method != null ? resolveParameterTypeNames(method) : new String[0];
    }
}
