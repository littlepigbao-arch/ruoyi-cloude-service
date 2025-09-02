package com.ruoyi.job.task;

import com.ruoyi.job.config.SmsConfig;
import com.ruoyi.job.domain.ScClassUserInfo;
import com.ruoyi.job.mapper.ScClassUserInfoMapper;
import com.ruoyi.job.service.SmsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Component("liveTask")
public class LiveTask {
    @Resource
    ScClassUserInfoMapper scClassUserInfoMapper;
    @Autowired
    private SmsService smsAliyuncs;

    @Autowired
    private SmsConfig smsConfig;
    /**
     * 短信打卡
     */
    public void CourseSmsNotice(){
        List<ScClassUserInfo> list = scClassUserInfoMapper.waitNoticeCourseUser();
        list.forEach(item->{
            if (item.getPhonenumber() !=  null && !item.getPhonenumber().trim().isEmpty()){
                log.info("发送打卡提醒 {} {} {}",item.getUserId(),item.getNickName(),item.getPhonenumber());
                smsAliyuncs.sendStudyNoticeSms(item.getPhonenumber());
            }
        });
    }
}
