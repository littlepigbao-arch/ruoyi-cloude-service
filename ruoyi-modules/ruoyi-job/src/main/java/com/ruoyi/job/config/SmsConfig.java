package com.ruoyi.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

/**
 * 验证码配置
 * 
 * @author ruoyi
 */
@Data
@Configuration
@RefreshScope
@ConfigurationProperties(prefix = "security.sms.aliyuncs")
public class SmsConfig
{
    private String accessKeyId;
    private String accessKeySecret;
    private String signName;
    private String templateCode;
}
