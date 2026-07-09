package com.lingframe.starter.web.adapter;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * 测试专用 Javax Servlet 可重复读输入流适配器。
 * <p>
 * {@code lingframe-spring-boot-starter} 的 {@code LingRepeatableReadFilter} 在运行时
 * 通过 {@code Class.forName} 加载本适配器以构造可重复读的 {@link ServletInputStream}。
 * 正式环境下该类由 {@code lingframe-spring-boot2-starter} 提供（与 javax.servlet 同模块），
 * 本测试源仅用于在 starter 模块单测中补齐该类的可见性，使 {@code getInputStream()} 回退逻辑
 * 不至于因 {@code ByteArrayInputStream} 无法转换为 {@code ServletInputStream} 而失败。
 */
public class JavaxRepeatableReadInputStream extends ServletInputStream {

    private final ByteArrayInputStream bais;

    public JavaxRepeatableReadInputStream(byte[] body) {
        this.bais = new ByteArrayInputStream(body);
    }

    @Override
    public boolean isFinished() {
        return bais.available() == 0;
    }

    @Override
    public boolean isReady() {
        return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
        // 测试实现：不做任何处理
    }

    @Override
    public int read() throws IOException {
        return bais.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return bais.read(b, off, len);
    }

    @Override
    public int available() throws IOException {
        return bais.available();
    }

    @Override
    public void close() throws IOException {
        bais.close();
    }
}
