package com.ruoyi.ai.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 工作簿内容 SHA-256 工具。后续可在此接入 Snapshot 规范化器。 */
public final class ContentHashUtils
{
    private ContentHashUtils() { }

    public static String sha256(String content)
    {
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((content == null ? "" : content).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes)
            {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        }
        catch (Exception e)
        {
            throw new IllegalStateException("无法计算工作簿内容哈希", e);
        }
    }
}
