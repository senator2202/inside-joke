package com.insidejoke.web;

import jakarta.servlet.http.HttpServletRequest;

/** Client address after forwarded headers are applied by the servlet container. */
public final class ClientIpUtils {

    private ClientIpUtils() {}

    public static String of(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }
}
