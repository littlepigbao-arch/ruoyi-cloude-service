package com.ruoyi.system.controller.inner

import com.ruoyi.common.core.domain.R
import com.ruoyi.common.core.web.controller.BaseController
import com.ruoyi.common.security.annotation.InnerAuth
import com.ruoyi.system.domain.SysNotice
import com.ruoyi.system.service.ISysNoticeService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 内部调用公告信息
 */
@RestController
@RequestMapping("/inner/notice")
open class InnerSysNoticeController : BaseController() {
    @Autowired
    open lateinit var noticeService: ISysNoticeService

    /**
     * 根据ID获取公告信息
     */
    @InnerAuth
    @GetMapping("/detail/{noticeId:\\d+}")
    fun infoById(@PathVariable("noticeId") noticeId: Long): R<Any> {
        return R.ok(noticeService.selectNoticeById(noticeId))
    }
}