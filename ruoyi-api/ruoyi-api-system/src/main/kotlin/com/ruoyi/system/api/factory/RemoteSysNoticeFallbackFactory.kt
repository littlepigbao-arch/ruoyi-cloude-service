package com.ruoyi.system.api.factory

import com.ruoyi.common.core.domain.R
import com.ruoyi.system.api.RemoteSysNoticeService
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.cloud.openfeign.FallbackFactory
import org.springframework.stereotype.Component

@Component
open class RemoteSysNoticeFallbackFactory : FallbackFactory<RemoteSysNoticeService> {
    companion object {
        var log: Logger = LoggerFactory.getLogger(RemoteSysNoticeFallbackFactory::class.java)
    }

    override fun create(throwable: Throwable): RemoteSysNoticeService {
        log.error("公告服务调用失败:{}", throwable.message)
        return object : RemoteSysNoticeService {
            override fun getById_Inner(noticeId: Long?, source: String?): R<Any> {
                return R.fail("获取公告失败")
            }
        }
    }
}