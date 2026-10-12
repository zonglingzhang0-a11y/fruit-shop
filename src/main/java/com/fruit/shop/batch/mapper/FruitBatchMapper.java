package com.fruit.shop.batch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fruit.shop.batch.domain.FruitBatch;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 到货批次 Mapper。 */
public interface FruitBatchMapper extends BaseMapper<FruitBatch> {

    /**
     * 读取并锁定批次行（必须在事务内调用），用于库存的读—改—写串行化。
     * 不加锁时并发回流会互相覆盖 remain_qty（Issue #1：库存 20 并发卖 10 件，剩余变成 19）。
     */
    @Select("SELECT * FROM fruit_batch WHERE id = #{id} FOR UPDATE")
    FruitBatch selectByIdForUpdate(@Param("id") Long id);
}
