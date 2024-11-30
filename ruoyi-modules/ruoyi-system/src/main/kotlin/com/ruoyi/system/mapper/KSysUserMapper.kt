package com.ruoyi.system.mapper

import com.ruoyi.system.api.domain.SysUser

/**
 * 用户表 数据层
 *
 * @author hsdllcw
 */
interface KSysUserMapper {
    /**
     * 通过用户名查询用户
     *
     * @param wxUnionId 微信unionid
     * @return 用户对象信息
     */
    fun selectUserByWxUnionId(wxUnionId: String): SysUser?

    /**
     * 校验微信unionid是否唯一
     *
     * @param wxUnionId 微信unionid
     * @return 结果
     */
    fun checkWxUnionIdUnique(wxUnionId: String): SysUser?

    fun insertSysUserAccount(params: Map<String, Any>): Int

    fun updateSysUserAccount(params: Map<String, Any?>): Int
}