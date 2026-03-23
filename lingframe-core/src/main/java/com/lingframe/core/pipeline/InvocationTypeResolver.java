package com.lingframe.core.pipeline;

/**
 * 调用类型解析工具。
 */
final class InvocationTypeResolver {

    private InvocationTypeResolver() {
    }

    static Class<?>[] resolveTypes(String[] typeNames, ClassLoader classLoader) throws ClassNotFoundException {
        if (typeNames == null || typeNames.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            types[i] = loadClass(typeNames[i], classLoader);
        }
        return types;
    }

    static Class<?> loadClass(String typeName, ClassLoader classLoader) throws ClassNotFoundException {
        switch (typeName) {
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "double":
                return double.class;
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "short":
                return short.class;
            case "float":
                return float.class;
            case "char":
                return char.class;
            default:
                return Class.forName(typeName, false, classLoader);
        }
    }
}
