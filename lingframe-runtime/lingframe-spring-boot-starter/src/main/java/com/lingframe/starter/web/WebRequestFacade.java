package com.lingframe.starter.web;

import java.security.Principal;

/**
 * 供共享 Web 治理逻辑使用的最小请求抽象。
 */
public interface WebRequestFacade {

    String getMethod();

    String getRequestURI();

    String getHeader(String name);

    Principal getUserPrincipal();

    String getRemoteUser();
}
