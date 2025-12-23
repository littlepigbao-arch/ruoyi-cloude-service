package com.ruoyi.system.api

import com.ruoyi.common.core.constant.ServiceNameConstants
import com.ruoyi.system.api.factory.RemoteUserFallbackFactory
import com.ruoyi.system.api.inner.InnerRemoteSysNoticeService
import org.springframework.cloud.openfeign.FeignClient


/**
 * 公告服务
 * @author 栾成伟
 */
@FeignClient(
    contextId = "remoteSysNoticeService",
    value = ServiceNameConstants.SYSTEM_SERVICE,
    fallbackFactory = RemoteUserFallbackFactory::class
)
interface RemoteSysNoticeService : InnerRemoteSysNoticeService {
}