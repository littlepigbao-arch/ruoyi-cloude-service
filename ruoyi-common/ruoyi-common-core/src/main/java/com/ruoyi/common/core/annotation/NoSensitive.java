package com.ruoyi.common.core.annotation;

import java.lang.annotation.*;

/**
 * @Description “关闭” 数据脱敏
 * @Author AhYi
 * @Date 2025-07-07 10:23
 */

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NoSensitive {
}
