package com.fruit.shop.push;

import com.fruit.shop.pricing.PricingPolicy;
import com.fruit.shop.push.dto.PushCopy;
import com.fruit.shop.push.dto.PushCopyRequest;
import org.springframework.stereotype.Component;

/**
 * 默认文案实现：纯模板拼装，不联网、可复现、可离线演示。
 *
 * <p>文案结构（答辩时可以直接指着念）：
 * 昵称 + 商品 + 剩余保质期 + 折扣 + 原价→现价 + 建议到店时段 + 售完为止的行动号召。
 * 不夸大、不承诺、不诱导囤货，也不需要任何个人信息（只有昵称）。
 */
@Component
public class TemplatePushCopyWriter implements PushCopyWriter {

    private static final int TITLE_MAX = 64;
    private static final int CONTENT_MAX = 512;

    @Override
    public PushCopy write(PushCopyRequest request) {
        String store = blankTo(request.storeName(), "街边水果店");
        String itemName = blankTo(request.itemName(), "临期果品");
        String unit = blankTo(request.unit(), "份");
        String label = blankTo(request.discountLabel(), "特价");

        String title = String.format("【%s】%s%s", store, itemName, "原价".equals(label) ? "临期特价" : label + "清仓");

        StringBuilder content = new StringBuilder();
        if (request.nickname() != null && !request.nickname().isBlank()) {
            content.append(request.nickname().trim()).append("，");
        } else {
            content.append("今日到货鲜果：");
        }
        content.append(itemName);
        if (request.daysLeft() != null) {
            if (request.daysLeft() <= 0) {
                content.append("今天最后一天");
            } else {
                content.append("还剩 ").append(request.daysLeft()).append(" 天保质期");
            }
            if (request.expireDate() != null) {
                content.append("（").append(request.expireDate()).append(" 到期）");
            }
        }
        content.append("，今天").append(label).append("：");
        content.append("原价 ¥").append(PricingPolicy.money(request.originalPrice())).append("/").append(unit)
                .append(" → 现价 ¥").append(PricingPolicy.money(request.discountPrice())).append("/").append(unit).append("。");
        if (request.timeHint() != null && !request.timeHint().isBlank()) {
            content.append(request.timeHint()).append("。");
        }
        content.append("品质没问题、只是不能放久了，售完为止～");

        return new PushCopy(PricingPolicy.truncate(title, TITLE_MAX), PricingPolicy.truncate(content.toString(), CONTENT_MAX));
    }

    @Override
    public String writerName() {
        return "TemplatePushCopyWriter";
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
