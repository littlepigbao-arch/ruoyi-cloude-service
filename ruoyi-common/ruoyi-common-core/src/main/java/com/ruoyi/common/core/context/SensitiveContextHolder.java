package com.ruoyi.common.core.context;

/**
 * @Description Sensitive 数据脱敏上下文管理，存储当前线程是否需要脱敏
 * @Author AhYi
 * @Date 2025-07-07 10:27
 */


public class SensitiveContextHolder {
    private static final ThreadLocal<Integer> COUNTER = new ThreadLocal<>();

    public static void enterNoSensitiveScope() {
        Integer count = COUNTER.get();
        if (count == null) {
            count = 0;
        }
        COUNTER.set(count + 1);
    }

    public static void exitNoSensitiveScope() {
        Integer count = COUNTER.get();
        if (count != null) {
            if (count <= 1) {
                COUNTER.remove();
            } else {
                COUNTER.set(count - 1);
            }
        }
    }

    public static boolean isNoSensitiveScope() {
        Integer count = COUNTER.get();
        return count != null && count > 0;
    }
}
