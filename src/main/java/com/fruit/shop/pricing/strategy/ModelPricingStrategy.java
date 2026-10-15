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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MODEL：读取 Python 定价模块产出的模型建议（docs/01 第 1 节：算法在 Python 侧，后端只读结果）。
 *
 * <p>产物由 {@code scripts/export_model_suggestions.py} 生成，位置 {@code app.pricing.model-file}
 * （默认 {@code data/seed/pricing_model.json}），按 mtime 缓存，重新导出后无需重启。格式：
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "bizDate": "2026-06-29",              // 产物对应的业务日：与生成建议的业务日不一致就视为过期
 *   "generatedAt": "2026-10-15T10:00:00",
 *   "modelVersion": "pricing_model.py@1a2b3c4d5e6f",
 *   "modelStatus": "ok",                  // 模型没拟合出来时为其它值，整份产物不用
 *   "modelNote": "...",
 *   "inputSource": "api" | "seed",
 *   "inputHash": "...",
 *   "suggestions": [ { "batchId": 12, "itemId": 3, "itemCode": "F1001",
 *                      "discountRate": 0.7, "expectedQty": 14.2, "reason": "..." } ]
 * }
 * </pre>
 *
 * <p><b>如实回退</b>：产物缺失、读不了、没写业务日、业务日对不上（过期）、模型状态不是 ok、或者产物里没有这个批次，
 * 都回退规则版，并且 ① 在理由里写清是哪一种原因 ② 提案的 {@code effectiveStrategy} 标为 RULE，
 * 由 PricingService 写进 {@code pricing_suggestion.effective_strategy}，效果看板据此把这些建议算到 RULE 名下。
 * 匹配顺序：batchId → batchNo → itemId → itemCode（同一单品各批次共用一个货架价，模型按单品联合决策）。
 * 只按单品匹配上时，产物里的期望销量是别的批次的分摊值，不用，改按本批次现存估算。
 */
@Component
public class ModelPricingStrategy implements PricingStrategy {

    private static final Logger log = LoggerFactory.getLogger(ModelPricingStrategy.class);

    public static final String STATUS_OK = "ok";

    private final AppProperties props;
    private final RuleBasedPricingStrategy ruleStrategy;
    private final ObjectMapper objectMapper;

    private volatile Artifact cache;

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
        Artifact artifact = load();
        String unusable = unusableReason(artifact, context.bizDate());
        ModelRow row = unusable == null ? lookup(artifact, context) : null;
        if (unusable == null && row == null) {
            unusable = "模型产物（业务日 " + artifact.bizDate() + "）里没有该批次/单品的建议";
        }
        if (unusable != null) {
            if (!props.getPricing().isModelFallbackToRule()) {
                throw new com.fruit.shop.support.BusinessException("模型策略不可用：" + unusable + "，且已关闭回退规则版");
            }
            PricingProposal fallback = ruleStrategy.propose(context);
            return new PricingProposal(fallback.discountRate(), fallback.expectedQty(),
                    PricingPolicy.reason(unusable + "，已回退规则版：" + fallback.reason()), ruleStrategy.code());
        }

        BigDecimal rate = PricingPolicy.toCandidate(row.discountRate());
        BigDecimal price = PricingPolicy.priceOf(context.originalPrice(), rate);
        BigDecimal expectedQty = row.batchLevel() && row.expectedQty() != null
                ? row.expectedQty()
                : PricingPolicy.estimateExpectedQty(context.remainQty(), rate, context.daysLeft());
        StringBuilder reason = new StringBuilder();
        reason.append("模型建议（").append(artifact.modelVersion() == null ? "Python 模型" : artifact.modelVersion())
                .append("，").append(artifact.bizDate()).append(" 产物）：")
                .append(PricingPolicy.discountLabel(rate))
                .append("，¥").append(PricingPolicy.money(context.originalPrice()))
                .append(" → ¥").append(PricingPolicy.money(price))
                .append("，期望销量 ").append(PricingPolicy.qty(expectedQty));
        if (row.reason() != null && !row.reason().isBlank()) {
            reason.append("；").append(row.reason());
        }
        if (price.compareTo(context.costPrice()) < 0) {
            reason.append("；已低于批次成本 ¥").append(PricingPolicy.money(context.costPrice())).append("，属清仓让利");
        }
        return new PricingProposal(rate, expectedQty, PricingPolicy.reason(reason.toString()), code(),
                row.rawDiscountRate());
    }

    /**
     * 这个批次现在能不能真的由模型定价（产物对该业务日可用、且覆盖了该批次/单品）。
     * PricingService 据此决定"早先回退了规则版的批次"值不值得重新生成，避免每点一次都白占一次调价。
     */
    public boolean covers(PricingContext context) {
        Artifact artifact = load();
        return unusableReason(artifact, context.bizDate()) == null && lookup(artifact, context) != null;
    }

    /**
     * 模型产物对某个业务日的状态（{@code GET /api/pricing/model-status}、定价页提示用）。
     *
     * @param bizDate 要生成建议的业务日；null 时只报告产物本身
     */
    public ModelStatus status(LocalDate bizDate) {
        Artifact artifact = load();
        String unusable = bizDate == null ? artifactProblem(artifact) : unusableReason(artifact, bizDate);
        return new ModelStatus(
                artifact.path(),
                artifact.present(),
                unusable == null,
                bizDate,
                artifact.bizDate(),
                artifact.generatedAt(),
                artifact.modelVersion(),
                artifact.modelStatus(),
                artifact.modelNote(),
                artifact.inputSource(),
                artifact.rowCount(),
                unusable == null ? "模型产物可用" : unusable);
    }

    /**
     * 模型产物状态。
     *
     * @param usable  对请求的业务日是否可用（不可用时 MODEL 会回退规则版）
     * @param message 可用/不可用的人话原因
     */
    public record ModelStatus(String path, boolean present, boolean usable, LocalDate requestedBizDate,
                              LocalDate artifactBizDate, String generatedAt, String modelVersion,
                              String modelStatus, String modelNote, String inputSource, int suggestionCount,
                              String message) {
    }

    // ------------------------------------------------------------------ 内部实现

    /** 产物对该业务日不可用的原因；可用返回 null。 */
    private String unusableReason(Artifact artifact, LocalDate bizDate) {
        String problem = artifactProblem(artifact);
        if (problem != null) {
            return problem;
        }
        if (!artifact.bizDate().equals(bizDate)) {
            return "模型产物是 " + artifact.bizDate() + " 的，与本次业务日 " + bizDate
                    + " 不一致（已过期，请先运行 scripts/export_model_suggestions.py --biz-date " + bizDate + "）";
        }
        return null;
    }

    /** 与业务日无关的产物问题；没问题返回 null。 */
    private String artifactProblem(Artifact artifact) {
        if (!artifact.present()) {
            return "模型产物缺失（" + artifact.path() + "，请先运行 scripts/export_model_suggestions.py）";
        }
        if (artifact.error() != null) {
            return "模型产物无法读取（" + artifact.error() + "）";
        }
        if (artifact.bizDate() == null) {
            return "模型产物没有写业务日（bizDate），无法确认是否过期";
        }
        if (!STATUS_OK.equalsIgnoreCase(artifact.modelStatus())) {
            return "模型产物标记模型不可用（modelStatus=" + artifact.modelStatus()
                    + (artifact.modelNote() == null ? "" : "：" + artifact.modelNote()) + "）";
        }
        return null;
    }

    private ModelRow lookup(Artifact artifact, PricingContext context) {
        Map<String, ModelRow> rows = artifact.rows();
        if (context.batch() != null) {
            ModelRow row = rows.get("batch:" + context.batch().getId());
            if (row == null && context.batch().getBatchNo() != null) {
                row = rows.get("batchNo:" + context.batch().getBatchNo());
            }
            if (row != null) {
                return row;
            }
        }
        if (context.item() != null) {
            ModelRow row = rows.get("item:" + context.item().getId());
            if (row == null && context.item().getItemCode() != null) {
                row = rows.get("code:" + context.item().getItemCode());
            }
            if (row != null) {
                // 单品级匹配：折扣是该单品的货架价，期望销量却是某个别的批次的分摊值，不能照搬
                return new ModelRow(row.discountRate(), row.expectedQty(), row.reason(), row.rawDiscountRate(), false);
            }
        }
        return null;
    }

    /** 按 路径 + mtime 缓存：同一版本的文件只读一次、解析失败也只告警一次（生成一天的建议会对每个批次调用）。 */
    private Artifact load() {
        Path path = Paths.get(props.getPricing().getModelFile());
        String pathText = path.toString();
        long modified;
        try {
            if (!Files.isRegularFile(path)) {
                return Artifact.missing(pathText);
            }
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Artifact.missing(pathText);
        }
        Artifact cached = cache;
        if (cached != null && cached.path().equals(pathText) && cached.lastModified() == modified) {
            return cached;
        }
        Artifact fresh;
        try {
            JsonNode root = objectMapper.readTree(Files.readString(path, StandardCharsets.UTF_8));
            fresh = parse(pathText, modified, root);
            log.info("[model] 载入定价模型产物 {}：业务日 {}，状态 {}，建议 {} 条，版本 {}",
                    pathText, fresh.bizDate(), fresh.modelStatus(), fresh.rowCount(), fresh.modelVersion());
        } catch (IOException | RuntimeException e) {
            log.warn("[model] 读取/解析模型产物失败（{}）：{}", pathText, e.getMessage());
            fresh = new Artifact(pathText, modified, true, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    null, null, null, null, null, null, Map.of(), 0);
        }
        cache = fresh;
        return fresh;
    }

    private Artifact parse(String path, long modified, JsonNode root) {
        if (root == null || !root.isObject()) {
            return new Artifact(path, modified, true, "顶层必须是 JSON 对象（含 bizDate、suggestions 等字段）",
                    null, null, null, null, null, null, Map.of(), 0);
        }
        LocalDate bizDate = null;
        String bizText = text(root, "bizDate", "biz_date");
        if (bizText != null) {
            try {
                bizDate = LocalDate.parse(bizText);
            } catch (DateTimeParseException e) {
                return new Artifact(path, modified, true, "bizDate 不是 yyyy-MM-dd：" + bizText,
                        null, null, null, null, null, null, Map.of(), 0);
            }
        }
        Map<String, ModelRow> rows = new LinkedHashMap<>();
        int count = 0;
        JsonNode array = root.get("suggestions");
        if (array != null && array.isArray()) {
            for (JsonNode node : array) {
                BigDecimal discount = decimal(node, "discountRate", "discount_rate");
                if (discount == null) {
                    continue;
                }
                count++;
                ModelRow row = new ModelRow(discount, decimal(node, "expectedQty", "expected_qty"),
                        text(node, "reason"), decimal(node, "rawDiscountRate", "raw_discount_rate"), true);
                Long batchId = longValue(node, "batchId", "batch_id");
                String batchNo = text(node, "batchNo", "batch_no");
                Long itemId = longValue(node, "itemId", "item_id");
                String itemCode = text(node, "itemCode", "item_code");
                if (batchId != null) {
                    rows.putIfAbsent("batch:" + batchId, row);
                }
                if (batchNo != null) {
                    rows.putIfAbsent("batchNo:" + batchNo, row);
                }
                if (itemId != null) {
                    rows.putIfAbsent("item:" + itemId, row);
                }
                if (itemCode != null) {
                    rows.putIfAbsent("code:" + itemCode, row);
                }
            }
        }
        return new Artifact(path, modified, true, null, bizDate,
                text(root, "generatedAt", "generated_at"),
                text(root, "modelVersion", "model_version"),
                text(root, "modelStatus", "model_status"),
                text(root, "modelNote", "model_note"),
                text(root, "inputSource", "input_source"),
                rows, count);
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
            if (value != null && value.isIntegralNumber()) {
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

    /** 模型产物的缓存壳：path + mtime + 元数据 + 解析结果。 */
    private record Artifact(String path, long lastModified, boolean present, String error, LocalDate bizDate,
                            String generatedAt, String modelVersion, String modelStatus, String modelNote,
                            String inputSource, Map<String, ModelRow> rows, int rowCount) {
        private Artifact {
            rows = new HashMap<>(rows);
        }

        static Artifact missing(String path) {
            return new Artifact(path, -1L, false, null, null, null, null, null, null, null, Map.of(), 0);
        }
    }

    /**
     * @param rawDiscountRate 不受最低折扣线约束时模型会选的档位（触底时才有）
     * @param batchLevel      是否按批次（id / 批次号）匹配上；单品级匹配时 expectedQty 不适用于本批次
     */
    private record ModelRow(BigDecimal discountRate, BigDecimal expectedQty, String reason,
                            BigDecimal rawDiscountRate, boolean batchLevel) {
    }
}
