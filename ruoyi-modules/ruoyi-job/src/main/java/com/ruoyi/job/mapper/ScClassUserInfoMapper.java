package com.ruoyi.job.mapper;

import com.ruoyi.job.domain.ScClassUserInfo;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

//@Mapper
//public interface ScClassUserInfoMapper extends tk.mybatis.mapper.common.Mapper<ScClassUserInfo>
public interface ScClassUserInfoMapper
 {
    List<ScClassUserInfo> waitNoticeCourseUser();

}
