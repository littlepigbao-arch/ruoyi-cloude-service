package com.ruoyi.system.api.domain

import com.cyl.manager.ums.domain.entity.MemberWechat

open class KSysUserAccount : MemberWechat() {
    /** 用户ID */
    open var userId: Long? = null
        set(value) {
            memberId = value
            field = value
        }
        get() = memberId

    /** 微信UnionId */
    open var wxUnionId: String? = null
        set(value) {
            unionid = value
            field = value
        }
        get() = unionid
}