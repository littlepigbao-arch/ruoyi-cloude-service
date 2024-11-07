package com.ruoyi.system.service

import com.alibaba.fastjson.JSONObject
import com.ruoyi.system.RuoYiSystemApplication
import org.junit.runner.RunWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner
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
}