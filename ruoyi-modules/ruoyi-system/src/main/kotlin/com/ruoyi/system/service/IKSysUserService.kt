package com.ruoyi.system.service

import com.ruoyi.system.api.domain.KSysUserAccount
import com.ruoyi.system.api.domain.SysUser

interface IKSysUserService {
    fun getISysUserService(): ISysUserService

    /**
     * 通过id查询用户
     *
     * @param userId
     * @return 用户对象信息
     */
    fun selectUserById(userId: Long): KSysUserAccount?

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
    /**
     * 绑定微信
     */
    fun bindWxUnionIdByUserId(userId: Long,wxUnionId: String): Int
    /**
     * 解绑微信
     */
    fun unBindWxUnionIdByUserId(userId: Long): Int
}