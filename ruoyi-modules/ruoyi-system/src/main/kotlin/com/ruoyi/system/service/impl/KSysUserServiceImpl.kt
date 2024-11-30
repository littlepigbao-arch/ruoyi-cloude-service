package com.ruoyi.system.service.impl

import com.ruoyi.common.core.constant.UserConstants
import com.ruoyi.common.core.utils.uuid.IdUtils
import com.ruoyi.common.security.utils.SecurityUtils
import com.ruoyi.system.api.domain.SysUser
import com.ruoyi.system.mapper.KSysUserMapper
import com.ruoyi.system.service.IKSysUserService
import com.ruoyi.system.service.ISysUserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

@Service
open class KSysUserServiceImpl : IKSysUserService {
    @Lazy
    @Autowired
    lateinit var sysUserService: ISysUserService

    @Autowired
    lateinit var kSysUserMapper: KSysUserMapper
    override fun getISysUserService() = sysUserService
    override fun selectUserById(userId: Long) = kSysUserMapper.selectUserById(userId)
    override fun selectUserByWxUnionId(wxUnionId: String) = kSysUserMapper.selectUserByWxUnionId(wxUnionId)
    override fun checkWxUnionIdUnique(wxUnionId: String) =
        kSysUserMapper.checkWxUnionIdUnique(wxUnionId)?.run { UserConstants.NOT_UNIQUE } ?: UserConstants.UNIQUE

    /**
     * 注册用户信息
     *
     * @param wxUnionId 微信unionId
     * @return 结果
     */
    override fun registerUserByWxUnionId(wxUnionId: String, deptId: Long?): Boolean {
        val user = SysUser().apply {
            userName = IdUtils.randomUUID().replace("-".toRegex(), "").substring(0, 30)
            nickName = "嘉迪微信用户"
            password = SecurityUtils.encryptPassword(IdUtils.randomUUID())
            this.deptId = deptId
        }
        return sysUserService.registerUser(user).apply {
            sysUserService.selectUserByUserName(user.userName)
            kSysUserMapper.insertSysUserAccount(
                mapOf(
                    "userId" to user.userId,
                    "wxUnionId" to wxUnionId,
                )
            )
        }
    }

    override fun bindWxUnionIdByUserId(userId: Long, wxUnionId: String): Int {
        if (this.selectUserById(userId) != null)
            return kSysUserMapper.updateSysUserAccount(
                mapOf(
                    "userId" to userId,
                    "wxUnionId" to wxUnionId,
                )
            )
        else
            return kSysUserMapper.insertSysUserAccount(
                mapOf(
                    "userId" to userId,
                    "wxUnionId" to wxUnionId,
                )
            )
    }

    override fun unBindWxUnionIdByUserId(userId: Long): Int {
        return kSysUserMapper.updateSysUserAccount(
            mapOf(
                "userId" to userId,
                "wxUnionId" to null,
            )
        )
    }
}