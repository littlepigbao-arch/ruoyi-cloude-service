package com.ruoyi.system;

import com.ruoyi.common.security.interceptor.NoSensitiveInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @Description WebMvcConfig
 * @Author AhYi
 * @Date 2025-07-07 10:46
 */

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NoSensitiveInterceptor())
                .addPathPatterns("/**")
                .order(-1);
    }
}
