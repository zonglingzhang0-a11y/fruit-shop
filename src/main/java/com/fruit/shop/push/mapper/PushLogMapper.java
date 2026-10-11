package com.fruit.shop.push.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fruit.shop.push.domain.PushLog;

/** 推送日志 Mapper（发送与效果归因，v2 拆表后取代 v1 的 push_message）。 */
public interface PushLogMapper extends BaseMapper<PushLog> {
}
