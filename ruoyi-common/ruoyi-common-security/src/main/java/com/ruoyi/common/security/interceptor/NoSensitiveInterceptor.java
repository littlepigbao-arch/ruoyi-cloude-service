package com.ruoyi.common.security.interceptor;

import com.ruoyi.common.core.annotation.NoSensitive;
import com.ruoyi.common.core.context.SensitiveContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @Description @NoSensitive 注解的请求拦截器，主要用于对请求的注解，在请求的整个生命周期内有效
 * @Author AhYi
 * @Date 2025-07-07 10:35
 */

public class NoSensitiveInterceptor implements HandlerInterceptor {
    private static final String SENSITIVE_INTERCEPTOR_APPLIED = "SENSITIVE_INTERCEPTOR_APPLIED";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (handler instanceof HandlerMethod) {
            HandlerMethod handlerMethod = (HandlerMethod) handler;
            NoSensitive noSensitive = handlerMethod.getMethodAnnotation(NoSensitive.class);
            if (noSensitive == null) {
                noSensitive = handlerMethod.getBeanType().getAnnotation(NoSensitive.class);
            }
            if (noSensitive != null) {
                SensitiveContextHolder.enterNoSensitiveScope();
                request.setAttribute(SENSITIVE_INTERCEPTOR_APPLIED, Boolean.TRUE);
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        Object applied = request.getAttribute(SENSITIVE_INTERCEPTOR_APPLIED);
        if (applied != null && (Boolean) applied) {
            SensitiveContextHolder.exitNoSensitiveScope();
        }
    }
}
