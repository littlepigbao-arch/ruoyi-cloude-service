package com.ruoyi.system.domain;

import lombok.Data;

@Data
public class SyncGoUser {
    private Integer userId;
    private String nickname;
    private String username;
    private String mobile;
    private String lastIp;
    private String remark;
    private String avatar;
    private Integer status;
    private String openId;
    private String unionId;
}
