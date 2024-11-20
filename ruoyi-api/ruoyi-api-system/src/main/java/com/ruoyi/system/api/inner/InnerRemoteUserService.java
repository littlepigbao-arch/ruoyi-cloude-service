package com.ruoyi.system.api.inner;

import com.github.pagehelper.Page;
import com.ruoyi.common.core.constant.SecurityConstants;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.domain.SysUser;
import com.ruoyi.system.api.model.LoginUser;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

public interface InnerRemoteUserService {
    /**
     * 通过用户ID查询用户信息
     *
     * @param userId 用户ID
     * @param source 请求来源
     * @return 结果
     */
    @GetMapping("/inner/user/detail/{userId}")
    R<LoginUser>  getById_Inner(@PathVariable("userId") Long userId, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    /**
     * 通过用户ID查询用户信息
     *
     * @param unionid 用户微信unionid
     * @param source 请求来源
     * @return 结果
     */
    @GetMapping("/inner/user/detail/wx/unionid/{unionid}")
    R<LoginUser>  getByWxUnionId_Inner(@PathVariable("unionid") String unionid, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    /**
     * 通过手机号查询用户信息
     *
     * @param phoneNumber 用户名
     * @param source      请求来源
     * @return 结果
     */
    @GetMapping("/inner/user/info/phoneNumber/{phoneNumber:\\d+}")
    R<LoginUser> getByPhoneNumber_Inner(@PathVariable("phoneNumber") String phoneNumber, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    /**
     * 通过手机号查询用户信息
     *
     * @param phoneNumber 用户名
     * @param source      请求来源
     * @return 结果
     */
    @GetMapping("/inner/user/list/phoneNumber/{phoneNumber:\\d+}")
    R<Page<SysUser>> findByPhoneNumberStartingWith_Inner(@PathVariable("phoneNumber") String phoneNumber, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    @PutMapping("/inner/user")
    R<LoginUser> edit_Inner(@RequestBody LoginUser user, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);

    /**
     * 注册用户信息
     *
     * @param params 包含微信unionId和deptId
     * @param source 请求来源
     * @return 结果
     */
    @PostMapping("/inner/user/register/wx/unionid")
    public R<Boolean> registerUserByWxUnionId_Inner(@RequestBody Map<String, String> params, @RequestHeader(SecurityConstants.FROM_SOURCE) String source);
}