package com.fruit.shop.pricing.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fruit.shop.config.AppProperties;
import com.fruit.shop.pricing.PricingPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MODEL：读取 Python 定价模块产出的模型建议（docs/01 第 1 节：算法在 Python 侧，后端只读结果）。
 *
 * <p>产物位置：{@code app.pricing.model-file}，默认 {@code data/seed/pricing_model.json}。
 * 刻意做成"文件缺失/字段缺失都不报错"：找不到就回退 RULE 并在 reason 里说明，
 * 保证答辩现场算法脚本没跑也能演示整条链路。文件按 mtime 缓存，重新生成后无需重启。
 *
 * <p>兼容的 JSON 形状（三种都认，字段名兼容 snake_case 与 camelCase）：
 * <pre>
 * 1) { "suggestions": [ { "itemId": 3, "batchId": 12, "discountRate": 0.6, "expectedQty": 14, "reason": "..." } ] }
 * 2) [ { "itemCode": "1029000110...", "discount_rate": 0.7, "expected_qty": 9 } ]
 * 3) { "items": [ ... ] } / { "records": [ ... ] } / { "rows": [ ... ] }
 * </pre>
 * 匹配顺序：batchId 精确匹配 → itemId → itemCode；同一键重复出现时以先出现的为准。
 */
@Component
public class ModelPricingStrategy implements PricingStrategy {

    private static final Logger log = LoggerFactory.getLogger(ModelPricingStrategy.class);

    private final AppProperties props;
    private final RuleBasedPricingStrategy ruleStrategy;
    private final ObjectMapper objectMapper;

    private volatile CachedArtifact cache;

    public ModelPricingStrategy(AppProperties props, RuleBasedPricingStrategy ruleStrategy, ObjectMapper objectMapper) {
        this.props = props;
        this.ruleStrategy = ruleStrategy;
        this.objectMapper = objectMapper;
    }

    @Override
    public String code() {
        return "MODEL";
    }

    @Override
    public PricingProposal propose(PricingContext context) {
        CachedArtifact artifact = load();
        ModelRow row = lookup(artifact, context);
        if (row == null) {
            if (!props.getPricing().isModelFallbackToRule()) {
                throw new com.fruit.shop.support.BusinessException(
                        "模型策略不可用：未找到 " + artifact.path() + " 中该批次的建议，且已关闭回退");
            }
            PricingProposal fallback = ruleStrategy.propose(context);
            String why = artifact.available()
                    ? "模型产物里没有该商品/批次的建议，已回退规则版："
                    : "模型产物缺失（" + artifact.path() + "），已回退规则版：";
            return new PricingProposal(fallback.discountRate(), fallback.expectedQty(),
                    PricingPolicy.reason(why + fallback.reason()));
        }

        BigDecimal rate = PricingPolicy.toCandidate(row.discountRate());
        BigDecimal price = PricingPolicy.priceOf(context.originalPrice(), rate);
        BigDecimal expectedQty = row.expectedQty() != null
                ? row.expectedQty()
                : PricingPolicy.estimateExpectedQty(context.remainQty(), rate, context.daysLeft());
        StringBuilder reason = new StringBuilder();
        reason.append("模型建议（Python 侧产物 ").append(artifact.path()).append("）：")
                .append(PricingPolicy.discountLabel(rate))
                .append("，¥").append(PricingPolicy.money(context.originalPrice()))
                .append(" → ¥").append(PricingPolicy.money(price))
                .append("；到期日 ").append(context.batch().getExpireDate())
                .append("，现存 ").append(PricingPolicy.qty(context.remainQty()))
                .append("，期望销量 ").append(PricingPolicy.qty(expectedQty));
        if (row.reason() != null && !row.reason().isBlank()) {
            reason.append("；模型说明：").append(row.reason());
        }
        if (price.compareTo(context.costPrice()) < 0) {
            reason.append("；已低于批次成本 ¥").append(PricingPolicy.money(context.costPrice())).append("，属清仓让利");
        }
        return new PricingProposal(rate, expectedQty, PricingPolicy.reason(reason.toString()));
    }

    /** 模型产物是否可用（供 /api/health 或看板展示"当前策略是否真的在用模型"）。 */
    public boolean artifactAvailable() {
        return load().available();
    }

    public String artifactPath() {
        return load().path();
    }

    // ------------------------------------------------------------------ 内部实现

    private ModelRow lookup(CachedArtifact artifact, PricingContext context) {
        Map<String, ModelRow> rows = artifact.rows();
        if (rows.isEmpty()) {
            return null;
        }
        ModelRow row = context.batch() == null ? null : rows.get("batch:" + context.batch().getId());
        if (row != null) {
            return row;
        }
        if (context.item() != null) {
            row = rows.get("item:" + context.item().getId());
            if (row != null) {
                return row;
            }
            if (context.item().getItemCode() != null) {
                row = rows.get("code:" + context.item().getItemCode());
            }
        }
        return row;
    }

    private CachedArtifact load() {
        Path path = Paths.get(props.getPricing().getModelFile());
        String pathText = path.toString();
        try {
            if (!Files.isRegularFile(path)) {
                return new CachedArtifact(pathText, -1L, Map.of(), false);
            }
            long modified = Files.getLastModifiedTime(path).toMillis();
            CachedArtifact cached = cache;
            if (cached != null && cached.path().equals(pathText) && cached.lastModified() == modified) {
                return cached;
            }
            JsonNode root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            Map<String, ModelRow> rows = parse(root);
            CachedArtifact fresh = new CachedArtifact(pathText, modified, rows, true);
            cache = fresh;
            log.info("[model] 载入定价模型产物 {}，可用建议 {} 条", pathText, rows.size());
            return fresh;
        } catch (IOException | RuntimeException e) {
            log.warn("[model] 读取/解析模型产物失败（{}），本次回退规则版：{}", pathText, e.getMessage());
            return new CachedArtifact(pathText, -1L, Map.of(), false);
        }
    }

    private Map<String, ModelRow> parse(JsonNode root) {
        Map<String, ModelRow> rows = new LinkedHashMap<>();
        JsonNode array = root;
        if (root != null && root.isObject()) {
            array = firstArray(root, "suggestions", "items", "records", "rows", "data");
        }
        if (array == null || !array.isArray()) {
            return rows;
        }
        for (JsonNode node : array) {
            BigDecimal discount = decimal(node, "discountRate", "discount_rate", "discount");
            if (discount == null) {
                continue;
            }
            ModelRow row = new ModelRow(
                    discount,
                    decimal(node, "expectedQty", "expected_qty", "expectedSales"),
                    text(node, "reason", "note", "explain"));
            Long itemId = longValue(node, "itemId", "item_id");
            String itemCode = text(node, "itemCode", "item_code", "code", "barcode");
            Long batchId = longValue(node, "batchId", "batch_id");
            if (batchId != null) {
                rows.putIfAbsent("batch:" + batchId, row);
            }
            if (itemId != null) {
                rows.putIfAbsent("item:" + itemId, row);
            }
            if (itemCode != null && !itemCode.isBlank()) {
                rows.putIfAbsent("code:" + itemCode.trim(), row);
            }
        }
        return rows;
    }

    private static JsonNode firstArray(JsonNode root, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode node = root.get(field);
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private static BigDecimal decimal(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.get(field);
            if (value != null && value.isNumber()) {
                return value.decimalValue();
            }
            if (value != null && value.isTextual()) {
                try {
                    return new BigDecimal(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 继续找下一个候选字段
                }
            }
        }
        return null;
    }

    private static Long longValue(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.get(field);
            if (value != null && value.isNumber()) {
                return value.asLong();
            }
            if (value != null && value.isTextual()) {
                try {
                    return Long.parseLong(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // 继续找下一个候选字段
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... fieldNames) {
        for (String field : fieldNames) {
            JsonNode value = node.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return null;
    }

    /** 模型产物的缓存壳：path + mtime + 解析结果。 */
    private record CachedArtifact(String path, long lastModified, Map<String, ModelRow> rows, boolean available) {
        private CachedArtifact {
            rows = new HashMap<>(rows);
        }
    }

    private record ModelRow(BigDecimal discountRate, BigDecimal expectedQty, String reason) {
    }
}
