package com.ruoyi.system.controller.inner

import com.github.pagehelper.Page
import com.ruoyi.common.core.domain.R
import com.ruoyi.common.core.utils.StringUtils
import com.ruoyi.common.core.utils.ip.IpUtils
import com.ruoyi.common.core.web.controller.BaseController
import com.ruoyi.common.core.web.domain.AjaxResult
import com.ruoyi.common.log.annotation.Log
import com.ruoyi.common.log.enums.BusinessType
import com.ruoyi.common.security.annotation.InnerAuth
import com.ruoyi.common.security.service.TokenService
import com.ruoyi.system.api.domain.SysUser
import com.ruoyi.system.api.model.LoginUser
import com.ruoyi.system.service.IKSysUserService
import com.ruoyi.system.service.ISysConfigService
import com.ruoyi.system.service.ISysPermissionService
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
open class InnerSysUserController : BaseController() {

    @Autowired
    open lateinit var userService: ISysUserService

    @Autowired
    open lateinit var kSysUserService: IKSysUserService

    @Autowired
    open lateinit var permissionService: ISysPermissionService

    @Autowired
    open lateinit var tokenService: TokenService

    @Autowired
    open lateinit var configService: ISysConfigService


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
     * 获取当前用户信息
     */
    @InnerAuth
    @GetMapping("/list/phoneNumber/{phoneNumber:\\d+}")
    fun findByPhoneNumberStartingWith(@PathVariable("phoneNumber") phoneNumber: String?): R<Page<SysUser>> {
        return R.ok(userService.findByPhoneNumberStartingWith(phoneNumber))
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
     * 根据微信unionid获取用户信息
     */
    @InnerAuth
    @GetMapping("/detail/wx/unionid/{unionid}")
    fun infoByWxUnionId(@PathVariable("unionid") unionid: String): R<LoginUser> {
        val sysUser = kSysUserService.selectUserByWxUnionId(unionid)
        if (StringUtils.isNull(sysUser)) {
            return R.fail("wx_unionid错误")
        }
        // 角色集合
        val roles: Set<String> = permissionService.getRolePermission(sysUser)
        // 权限集合
        val permissions: Set<String> = permissionService.getMenuPermission(sysUser)
        val sysUserVo = LoginUser()
        sysUserVo.userid = sysUser?.userId
        sysUserVo.ipaddr = IpUtils.getIpAddr()
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
    fun edit(@Validated @RequestBody loginUser: LoginUser): AjaxResult {
        val targetUser = loginUser.sysUser
        if (!userService.checkUserNameUnique(targetUser)) {
            return error("修改用户'" + targetUser.userName + "'失败，登录账号已存在")
        } else if (StringUtils.isNotEmpty(targetUser.phonenumber) && !userService.checkPhoneUnique(targetUser)) {
            return error("修改用户'" + targetUser.userName + "'失败，手机号码已存在")
        } else if (StringUtils.isNotEmpty(targetUser.email) && !userService.checkEmailUnique(targetUser)) {
            return error("修改用户'" + targetUser.userName + "'失败，邮箱账号已存在")
        }
        val originUser = userService.selectUserById(targetUser.userId)
        originUser.userName = targetUser.userName ?: originUser.userName
        originUser.nickName = targetUser.nickName ?: originUser.nickName
        originUser.phonenumber = targetUser.phonenumber ?: originUser.phonenumber
        originUser.email = targetUser.email ?: originUser.email
        originUser.avatar = targetUser.avatar ?: originUser.avatar
        originUser.sex = targetUser.sex ?: originUser.sex
        originUser.updateBy = originUser.userName
        originUser.sysUserAccount = loginUser.sysUser.sysUserAccount
        userService.updateUser(originUser)
        // 更新缓存用户信息
        tokenService.setLoginUser(loginUser)
        return success(loginUser)
    }

    /**
     * 注册用户信息
     */
    @InnerAuth
    @PostMapping("/register/wx/unionid")
    fun registerUserByWxUnionId(@RequestBody params: Map<String, String?>): R<Boolean> {
        if (!("true" == configService.selectConfigByKey("sys.account.registerUser"))) {
            return R.fail("当前系统没有开启注册功能！")
        }
        val unionid = params.get("unionId")
        val deptId = params.get("deptId")?.toLong()
        if (unionid == null) return R.fail("微信unionid不存在无法注册")
        if (!kSysUserService.checkWxUnionIdUnique(unionid)) {
            return R.fail("保存用户'$unionid'失败，注册账号已存在")
        }
        return R.ok(kSysUserService.registerUserByWxUnionId(unionid, deptId))
    }
}