package com.ruoyi.job.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;

public class ProgressFormatterUtil {
    public static String format(BigDecimal progress ) {
        if (progress == null) return "0";
        // 保留两位小数（四舍五入）
        BigDecimal scaled = progress.setScale(2, RoundingMode.HALF_UP);
        // 去除末尾零和小数点
        NumberFormat nf  = NumberFormat.getInstance();
        nf.setMaximumFractionDigits(2);
        nf.setMinimumFractionDigits(0);
        return nf.format(scaled) + "%";
    }
}
