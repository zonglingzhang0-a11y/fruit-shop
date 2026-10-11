package com.fruit.shop.support;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 骨架自检实体，仅用于验证 MyBatis-Plus 链路。 */
@Data
@TableName("health_check")
public class HealthCheck {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String checkName;

    private String status;

    private LocalDateTime checkedAt;
}
