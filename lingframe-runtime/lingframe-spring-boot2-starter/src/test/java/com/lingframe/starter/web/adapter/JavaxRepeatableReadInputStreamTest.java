package com.lingframe.starter.web.adapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import javax.servlet.ReadListener;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JavaxRepeatableReadInputStream 单元测试")
class JavaxRepeatableReadInputStreamTest {

    @Test
    @DisplayName("测试可重复读输入流的基本读写与生命周期方法")
    void testInputStream() throws IOException {
        byte[] body = "Hello, LingFrame!".getBytes();
        JavaxRepeatableReadInputStream stream = new JavaxRepeatableReadInputStream(body);

        assertFalse(stream.isFinished());
        assertTrue(stream.isReady());
        assertEquals(body.length, stream.available());

        // 测试 read 单个字节
        int firstByte = stream.read();
        assertEquals('H', firstByte);
        assertEquals(body.length - 1, stream.available());

        // 测试 read 字节数组
        byte[] buffer = new byte[5];
        int readLen = stream.read(buffer, 0, 5);
        assertEquals(5, readLen);
        assertEquals("ello,", new String(buffer));

        // 读完所有数据
        byte[] remaining = new byte[20];
        int count = stream.read(remaining, 0, 20);
        assertEquals(11, count);
        assertTrue(stream.isFinished());
        assertEquals(0, stream.available());

        // 测试 setReadListener 无异常
        ReadListener listener = new ReadListener() {
            @Override
            public void onDataAvailable() throws IOException {}
            @Override
            public void onAllDataRead() throws IOException {}
            @Override
            public void onError(Throwable throwable) {}
        };
        assertDoesNotThrow(() -> stream.setReadListener(listener));

        // 测试 close 无异常
        assertDoesNotThrow(stream::close);
    }
}
