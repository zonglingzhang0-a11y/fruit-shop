package com.fruit.shop.pricing.strategy;

/**
 * 定价策略口子。三种实现对应 docs/01-技术方案.md 的 strategy 取值：
 *
 * <ul>
 *   <li>{@code FIXED}：固定折扣档，人工"一刀切"清仓，作为对照基线；</li>
 *   <li>{@code RULE}：按剩余天数分档 + 存量压力的规则版，无外部依赖，随时可跑；</li>
 *   <li>{@code MODEL}：读取 Python 侧产出的模型建议（data/seed/pricing_model.json），
 *       文件缺失时回退 RULE，保证"没有算法产物也能演示"。</li>
 * </ul>
 *
 * <p>新增策略只需实现本接口并注册为 Spring Bean，{@code PricingService} 会自动收集；
 * 任何实现都必须遵守"只在候选折扣档里挑、折扣 &lt;= 1.0 不涨价"。
 */
public interface PricingStrategy {

    /** FIXED / RULE / MODEL。 */
    String code();

    PricingProposal propose(PricingContext context);
}
