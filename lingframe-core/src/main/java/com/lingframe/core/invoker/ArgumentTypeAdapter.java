package com.lingframe.core.invoker;

import lombok.extern.slf4j.Slf4j;

import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产级入参类型自适应绑定引擎。
 *
 * <p>核心解决协议层（JSON/Map）与灵元实现层（强类型 JavaBean/DTO/Record）之间的阻抗失配。
 *
 * <p>安全与防泄漏原则：
 * <ul>
 *   <li>1. <b>零灵核强引用</b>：所有反射与类型装配均为调用栈局部变量，严禁全局静态缓存灵元 Class，彻底杜绝类加载器泄漏；
 *       唯一的持久缓存是引擎 A 的 {@link #MAPPER_CACHE}，它以 ClassLoader 为键缓存 ObjectMapper，
 *       且灵元卸载时由 LingUnloadCoordinator 显式 {@link #evict(ClassLoader)}——有配套清理，不滞留；</li>
 *   <li>2. <b>隔离环境优先</b>：优先使用灵元自身 ClassLoader 上下文下的 Jackson 进行精准反序列化；</li>
 *   <li>3. <b>纯 JDK 递归兜底</b>：环境无 Jackson 时，由原生全功能 BeanPopulator 支持复杂嵌套、泛型 List、枚举与日期时间装配；</li>
 *   <li>4. <b>零回归与 Fast-Path</b>：类型已兼容时走快速通道直接透传，性能零损耗。</li>
 * </ul>
 */
@Slf4j
public class ArgumentTypeAdapter {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_DATE_TIME;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_DATE;

    /**
     * 按目标 ClassLoader 缓存跨 ClassLoader Jackson 转换所用的 ObjectMapper。
     * <p>
     * 热点优化：引擎 A 每次调用都反射新建 ObjectMapper 会触达模块发现与内省缓存构建（5–10ms），
     * 高 QPS 下单参数 Map→DTO 转换会成为挂钩点。缓存以 ClassLoader 为键，实例由灵元自身加载，
     * 灵元卸载时经 {@link #evict(ClassLoader)} 清理对应条目，避免对灵元 Class / ClassLoader 的
     * 强引用滞留（与类文档"禁止全局静态缓存灵元 Class"原则不冲突——该原则针对无配套清理的持久缓存）。
     */
    private static final ConcurrentHashMap<ClassLoader, Object> MAPPER_CACHE = new ConcurrentHashMap<ClassLoader, Object>();

    /**
     * 对传入的方法反射参数数组进行自适应类型转换。
     *
     * @param method 目标调用方法
     * @param args 原始入参数组
     * @param targetClassLoader 目标灵元类加载器
     * @return 转换后的入参数组
     */
    public static Object[] adapt(Method method, Object[] args, ClassLoader targetClassLoader) {
        if (args == null || args.length == 0 || method == null) {
            return args;
        }
        Class<?>[] parameterTypes = method.getParameterTypes();
        Type[] genericParameterTypes = method.getGenericParameterTypes();
        Object[] adapted = new Object[args.length];

        for (int i = 0; i < args.length; i++) {
            if (i >= parameterTypes.length) {
                adapted[i] = args[i];
                continue;
            }
            Class<?> expectedType = parameterTypes[i];
            Type genericType = genericParameterTypes[i];
            Object actualArg = args[i];

            // 1. 快速通路：如果类型已经完全兼容且不需要集合泛型元素转换，直接零开销放行
            if (isCompatible(expectedType, actualArg) && !needsCollectionElementConversion(genericType, actualArg)) {
                adapted[i] = actualArg;
                continue;
            }

            // 2. 自适应转换
            adapted[i] = tryConvert(actualArg, expectedType, genericType, targetClassLoader);
        }
        return adapted;
    }

    /**
     * 对 MethodType 参数（用于 MethodHandle 调用）进行自适应类型转换。
     *
     * @param methodType 目标 MethodType
     * @param argsWithTarget 包含目标 Bean 以及业务入参的完整参数数组 (argsWithTarget[0] 为 targetBean)
     * @param targetClassLoader 目标灵元类加载器
     * @return 转换后的入参数组
     */
    public static Object[] adapt(MethodType methodType, Object[] argsWithTarget, ClassLoader targetClassLoader) {
        if (argsWithTarget == null || argsWithTarget.length == 0 || methodType == null) {
            return argsWithTarget;
        }
        Class<?>[] parameterTypes = methodType.parameterArray();
        Object[] adapted = new Object[argsWithTarget.length];

        for (int i = 0; i < argsWithTarget.length; i++) {
            if (i >= parameterTypes.length) {
                adapted[i] = argsWithTarget[i];
                continue;
            }
            Class<?> expectedType = parameterTypes[i];
            Object actualArg = argsWithTarget[i];

            // 快速通路：targetBean 或已类型兼容直接放行
            if (isCompatible(expectedType, actualArg)) {
                adapted[i] = actualArg;
                continue;
            }

            // 自适应转换
            adapted[i] = tryConvert(actualArg, expectedType, expectedType, targetClassLoader);
        }
        return adapted;
    }

    /**
     * 核心转换调度：双引擎流水线
     */
    public static Object tryConvert(Object source, Class<?> targetType, Type genericType, ClassLoader targetClassLoader) {
        if (source == null) {
            return null;
        }
        if (targetType == null || Object.class.equals(targetType)) {
            return source;
        }
        if (isCompatible(targetType, source) && !needsCollectionElementConversion(genericType, source)) {
            return source;
        }

        // 1. 标量类型与常用类型转换（String / 基础类型 / 日期 / BigDecimal / Enum 等）
        if (isScalarOrStandardType(targetType)) {
            Object scalar = convertScalar(source, targetType);
            if (scalar != null) {
                return scalar;
            }
        }

        // 2. 集合类型转换（List, Set, Collection）
        if (Collection.class.isAssignableFrom(targetType) && (source instanceof Collection || source.getClass().isArray())) {
            return convertCollection(source, targetType, genericType, targetClassLoader);
        }

        // 3. 数组类型转换
        if (targetType.isArray() && (source instanceof Collection || source.getClass().isArray())) {
            return convertArray(source, targetType, targetClassLoader);
        }

        // 4. Map -> 强类型 POJO / DTO
        if (source instanceof Map) {
            // 引擎 A：优先尝试灵元类加载器中的 Jackson 进行深度转换
            Object jacksonResult = tryConvertViaIsolatedJackson(source, targetType, targetClassLoader);
            if (jacksonResult != null) {
                return jacksonResult;
            }

            // 引擎 B：纯 JDK 原生 BeanPopulator 递归装配
            return populatePojoFromMap((Map<?, ?>) source, targetType, targetClassLoader);
        }

        // 兜底返回原值
        return source;
    }

    /**
     * 引擎 A：在灵元自身 ClassLoader 中反射使用 Jackson ObjectMapper。
     * <p>
     * ObjectMapper 按 ClassLoader 缓存复用（{@link #MAPPER_CACHE}），避免每参数反射新建；
     * 实例由灵元类加载器加载，灵元卸载时经 {@link #evict(ClassLoader)} 清出，不滞留灵核。
     */
    private static Object tryConvertViaIsolatedJackson(Object source, Class<?> targetType, ClassLoader targetClassLoader) {
        if (targetClassLoader == null) {
            return null;
        }
        Object mapper = MAPPER_CACHE.computeIfAbsent(targetClassLoader, cl -> {
            try {
                Class<?> mapperClass = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, cl);
                return mapperClass.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                // 目标环境无 Jackson：设计内降级，返回 null 交给引擎 B；ConcurrentHashMap 不缓存 null
                return null;
            }
        });
        if (mapper == null) {
            return null;
        }
        try {
            Method convertMethod = mapper.getClass().getMethod("convertValue", Object.class, Class.class);
            return convertMethod.invoke(mapper, source, targetType);
        } catch (NoClassDefFoundError e) {
            // 目标环境 Jackson 存在但待链接依赖缺失：与「无 Jackson」同属设计内降级，走引擎 B
            return null;
        } catch (Exception e) {
            // 只捕 Exception：转换失败仅影响单个参数，记录并降级；OOM/StackOverflow 等 Error 上抛，
            // 避免被静默吞掉掩盖致命错误
            log.warn("Isolated Jackson Map->{} conversion failed for CL {}, fallback to native populators: {}",
                    targetType.getName(), targetClassLoader, e.toString());
            return null;
        }
    }

    /**
     * 灵元卸载时清掉其 ClassLoader 对应的 ObjectMapper 缓存条目，
     * 释放对灵元类 / ClassLoader 的强引用，防止滞留导致的回收延迟。
     *
     * @param targetClassLoader 被卸载灵元的 ClassLoader
     */
    public static void evict(ClassLoader targetClassLoader) {
        if (targetClassLoader != null) {
            MAPPER_CACHE.remove(targetClassLoader);
        }
    }

    /**
     * 引擎 B：纯 JDK 原生递归 POJO 装配引擎
     */
    private static Object populatePojoFromMap(Map<?, ?> map, Class<?> targetType, ClassLoader targetClassLoader) {
        try {
            // 实例化目标对象
            Object instance = createInstance(targetType);
            if (instance == null) {
                return map;
            }

            // 扫描 Setter 方法（exact + normalized 双索引，见 PropertyIndex）
            PropertyIndex<Method> setterIndex = findSetters(targetType);
            // 扫描 Field
            PropertyIndex<Field> fieldIndex = findFields(targetType);

            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey()).trim();
                Object value = entry.getValue();

                String normalizedKey = normalizePropertyName(key);
                Method setter = findMatchingMethod(setterIndex, normalizedKey, key);
                if (setter != null) {
                    try {
                        setter.setAccessible(true);
                        Class<?> paramType = setter.getParameterTypes()[0];
                        Type genericParamType = setter.getGenericParameterTypes()[0];
                        Object convertedValue = tryConvert(value, paramType, genericParamType, targetClassLoader);
                        setter.invoke(instance, convertedValue);
                        continue;
                    } catch (Throwable ignored) {}
                }

                Field field = findMatchingField(fieldIndex, normalizedKey, key);
                if (field != null) {
                    try {
                        field.setAccessible(true);
                        Class<?> fieldType = field.getType();
                        Type genericFieldType = field.getGenericType();
                        Object convertedValue = tryConvert(value, fieldType, genericFieldType, targetClassLoader);
                        field.set(instance, convertedValue);
                    } catch (Throwable ignored) {}
                }
            }
            return instance;
        } catch (Throwable t) {
            log.debug("Native POJO population skipped for [{}]: {}", targetType.getName(), t.getMessage());
            return map;
        }
    }

    private static Object createInstance(Class<?> clazz) {
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            // 无无参构造时尝试取第一个构造函数
            for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                try {
                    ctor.setAccessible(true);
                    Object[] dummyArgs = new Object[ctor.getParameterCount()];
                    Class<?>[] pTypes = ctor.getParameterTypes();
                    for (int i = 0; i < pTypes.length; i++) {
                        dummyArgs[i] = defaultValueForPrimitive(pTypes[i]);
                    }
                    return ctor.newInstance(dummyArgs);
                } catch (Throwable ignored) {}
            }
            return null;
        }
    }

    /**
     * 属性索引：exact（保留分隔符、统一小写）与 normalized（去分隔符小写）分离。
     * <p>
     * ⚠️ 必须分两张 map：若把 rawLower 与 normalized 混入同一张 {@code putIfAbsent} map，
     * 无分隔符属性（如 {@code username}）的 rawLower 与其 normalized key 相同，
     * 会被先声明的分隔符变体（如 {@code user_name} → normalized "username"）抢占同一 key 槽，
     * 导致 {@code username} 属性完全丢失注册（声明顺序决定结果，非确定性错配）。
     */
    private static final class PropertyIndex<T> {
        /** 精确索引：key = 属性名统一小写（保留 _ / -），如 user_name / username / userName → username */
        final Map<String, T> exact = new HashMap<>();
        /** 宽松索引：key = 去分隔符小写，如 user_name / username / user-name → username */
        final Map<String, T> normalized = new HashMap<>();
    }

    private static PropertyIndex<Method> findSetters(Class<?> clazz) {
        PropertyIndex<Method> index = new PropertyIndex<>();
        Class<?> current = clazz;
        while (current != null && !Object.class.equals(current)) {
            for (Method m : current.getDeclaredMethods()) {
                if (m.getParameterCount() == 1 && m.getName().startsWith("set") && m.getName().length() > 3) {
                    if (Modifier.isStatic(m.getModifiers())) {
                        continue;
                    }
                    // exact：原始属性名统一小写（保留分隔符），user_name / userName 均可精确命中；
                    // normalized：去分隔符小写，作为跨命名风格（如 user-name）的宽松兜底。
                    // 两者分 map 存储，normalized 冲突（user_name vs username）不影响 exact 精确匹配。
                    String propName = m.getName().substring(3);
                    index.exact.put(propName.toLowerCase(), m);
                    index.normalized.putIfAbsent(normalizePropertyName(propName), m);
                }
            }
            current = current.getSuperclass();
        }
        return index;
    }

    private static PropertyIndex<Field> findFields(Class<?> clazz) {
        PropertyIndex<Field> index = new PropertyIndex<>();
        Class<?> current = clazz;
        while (current != null && !Object.class.equals(current)) {
            for (Field f : current.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) {
                    continue;
                }
                String fieldName = f.getName();
                index.exact.put(fieldName.toLowerCase(), f);
                index.normalized.putIfAbsent(normalizePropertyName(fieldName), f);
            }
            current = current.getSuperclass();
        }
        return index;
    }

    private static String normalizePropertyName(String name) {
        if (name == null) return "";
        return name.replace("_", "").replace("-", "").toLowerCase();
    }

    /**
     * 匹配 setter：精确索引优先（user_name 与 username 互不干扰），
     * 未命中时回退到归一化索引的宽松匹配（兼容 customer_id → customerId 等跨命名风格）。
     */
    private static Method findMatchingMethod(PropertyIndex<Method> index, String normalizedKey, String rawKey) {
        if (rawKey != null) {
            Method exact = index.exact.get(rawKey.toLowerCase());
            if (exact != null) {
                return exact;
            }
        }
        return index.normalized.get(normalizedKey);
    }

    private static Field findMatchingField(PropertyIndex<Field> index, String normalizedKey, String rawKey) {
        if (rawKey != null) {
            Field exact = index.exact.get(rawKey.toLowerCase());
            if (exact != null) {
                return exact;
            }
        }
        return index.normalized.get(normalizedKey);
    }

    private static boolean needsCollectionElementConversion(Type genericType, Object actualArg) {
        if (!(genericType instanceof ParameterizedType) || !(actualArg instanceof Collection)) {
            return false;
        }
        Collection<?> coll = (Collection<?>) actualArg;
        if (coll.isEmpty()) {
            return false;
        }
        Type[] actualArgs = ((ParameterizedType) genericType).getActualTypeArguments();
        if (actualArgs == null || actualArgs.length == 0) {
            return false;
        }
        Type itemGenericType = actualArgs[0];
        Class<?> itemClass = null;
        if (itemGenericType instanceof Class) {
            itemClass = (Class<?>) itemGenericType;
        } else if (itemGenericType instanceof ParameterizedType) {
            itemClass = (Class<?>) ((ParameterizedType) itemGenericType).getRawType();
        }
        if (itemClass == null || Object.class.equals(itemClass) || Map.class.isAssignableFrom(itemClass)) {
            return false;
        }
        for (Object item : coll) {
            if (item != null) {
                return !itemClass.isAssignableFrom(item.getClass());
            }
        }
        return false;
    }

    /**
     * 集合类型转换（处理 List<DTO> 等泛型场景）
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertCollection(Object source, Class<?> targetType, Type genericType, ClassLoader cl) {
        Collection<?> sourceList;
        if (source instanceof Collection) {
            sourceList = (Collection<?>) source;
        } else {
            int len = Array.getLength(source);
            List<Object> list = new ArrayList<>(len);
            for (int i = 0; i < len; i++) {
                list.add(Array.get(source, i));
            }
            sourceList = list;
        }

        Class<?> itemType = Object.class;
        Type itemGenericType = Object.class;
        if (genericType instanceof ParameterizedType) {
            Type[] actualArgs = ((ParameterizedType) genericType).getActualTypeArguments();
            if (actualArgs != null && actualArgs.length > 0) {
                itemGenericType = actualArgs[0];
                if (itemGenericType instanceof Class) {
                    itemType = (Class<?>) itemGenericType;
                } else if (itemGenericType instanceof ParameterizedType) {
                    itemType = (Class<?>) ((ParameterizedType) itemGenericType).getRawType();
                }
            }
        }

        Collection resultColl = Set.class.isAssignableFrom(targetType)
                ? new HashSet<>(sourceList.size())
                : new ArrayList<>(sourceList.size());

        for (Object item : sourceList) {
            resultColl.add(tryConvert(item, itemType, itemGenericType, cl));
        }
        return resultColl;
    }

    /**
     * 数组类型转换
     */
    private static Object convertArray(Object source, Class<?> targetType, ClassLoader cl) {
        Class<?> componentType = targetType.getComponentType();
        int size = (source instanceof Collection) ? ((Collection<?>) source).size() : Array.getLength(source);
        Object resultArray = Array.newInstance(componentType, size);

        int index = 0;
        if (source instanceof Collection) {
            for (Object item : (Collection<?>) source) {
                Array.set(resultArray, index++, tryConvert(item, componentType, componentType, cl));
            }
        } else {
            for (int i = 0; i < size; i++) {
                Array.set(resultArray, i, tryConvert(Array.get(source, i), componentType, componentType, cl));
            }
        }
        return resultArray;
    }

    /**
     * 标量与标准数据类型转换
     */
    private static Object convertScalar(Object source, Class<?> targetType) {
        if (source == null) return null;
        String strVal = String.valueOf(source).trim();

        if (targetType == String.class) {
            return strVal;
        }
        if (targetType == Integer.class || targetType == int.class) {
            if (source instanceof Number) return ((Number) source).intValue();
            return strVal.isEmpty() ? 0 : Integer.parseInt(strVal);
        }
        if (targetType == Long.class || targetType == long.class) {
            if (source instanceof Number) return ((Number) source).longValue();
            return strVal.isEmpty() ? 0L : Long.parseLong(strVal);
        }
        if (targetType == Double.class || targetType == double.class) {
            if (source instanceof Number) return ((Number) source).doubleValue();
            return strVal.isEmpty() ? 0.0 : Double.parseDouble(strVal);
        }
        if (targetType == Float.class || targetType == float.class) {
            if (source instanceof Number) return ((Number) source).floatValue();
            return strVal.isEmpty() ? 0.0f : Float.parseFloat(strVal);
        }
        if (targetType == Boolean.class || targetType == boolean.class) {
            if (source instanceof Boolean) return source;
            return "true".equalsIgnoreCase(strVal) || "1".equals(strVal) || "yes".equalsIgnoreCase(strVal);
        }
        if (targetType == Byte.class || targetType == byte.class) {
            if (source instanceof Number) return ((Number) source).byteValue();
            return strVal.isEmpty() ? (byte) 0 : Byte.parseByte(strVal);
        }
        if (targetType == Short.class || targetType == short.class) {
            if (source instanceof Number) return ((Number) source).shortValue();
            return strVal.isEmpty() ? (short) 0 : Short.parseShort(strVal);
        }
        if (targetType == Character.class || targetType == char.class) {
            return strVal.isEmpty() ? '\0' : strVal.charAt(0);
        }
        if (targetType == BigDecimal.class) {
            if (source instanceof BigDecimal) return source;
            if (source instanceof Number) {
                // 用字符串构造而非 doubleValue()：BigDecimal.valueOf(doubleValue()) 会先把
                // long/BigInteger 等整型降为 double，超 52 位精度即丢失（金额/计数类字段风险）；
                // toString 保留原对象精确数字表示
                return new BigDecimal(source.toString());
            }
            return strVal.isEmpty() ? BigDecimal.ZERO : new BigDecimal(strVal);
        }
        if (targetType == BigInteger.class) {
            if (source instanceof BigInteger) return source;
            if (source instanceof Number) return BigInteger.valueOf(((Number) source).longValue());
            return strVal.isEmpty() ? BigInteger.ZERO : new BigInteger(strVal);
        }
        if (targetType.isEnum()) {
            return convertEnum(strVal, targetType);
        }
        if (targetType == LocalDateTime.class) {
            return convertLocalDateTime(source, strVal);
        }
        if (targetType == LocalDate.class) {
            return convertLocalDate(source, strVal);
        }
        if (targetType == Date.class) {
            return convertDate(source, strVal);
        }
        if (targetType == Instant.class) {
            return convertInstant(source, strVal);
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertEnum(String strVal, Class<?> enumType) {
        if (strVal.isEmpty()) return null;
        for (Object constant : enumType.getEnumConstants()) {
            Enum<?> e = (Enum<?>) constant;
            if (e.name().equalsIgnoreCase(strVal)) {
                return e;
            }
        }
        try {
            int ordinal = Integer.parseInt(strVal);
            Object[] constants = enumType.getEnumConstants();
            if (ordinal >= 0 && ordinal < constants.length) {
                return constants[ordinal];
            }
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private static LocalDateTime convertLocalDateTime(Object source, String strVal) {
        if (source instanceof Number) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(((Number) source).longValue()), ZoneId.systemDefault());
        }
        try {
            if (strVal.contains("T")) {
                return LocalDateTime.parse(strVal, ISO_FORMATTER);
            }
            if (strVal.length() == 19) {
                return LocalDateTime.parse(strVal, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static LocalDate convertLocalDate(Object source, String strVal) {
        if (source instanceof Number) {
            return Instant.ofEpochMilli(((Number) source).longValue()).atZone(ZoneId.systemDefault()).toLocalDate();
        }
        try {
            return LocalDate.parse(strVal, DATE_FORMATTER);
        } catch (Throwable ignored) {}
        return null;
    }

    private static Date convertDate(Object source, String strVal) {
        if (source instanceof Number) {
            return new Date(((Number) source).longValue());
        }
        try {
            long millis = Long.parseLong(strVal);
            return new Date(millis);
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private static Instant convertInstant(Object source, String strVal) {
        if (source instanceof Number) {
            return Instant.ofEpochMilli(((Number) source).longValue());
        }
        try {
            return Instant.parse(strVal);
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isScalarOrStandardType(Class<?> clazz) {
        return clazz.isPrimitive()
                || clazz == String.class
                || Number.class.isAssignableFrom(clazz)
                || clazz == Boolean.class
                || clazz == Character.class
                || clazz.isEnum()
                || clazz == BigDecimal.class
                || clazz == BigInteger.class
                || clazz == LocalDateTime.class
                || clazz == LocalDate.class
                || clazz == Date.class
                || clazz == Instant.class;
    }

    public static boolean isCompatible(Class<?> expected, Object actual) {
        if (actual == null) {
            return !expected.isPrimitive();
        }
        return wrap(expected).isAssignableFrom(actual.getClass());
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        return type;
    }

    private static Object defaultValueForPrimitive(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return '\0';
        return null;
    }
}