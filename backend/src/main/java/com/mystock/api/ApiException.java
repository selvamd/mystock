package com.mystock.api;

/** An error with a protocol error code (BAD_REQUEST, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, UPSTREAM, INTERNAL). */
public class ApiException extends RuntimeException {
    public final String code;
    public ApiException(String code, String msg) { super(msg); this.code = code; }

    public static ApiException badRequest(String m) { return new ApiException("BAD_REQUEST", m); }
    public static ApiException notFound(String m) { return new ApiException("NOT_FOUND", m); }
    public static ApiException forbidden(String m) { return new ApiException("FORBIDDEN", m); }
    public static ApiException upstream(String m) { return new ApiException("UPSTREAM", m); }
}
