package com.ruoyi.system.api.factory;

import com.github.pagehelper.Page;
import com.ruoyi.system.api.domain.KSysUserAccount;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.system.api.RemoteUserService;
import com.ruoyi.system.api.domain.SysUser;
import com.ruoyi.system.api.model.LoginUser;

/**
 * 用户服务降级处理
 *
 * @author ruoyi
 */
@Component
public class RemoteUserFallbackFactory implements FallbackFactory<RemoteUserService>
{
    private static final Logger log = LoggerFactory.getLogger(RemoteUserFallbackFactory.class);

    @Override
    public RemoteUserService create(Throwable throwable)
    {
        log.error("用户服务调用失败:{}", throwable.getMessage());
        return new RemoteUserService()
        {
            @NotNull
            @Override
            public R<LoginUser> getById_Inner(Long userId, @NotNull String source) {
                return R.fail("获取用户失败:" + throwable.getMessage());
            }

            @Override
            public R<LoginUser> getByWxUnionId_Inner(String unionid, String source) {
                return R.fail("获取用户失败:" + throwable.getMessage());
            }

            @Override
            public R<LoginUser> getUserInfo(String username, String source)
            {
                return R.fail("获取用户失败:" + throwable.getMessage());
            }

            @NotNull
            @Override
            public R<LoginUser> getByPhoneNumber_Inner(@NotNull String phoneNumber, @NotNull String source) {
                return R.fail("获取用户失败:" + throwable.getMessage());
            }

            @Override
            public R<Page<SysUser>> findByPhoneNumberStartingWith_Inner(String phoneNumber, String source) {
                return R.fail("获取用户失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> registerUserInfo(SysUser sysUser, String source)
            {
                return R.fail("注册用户失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> recordUserLogin(SysUser sysUser, String source)
            {
                return R.fail("记录用户登录信息失败:" + throwable.getMessage());
            }

            @NotNull
            @Override
            public R<LoginUser> edit_Inner(@NotNull LoginUser user, @NotNull String source) {
                return R.fail("修改用户信息失败:" + throwable.getMessage());
            }

            @Override
            public R<Boolean> registerUserBySysUserAccount_Inner(KSysUserAccount sysUserAccount, Long deptId, String source) {
                return R.fail("注册用户失败:" + throwable.getMessage());
            }
        };
    }
}
