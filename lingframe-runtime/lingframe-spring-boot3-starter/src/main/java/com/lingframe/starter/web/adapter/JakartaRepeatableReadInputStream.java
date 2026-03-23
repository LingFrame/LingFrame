package com.lingframe.starter.web.adapter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Jakarta Servlet 专用可重复读输入流。
 */
public class JakartaRepeatableReadInputStream extends ServletInputStream {

    private final ByteArrayInputStream bais;

    public JakartaRepeatableReadInputStream(byte[] body) {
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
