package com.ruoyi.job.service;

import com.ruoyi.common.core.exception.CaptchaException;

/**
 * 验证码处理
 *
 * @author ruoyi
 */
public interface SmsService
{
    /**
     * 发送学习短信通知
     */
    public void sendStudyNoticeSms(String phone) throws CaptchaException;
}
