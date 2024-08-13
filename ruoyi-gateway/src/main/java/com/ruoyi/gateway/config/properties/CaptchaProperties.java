package com.ruoyi.gateway.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

/**
 * 验证码配置
 * 
 * @author ruoyi
 */
@Configuration
@RefreshScope
@ConfigurationProperties(prefix = "security.captcha")
public class CaptchaProperties
{
    /**
     * 验证码开关
     */
    private Boolean enabled;

    /**
     * 验证码类型（math 数组计算 char 字符）
     */
    private String type;

    public Boolean getEnabled()
    {
        return enabled;
    }

    public void setEnabled(Boolean enabled)
    {
        this.enabled = enabled;
    }

    public String getType()
    {
        return type;
    }

    public void setType(String type)
    {
        this.type = type;
    }

    public static class SMS {
        @RefreshScope
        @Configuration
        @ConfigurationProperties(prefix = "security.captcha.sms.aliyuncs")
        public static class Aliyuncs {
            private String accessKeyId;
            private String accessKeySecret;
            private String signName;
            private String templateCode;
            public String getAccessKeyId() {
                return accessKeyId;
            }
            public void setAccessKeyId(String accessKeyId) {
                this.accessKeyId = accessKeyId;
            }
            public String getAccessKeySecret() {
                return accessKeySecret;
            }

            public void setAccessKeySecret(String accessKeySecret) {
                this.accessKeySecret = accessKeySecret;
            }

            public String getSignName() {
                return signName;
            }
            public void setSignName(String signName) {
                this.signName = signName;
            }
            public String getTemplateCode() {
                return templateCode;
            }
            public void setTemplateCode(String templateCode) {
                this.templateCode = templateCode;
            }
        }
    }
}
