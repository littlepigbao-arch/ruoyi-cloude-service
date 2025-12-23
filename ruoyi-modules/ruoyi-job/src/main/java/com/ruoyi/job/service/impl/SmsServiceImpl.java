package com.ruoyi.job.service.impl;

import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.common.core.exception.CaptchaException;
import com.ruoyi.job.config.SmsConfig;
import com.ruoyi.job.service.SmsService;
import com.ujcms.commons.sms.AliyunUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 验证码实现处理
 *
 * @author ruoyi
 */
@Service
public class SmsServiceImpl implements SmsService
{
    @Autowired
    private SmsConfig smsConfig;

    /**
     * 发送学习短信通知
     */
    @Override
    public void sendStudyNoticeSms(String phone) throws CaptchaException {
        AliyunUtils.sendSms(smsConfig.getAccessKeyId(),
                smsConfig.getAccessKeySecret(),
                smsConfig.getSignName(),
                smsConfig.getTemplateCode(),
                JSONObject.of(),
                phone
        );
    }
}
