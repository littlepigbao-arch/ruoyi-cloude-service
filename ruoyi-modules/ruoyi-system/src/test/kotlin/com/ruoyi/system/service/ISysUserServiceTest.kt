package com.ruoyi.system.service

import com.alibaba.fastjson.JSONObject
import com.ruoyi.common.core.utils.StringUtils
import com.ruoyi.common.core.utils.uuid.IdUtils
import com.ruoyi.common.security.utils.SecurityUtils
import com.ruoyi.system.RuoYiSystemApplication
import com.ruoyi.system.api.domain.SysUser
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner
import java.io.File
import kotlin.test.Test

@RunWith(SpringJUnit4ClassRunner::class)
@SpringBootTest(classes = [RuoYiSystemApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ISysUserServiceTest {
    @Autowired
    lateinit var iSysUserService: ISysUserService

    @Test
    fun findByPhoneNumberStartingWithTest() {
        println(JSONObject.toJSONString(iSysUserService.findByPhoneNumberStartingWith("1")))
    }

    @Test
    fun batchSetVIP() {
        File("/home/hsdllcw/Desktop/嘉迪课堂会员登记表.csv").readLines().map {
            it.split(",").let { item ->
                mapOf(
                    "phone" to item[3],
                    "name" to item[1],
                    "date" to item[4]
                )
            }
        }.filter { Regex("\\d+").matches(it["phone"]!!) }.forEach {
            print(it)
            if (iSysUserService.findByPhoneNumberStartingWith(it["phone"]).firstOrNull()?.also { user ->
                    setVIP(user,  it)
                } == null) {
                registerByPhoneNumber(it)
                println("==========${it["name"]}已注册为VIP==============")
            }
        }
    }
    private fun setVIP(user:SysUser, it: Map<String, String>) {
        if (user.roles?.any { role -> role.roleKey == "live-vip" } == true) {
            println(":vip")
            if (StringUtils.isEmpty(user.remark)) {
                user.remark = "${it["name"]}-会员开通时间(${it["date"]})"
                iSysUserService.updateUser(user)
            }
        } else {
            println(":普通用户")
            user.roleIds = arrayOf(2, 101)
            iSysUserService.updateUser(user)
            println("\n==========${it["name"]}已设置为VIP==============")
        }
    }

    private fun registerByPhoneNumber(it: Map<String, String>) {
        val sysUser = SysUser()
        sysUser.userName = IdUtils.randomUUID().replace("-".toRegex(), "").substring(0, 30)
        sysUser.nickName = "嘉迪" + it["phone"]?.substring((it["phone"]?.length?:0) - 4)
        sysUser.phonenumber = it["phone"]
        sysUser.password = SecurityUtils.encryptPassword(IdUtils.randomUUID())
        sysUser.deptId = 204
        sysUser.roleIds = arrayOf(2, 101)
        sysUser.remark = "${it["name"]}-会员开通时间(${it["date"]})"
        iSysUserService.registerUser(sysUser)
        setVIP(sysUser, it)
    }
}