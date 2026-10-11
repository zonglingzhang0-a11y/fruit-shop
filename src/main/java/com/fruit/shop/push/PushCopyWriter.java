package com.fruit.shop.push;

import com.fruit.shop.push.dto.PushCopy;
import com.fruit.shop.push.dto.PushCopyRequest;

/**
 * 推送文案生成策略口子（"大模型生成"留成可替换接口，默认实现是纯模板）。
 *
 * <h3>为什么默认不直接调大模型</h3>
 * 课程演示环境不保证外网可达、也不该把 API Key 写进仓库；更重要的是"文案生成失败不能拖垮发送链路"。
 * 所以这里定义接口，默认用 {@link TemplatePushCopyWriter} 做可复现的模板拼装，
 * 接口签名已经把大模型需要的上下文（商品/品类/折扣/现价/剩余天数/推送时段/会员昵称）都准备好了。
 *
 * <h3>换成真实大模型（DeepSeek / OpenAI 兼容接口）的做法</h3>
 * <ol>
 *   <li>新增实现类 {@code push/LlmPushCopyWriter.java}，{@code implements PushCopyWriter}；</li>
 *   <li>类内用 RestClient/HttpClient 调 {@code POST {baseUrl}/chat/completions}，
 *       Prompt 例如："你是街边水果店店主，请写一条 30 字以内的到店促销站内消息，
 *       商品：{itemName}，品类：{categoryName}，折扣：{discountLabel}，现价：{discountPrice}元/{unit}，
 *       剩余保质期：{daysLeft} 天，建议到店时段：{timeHint}，会员昵称：{nickname}。
 *       要求：不夸大、不承诺疗效、不收集个人信息。"；</li>
 *   <li>给它加 {@code @Component @Primary}（本项目的 PushService 注入的是 PushCopyWriter 接口 +
 *       TemplatePushCopyWriter 兜底，业务代码一行都不用改）；</li>
 *   <li>{@code write()} 内部必须 try/catch，失败返回 null 或抛异常都会由 PushService 回退到模板实现；</li>
 *   <li>API Key 走环境变量 / application-local.yml，不入库、不进 Git（见 docs/05 合规说明）。</li>
 * </ol>
 */
public interface PushCopyWriter {

    /**
     * 生成一条推送文案。
     *
     * @return 标题 + 正文；返回 null 表示本实现放弃生成，调用方会回退到模板实现
     */
    PushCopy write(PushCopyRequest request);

    /** 实现名，落日志用。 */
    default String writerName() {
        return getClass().getSimpleName();
    }
}
