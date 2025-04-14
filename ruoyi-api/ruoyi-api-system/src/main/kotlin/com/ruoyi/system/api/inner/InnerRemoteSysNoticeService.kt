package com.ruoyi.system.api.inner

import com.ruoyi.common.core.constant.SecurityConstants
import com.ruoyi.common.core.domain.R
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam

interface InnerRemoteSysNoticeService {
    /**
     * 根据公告ID查询公告信息
     * @param noticeId 公告ID
     * @return 公告信息
     */
    @GetMapping("/inner/notice/detail")
    fun getById_Inner(
        @RequestParam("noticeId") noticeId: Long,
        @RequestHeader(SecurityConstants.FROM_SOURCE) source: String
    ): R<Any>
}