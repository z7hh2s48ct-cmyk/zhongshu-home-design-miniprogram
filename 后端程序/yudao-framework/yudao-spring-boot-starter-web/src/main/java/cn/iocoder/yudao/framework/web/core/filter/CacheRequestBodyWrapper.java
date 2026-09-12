package cn.iocoder.yudao.framework.web.core.filter;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 *  Request Body 缓存 Wrapper
 *
 * @author 芋道源码
 */
public class CacheRequestBodyWrapper extends HttpServletRequestWrapper {

    /**
     * 缓存的内容
     */
    private final byte[] body;

    public static class BodyTooLargeException extends IOException { }

    public CacheRequestBodyWrapper(HttpServletRequest request) throws IOException {
        this(request, 1024 * 1024);
    }

    public CacheRequestBodyWrapper(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        if (maxBytes <= 0 || maxBytes == Integer.MAX_VALUE) throw new IllegalArgumentException("invalid body limit");
        if (request.getContentLengthLong() > maxBytes) throw new BodyTooLargeException();
        // Also bounds chunked requests and untrusted Content-Length before any full-body cache.
        body = request.getInputStream().readNBytes(maxBytes + 1);
        if (body.length > maxBytes) throw new BodyTooLargeException();
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(this.getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }

    @Override
    public ServletInputStream getInputStream() {
        final ByteArrayInputStream inputStream = new ByteArrayInputStream(body);
        // 返回 ServletInputStream
        return new ServletInputStream() {

            @Override
            public int read() {
                return inputStream.read();
            }

            @Override
            public boolean isFinished() {
                return inputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener readListener) {}

            @Override
            public int available() {
                return inputStream.available();
            }

        };
    }

}
