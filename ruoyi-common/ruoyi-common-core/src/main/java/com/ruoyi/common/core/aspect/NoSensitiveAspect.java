package com.ruoyi.common.core.aspect;

import com.ruoyi.common.core.annotation.NoSensitive;
import com.ruoyi.common.core.context.SensitiveContextHolder;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * @Description @NoSensitive 注解切面，主要用户对方法的注解
 * @Author AhYi
 * @Date 2025-07-07 10:31
 */

@Aspect
@Component
public class NoSensitiveAspect {

    @Around("@annotation(noSensitive)")
    public Object around(ProceedingJoinPoint joinPoint, NoSensitive noSensitive) throws Throwable {
        try {
            SensitiveContextHolder.enterNoSensitiveScope();
            return joinPoint.proceed();
        } finally {
            SensitiveContextHolder.exitNoSensitiveScope();
        }
    }
}
