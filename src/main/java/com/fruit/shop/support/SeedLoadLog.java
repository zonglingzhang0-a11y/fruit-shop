package com.fruit.shop.support;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 种子数据导入记录，避免重复导入。 */
@Data
@TableName("seed_load_log")
public class SeedLoadLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String seedName;

    private Integer rowCount;

    private LocalDateTime loadedAt;
}
