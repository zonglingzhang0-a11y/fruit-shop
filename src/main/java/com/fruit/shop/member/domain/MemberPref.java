package com.fruit.shop.member.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 会员品类偏好：推送"推给谁"的依据（买过该品类的会员优先）。 */
@Data
@TableName("member_pref")
public class MemberPref {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long memberId;

    private String categoryName;

    private Integer buyCount;

    private LocalDate lastBuyDate;

    private BigDecimal score;
}
