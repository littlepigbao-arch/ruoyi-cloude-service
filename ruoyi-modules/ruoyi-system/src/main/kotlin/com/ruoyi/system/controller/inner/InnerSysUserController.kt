package com.ruoyi.system.controller.inner

import com.ruoyi.common.core.domain.R
import com.ruoyi.common.core.utils.StringUtils
import com.ruoyi.common.log.annotation.Log
import com.ruoyi.common.log.enums.BusinessType
import com.ruoyi.common.security.annotation.InnerAuth
import com.ruoyi.system.api.domain.SysUser
import com.ruoyi.system.api.model.LoginUser
import com.ruoyi.system.service.ISysDeptService
import com.ruoyi.system.service.ISysPermissionService
import com.ruoyi.system.service.ISysRoleService
import com.ruoyi.system.service.ISysUserService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.*

/**
 * 用户信息
 *
 * @author hsdllcw
 */
@RestController
@RequestMapping("/inner/user")
class InnerSysUserController {

    @Autowired
    lateinit var userService: ISysUserService

    @Autowired
    lateinit var permissionService: ISysPermissionService

    @Autowired
    lateinit var deptService: ISysDeptService

    @Autowired
    lateinit var roleService: ISysRoleService


    /**
     * 获取当前用户信息
     */
    @InnerAuth
    @GetMapping("/info/phoneNumber/{phoneNumber:\\d+}")
    fun infoByPhone(@PathVariable("phoneNumber") phoneNumber: String?): R<LoginUser> {
        val sysUser: SysUser = userService.selectUserByPhoneNumber(phoneNumber)
        if (StringUtils.isNull(sysUser)) {
            return R.fail("用户名或密码错误")
        }
        // 角色集合
        val roles: Set<String> = permissionService.getRolePermission(sysUser)
        // 权限集合
        val permissions: Set<String> = permissionService.getMenuPermission(sysUser)
        val sysUserVo = LoginUser()
        sysUserVo.sysUser = sysUser
        sysUserVo.roles = roles
        sysUserVo.permissions = permissions
        return R.ok(sysUserVo)
    }

    /**
     * 根据ID获取用户信息
     */
    @InnerAuth
    @GetMapping("/detail/{userId}")
    fun infoById(@PathVariable("userId") userId: Long?): R<LoginUser> {
        val sysUser: SysUser = userService.selectUserById(userId)
        // 角色集合
        val roles: Set<String> = permissionService.getRolePermission(sysUser)
        // 权限集合
        val permissions: Set<String> = permissionService.getMenuPermission(sysUser)
        val sysUserVo = LoginUser()
        sysUserVo.sysUser = sysUser
        sysUserVo.roles = roles
        sysUserVo.permissions = permissions
        return R.ok(sysUserVo)
    }

    /**
     * 修改用户
     */
    @InnerAuth
    @PutMapping
    @Log(title = "用户修改本人信息", businessType = BusinessType.UPDATE)
    fun edit(@Validated @RequestBody loginUser: LoginUser): R<LoginUser> {
        val originUser = userService.selectUserById(loginUser.userid)
        val targetUser = loginUser.sysUser
        userService.checkUserAllowed(originUser)
        userService.checkUserDataScope(originUser.userId)
        deptService.checkDeptDataScope(originUser.deptId)
        roleService.checkRoleDataScope(*originUser.roleIds)
        if (!userService.checkUserNameUnique(targetUser)) {
            return error("修改用户'" + targetUser.userName + "'失败，登录账号已存在")
        } else if (StringUtils.isNotEmpty(targetUser.phonenumber) && !userService.checkPhoneUnique(targetUser)) {
            return error("修改用户'" + targetUser.userName + "'失败，手机号码已存在")
        } else if (StringUtils.isNotEmpty(targetUser.email) && !userService.checkEmailUnique(targetUser)) {
            return error("修改用户'" + targetUser.userName + "'失败，邮箱账号已存在")
        }
        originUser.userName = targetUser.userName ?: originUser.userName
        originUser.phonenumber = targetUser.phonenumber ?: originUser.phonenumber
        originUser.email = targetUser.email ?: originUser.email
        originUser.avatar = targetUser.avatar ?: originUser.avatar
        originUser.updateBy = originUser.userName
        userService.updateUser(originUser)
        return R.ok(loginUser)
    }
}