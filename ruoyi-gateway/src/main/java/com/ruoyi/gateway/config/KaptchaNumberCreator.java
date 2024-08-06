package com.ruoyi.gateway.config;

import com.google.code.kaptcha.text.impl.DefaultTextCreator;
import com.ujcms.commons.security.Secures;

/**
 * 验证码数字生成器
 *
 * @author hsdllcw
 */
public class KaptchaNumberCreator extends DefaultTextCreator {

    @Override
    public String getText() {
        return Secures.randomNumeric(getConfig().getTextProducerCharLength());
    }
}