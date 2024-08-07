package com.ruoyi.gateway.service;

import java.io.IOException;
import com.ruoyi.common.core.exception.CaptchaException;
import com.ruoyi.common.core.web.domain.AjaxResult;

/**
 * 验证码处理
 *
 * @author ruoyi
 */
public interface ValidateCodeService
{
    /**
     * 生成图片验证码
     */
    public AjaxResult createCaptcha() throws IOException, CaptchaException;

    /**
     * 生成短信验证码
     */
    public AjaxResult createSMSCaptcha(String receiver) throws IOException, CaptchaException;

    /**
     * 校验验证码
     */
    public void checkCaptcha(String key, String value) throws CaptchaException;
}
