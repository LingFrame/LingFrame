package com.lingframe.api.config;

import com.lingframe.api.exception.InvalidArgumentException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("LingDefinition 单元测试")
class LingDefinitionTest {

    @Test
    @DisplayName("测试 validate 校验机制")
    void testValidate() {
        LingDefinition def = new LingDefinition();
        
        // id 缺失
        assertThrows(InvalidArgumentException.class, def::validate);
        
        def.setId("  ");
        assertThrows(InvalidArgumentException.class, def::validate);
        
        def.setId("test-ling");
        // version 缺失
        assertThrows(InvalidArgumentException.class, def::validate);
        
        def.setVersion(" ");
        assertThrows(InvalidArgumentException.class, def::validate);
        
        def.setVersion("1.0.0");
        // id 和 version 都齐全，校验通过
        def.validate();
    }

    @Test
    @DisplayName("测试 copy 深拷贝")
    void testCopy() {
        LingDefinition origin = new LingDefinition();
        origin.setId("ling-id");
        origin.setVersion("1.0.0");
        origin.setProvider("developer");
        origin.setDescription("my description");
        origin.setMainClass("com.example.Main");
        origin.getProperties().put("key", "val");
        
        LingDefinition copied = origin.copy();
        
        assertEquals(origin.getId(), copied.getId());
        assertEquals(origin.getVersion(), copied.getVersion());
        assertEquals(origin.getProvider(), copied.getProvider());
        assertEquals(origin.getDescription(), copied.getDescription());
        assertEquals(origin.getMainClass(), copied.getMainClass());
        assertEquals(origin.getProperties().get("key"), copied.getProperties().get("key"));
        
        // 验证 properties 是复制的而不是同一个引用
        copied.getProperties().put("key", "new-val");
        assertEquals("val", origin.getProperties().get("key"));
        
        // 验证 toString
        assertEquals("LingDefinition{id='ling-id', version='1.0.0'}", origin.toString());
    }
}