package com.lingframe.runtime.adapter;

import com.lingframe.api.config.LingDefinition;
import com.lingframe.api.exception.InvalidArgumentException;
import com.lingframe.api.ling.Ling;
import com.lingframe.core.exception.LingInstallException;
import com.lingframe.core.spi.LingContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link NativeContainerFactory} 单元测试。
 * <p>
 * 覆盖参数校验、主类加载、异常包装及正常创建路径。
 */
@DisplayName("NativeContainerFactory 测试")
class NativeContainerFactoryTest {

    private final NativeContainerFactory factory = new NativeContainerFactory();

    @Test
    @DisplayName("definition 为 null 应抛 InvalidArgumentException")
    void shouldThrowWhenDefinitionNull() {
        InvalidArgumentException ex = assertThrows(InvalidArgumentException.class,
                () -> factory.create(null, new File("."), getClass().getClassLoader()));
        assertEquals("definition", ex.getParamName());
    }

    @Test
    @DisplayName("lingId 为 null 应抛 InvalidArgumentException")
    void shouldThrowWhenLingIdNull() {
        LingDefinition def = new LingDefinition();
        // id 默认为 null
        InvalidArgumentException ex = assertThrows(InvalidArgumentException.class,
                () -> factory.create(def, new File("."), getClass().getClassLoader()));
        assertEquals("lingId", ex.getParamName());
    }

    @Test
    @DisplayName("lingId 为空字符串应抛 InvalidArgumentException")
    void shouldThrowWhenLingIdEmpty() {
        LingDefinition def = new LingDefinition();
        def.setId("");
        InvalidArgumentException ex = assertThrows(InvalidArgumentException.class,
                () -> factory.create(def, new File("."), getClass().getClassLoader()));
        assertEquals("lingId", ex.getParamName());
    }

    @Test
    @DisplayName("lingId 为纯空白应抛 InvalidArgumentException")
    void shouldThrowWhenLingIdBlank() {
        LingDefinition def = new LingDefinition();
        def.setId("   ");
        InvalidArgumentException ex = assertThrows(InvalidArgumentException.class,
                () -> factory.create(def, new File("."), getClass().getClassLoader()));
        assertEquals("lingId", ex.getParamName());
    }

    @Test
    @DisplayName("mainClass 为 null 应抛 InvalidArgumentException")
    void shouldThrowWhenMainClassNull() {
        LingDefinition def = new LingDefinition();
        def.setId("test-ling");
        // mainClass 默认为 null
        InvalidArgumentException ex = assertThrows(InvalidArgumentException.class,
                () -> factory.create(def, new File("."), getClass().getClassLoader()));
        assertEquals("mainClass", ex.getParamName());
    }

    @Test
    @DisplayName("mainClass 为空字符串应抛 InvalidArgumentException")
    void shouldThrowWhenMainClassEmpty() {
        LingDefinition def = new LingDefinition();
        def.setId("test-ling");
        def.setMainClass("");
        InvalidArgumentException ex = assertThrows(InvalidArgumentException.class,
                () -> factory.create(def, new File("."), getClass().getClassLoader()));
        assertEquals("mainClass", ex.getParamName());
    }

    @Test
    @DisplayName("mainClass 为纯空白应抛 InvalidArgumentException")
    void shouldThrowWhenMainClassBlank() {
        LingDefinition def = new LingDefinition();
        def.setId("test-ling");
        def.setMainClass("   ");
        InvalidArgumentException ex = assertThrows(InvalidArgumentException.class,
                () -> factory.create(def, new File("."), getClass().getClassLoader()));
        assertEquals("mainClass", ex.getParamName());
    }

    @Test
    @DisplayName("mainClass 不存在应抛 LingInstallException（cause 为 ClassNotFoundException）")
    void shouldThrowWhenMainClassNotFound() {
        LingDefinition def = new LingDefinition();
        def.setId("test-ling");
        def.setMainClass("com.nonexistent.FakeClass");
        LingInstallException ex = assertThrows(LingInstallException.class,
                () -> factory.create(def, new File("."), getClass().getClassLoader()));
        assertEquals("test-ling", ex.getLingId());
        assertTrue(ex.getCause() instanceof ClassNotFoundException);
    }

    @Test
    @DisplayName("mainClass 不实现 Ling 接口应抛 LingInstallException")
    void shouldThrowWhenMainClassNotImplementLing() {
        LingDefinition def = new LingDefinition();
        def.setId("test-ling");
        def.setMainClass(String.class.getName());
        LingInstallException ex = assertThrows(LingInstallException.class,
                () -> factory.create(def, new File("."), getClass().getClassLoader()));
        assertEquals("test-ling", ex.getLingId());
        assertNotNull(ex.getCause());
    }

    @Test
    @DisplayName("正常入参应创建 NativeLingContainer 实例")
    void shouldCreateContainerWhenValidInput() {
        LingDefinition def = new LingDefinition();
        def.setId("test-ling");
        def.setMainClass(ValidLing.class.getName());
        LingContainer container = factory.create(def, new File("."), getClass().getClassLoader());
        assertNotNull(container);
        assertTrue(container instanceof NativeLingContainer);
        // 未 start 前不活跃
        assertFalse(container.isActive());
    }

    @Test
    @DisplayName("正常入参创建的容器应返回正确的 ClassLoader")
    void shouldCreateContainerWithCorrectClassLoader() {
        LingDefinition def = new LingDefinition();
        def.setId("test-ling");
        def.setMainClass(ValidLing.class.getName());
        ClassLoader cl = getClass().getClassLoader();
        LingContainer container = factory.create(def, new File("."), cl);
        assertSame(cl, container.getClassLoader());
    }

    /** 测试用 Ling 实现（默认空实现即可） */
    public static class ValidLing implements Ling {
    }
}
