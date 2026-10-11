package com.fruit.shop.member.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 会员。个人信息最小化：只留尾号，不存完整手机号。 */
@Data
@TableName("member")
public class Member {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String memberNo;

    private String nickname;

    /** 手机号尾号（可空） */
    private String phoneTail;

    private LocalDate joinedDate;

    private Boolean pushEnabled;

    private LocalDateTime createdAt;
}
