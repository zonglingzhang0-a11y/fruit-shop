package com.fruit.shop.pricing;

import com.fruit.shop.config.AppProperties;
import com.fruit.shop.pricing.dto.ModelInputResponse;
import com.fruit.shop.support.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

/**
 * 模型输入快照（{@code GET /api/pricing/model-input}）：给 Python 侧
 * {@code scripts/export_model_suggestions.py} 用，让模型在系统里的真实数据上训练和决策，而不只是种子 CSV。
 *
 * <p>只给"可观测"的数据：单品档案、批次台账（含到货量与现存），以及历史窗口 [bizDate − historyDays, bizDate)
 * 内按 业务日 × 批次 汇总的销量与营收。<b>不含 bizDate 当天及以后的成交</b>——模型只能看过去，
 * 和离线评测的"不偷看"口径一致。期初库存、是否断货由 Python 侧用台账 + 销量重建。只读，不写任何表。
 */
@Service
public class ModelInputService {

    /** 历史窗口上限：再长对弹性估计没有帮助，只会让接口变慢。 */
    public static final int MAX_HISTORY_DAYS = 365;

    public static final int DEFAULT_HISTORY_DAYS = 60;

    private final JdbcTemplate jdbc;
    private final AppProperties props;

    public ModelInputService(JdbcTemplate jdbc, AppProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    public ModelInputResponse snapshot(LocalDate bizDate, Integer historyDays) {
        if (bizDate == null) {
            throw BusinessException.badRequest("bizDate 不能为空（yyyy-MM-dd）");
        }
        int days = historyDays == null ? DEFAULT_HISTORY_DAYS : historyDays;
        if (days < 1 || days > MAX_HISTORY_DAYS) {
            throw BusinessException.badRequest("historyDays 取值 1~" + MAX_HISTORY_DAYS + "，收到：" + days);
        }
        LocalDate from = bizDate.minusDays(days);

        List<ModelInputResponse.Item> items = jdbc.query(
                "SELECT id, item_code, item_name, base_price, cost_price FROM fruit_item ORDER BY id",
                (rs, i) -> new ModelInputResponse.Item(rs.getLong("id"), rs.getString("item_code"),
                        rs.getString("item_name"), rs.getBigDecimal("base_price"), rs.getBigDecimal("cost_price")));

        // 窗口内可能在售过的批次：到货不晚于 bizDate，且到期不早于窗口起点
        List<ModelInputResponse.Batch> batches = jdbc.query(
                "SELECT b.id, b.batch_no, b.item_id, i.item_code, b.arrive_date, b.expire_date, b.init_qty,"
                        + " b.remain_qty, b.cost_price, b.status"
                        + " FROM fruit_batch b JOIN fruit_item i ON i.id = b.item_id"
                        + " WHERE b.arrive_date <= ? AND b.expire_date >= ?"
                        + " ORDER BY b.item_id, b.expire_date, b.id",
                (rs, i) -> new ModelInputResponse.Batch(rs.getLong("id"), rs.getString("batch_no"),
                        rs.getLong("item_id"), rs.getString("item_code"),
                        rs.getDate("arrive_date").toLocalDate(), rs.getDate("expire_date").toLocalDate(),
                        rs.getBigDecimal("init_qty"), rs.getBigDecimal("remain_qty"),
                        rs.getBigDecimal("cost_price"), rs.getString("status")),
                Date.valueOf(bizDate), Date.valueOf(from));

        // 重建"期初库存"要知道每个批次从到货起卖掉了多少，所以销量从这些批次里最早的到货日开始给，
        // 而不是从窗口起点（窗口起点之前到货的批次，窗口外的销量同样要扣）
        LocalDate salesFrom = batches.stream().map(ModelInputResponse.Batch::arriveDate)
                .filter(d -> d.isBefore(from)).min(LocalDate::compareTo).orElse(from);

        // 按 业务日 × 批次 汇总（退货为负数量，一并计入净销量）；没挂批次的销售 batchId 为 null
        List<ModelInputResponse.DailySale> sales = jdbc.query(
                "SELECT s.biz_date, s.batch_id, i.item_code, SUM(s.quantity) AS qty,"
                        + " SUM(s.quantity * s.unit_price) AS revenue"
                        + " FROM sales_record s JOIN fruit_item i ON i.id = s.item_id"
                        + " WHERE s.biz_date >= ? AND s.biz_date < ?"
                        + " GROUP BY s.biz_date, s.batch_id, i.item_code"
                        + " ORDER BY s.biz_date, i.item_code, s.batch_id",
                // 未关联批次的散卖记录 batch_id 是 NULL：要原样给 null，不能读成 0
                (rs, i) -> new ModelInputResponse.DailySale(rs.getDate("biz_date").toLocalDate(),
                        rs.getObject("batch_id", Long.class), rs.getString("item_code"),
                        scale2(rs.getBigDecimal("qty")), scale2(rs.getBigDecimal("revenue"))),
                Date.valueOf(salesFrom), Date.valueOf(bizDate));

        return new ModelInputResponse(bizDate, from, bizDate.minusDays(1), days, salesFrom,
                props.minDiscountRate(), items, batches, sales,
                "训练窗口 [" + from + ", " + bizDate + ")；销量从 " + salesFrom
                        + " 起给（用于重建期初库存），不含 bizDate 当天及以后的成交");
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
