package com.ruoyi.system.service

import com.ruoyi.system.api.domain.SysUser

interface IKSysUserService {
    fun getISysUserService(): ISysUserService

    /**
     * 通过微信unionid查询用户
     *
     * @param wxUnionId 微信unionid
     * @return 用户对象信息
     */
    fun selectUserByWxUnionId(wxUnionId: String): SysUser?

    /**
     * 校验微信unionid是否唯一
     *
     * @param wxUnionId 微信unionid
     * @return 用户对象信息
     */
    fun checkWxUnionIdUnique(wxUnionId: String): Boolean

    /**
     * 注册用户信息
     *
     * @param wxUnionId 微信unionId
     * @return 结果
     */
    fun registerUserByWxUnionId(wxUnionId: String, deptId: Long?): Boolean
}