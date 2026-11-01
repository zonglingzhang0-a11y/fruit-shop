package com.fruit.shop.support;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.domain.MemberPref;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.member.mapper.MemberPrefMapper;
import com.fruit.shop.sales.domain.SalesRecord;
import com.fruit.shop.sales.domain.StrategyDailyStat;
import com.fruit.shop.sales.mapper.SalesRecordMapper;
import com.fruit.shop.sales.mapper.StrategyDailyStatMapper;
import com.fruit.shop.support.mapper.SeedLoadLogMapper;
import com.fruit.shop.traffic.domain.TrafficHourly;
import com.fruit.shop.traffic.mapper.TrafficHourlyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把仿真器产出的 data/seed/shop_*.csv 导入数据库，让系统"一启动就有数据可演示"。
 *
 * 设计原则：
 * - 没有数据文件也能正常启动（只打印提示），便于队友并行开发；
 * - 每个文件只导入一次（由 seed_load_log 记录），重启不会重复；
 * - 每个文件的导入与它的 seed_load_log 记录放在<b>同一个事务</b>里：导入中途被打断（Ctrl-C、IDE 重跑）
 *   整个文件回滚，下次启动干净重来。demo 文件库开启 app.db.keep-existing 后不再每次重建表，
 *   不这样做的话，半截导入会被永久保留（销售重复导入、或唯一键冲突导致再也启动不了）；
 * - 只做数据搬运，业务口径统一放在 Service 层，避免导入器里藏业务规则。
 *
 * 列名契约见 docs/02-仿真器假设与数据字典.md（由 scripts/simulator.py 生成）。
 */
@Component
public class SeedDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataLoader.class);

    @Value("${app.seed.enabled:true}")
    private boolean enabled;

    @Value("${app.seed.dir:./data/seed}")
    private String seedDir;

    private final FruitItemMapper itemMapper;
    private final FruitBatchMapper batchMapper;
    private final MemberMapper memberMapper;
    private final MemberPrefMapper memberPrefMapper;
    private final TrafficHourlyMapper trafficMapper;
    private final SalesRecordMapper salesMapper;
    private final StrategyDailyStatMapper statMapper;
    private final SeedLoadLogMapper seedLogMapper;
    private final TransactionTemplate tx;

    public SeedDataLoader(FruitItemMapper itemMapper,
                          FruitBatchMapper batchMapper,
                          MemberMapper memberMapper,
                          MemberPrefMapper memberPrefMapper,
                          TrafficHourlyMapper trafficMapper,
                          SalesRecordMapper salesMapper,
                          StrategyDailyStatMapper statMapper,
                          SeedLoadLogMapper seedLogMapper,
                          PlatformTransactionManager transactionManager) {
        this.itemMapper = itemMapper;
        this.batchMapper = batchMapper;
        this.memberMapper = memberMapper;
        this.memberPrefMapper = memberPrefMapper;
        this.trafficMapper = trafficMapper;
        this.salesMapper = salesMapper;
        this.statMapper = statMapper;
        this.seedLogMapper = seedLogMapper;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("[seed] app.seed.enabled=false，跳过种子数据导入");
            return;
        }
        Path dir = Paths.get(seedDir);
        if (!Files.isDirectory(dir)) {
            log.info("[seed] 未找到种子目录 {}，跳过导入（系统仍可启动，可手工登记批次）", dir.toAbsolutePath());
            return;
        }

        // 单品必须先导入：批次/销售/推送都要靠 item_code -> item_id 的映射
        // 每个文件一个事务（导入行 + seed_load_log 一起提交或一起回滚）
        Map<String, Long> itemIdByCode = tx.execute(s -> loadItems(dir.resolve("shop_item.csv")));
        if (itemIdByCode == null || itemIdByCode.isEmpty()) {
            log.warn("[seed] shop_item.csv 缺失或为空，依赖单品的种子文件将跳过");
            return;
        }
        tx.executeWithoutResult(s -> loadBatches(dir.resolve("shop_batch.csv"), itemIdByCode));
        Map<String, Long> memberIdByNo = tx.execute(s -> loadMembers(dir.resolve("shop_member.csv")));
        tx.executeWithoutResult(s -> loadMemberPrefs(dir.resolve("shop_member_pref.csv"), memberIdByNo));
        tx.executeWithoutResult(s -> loadTraffic(dir.resolve("shop_traffic_hourly.csv")));
        tx.executeWithoutResult(s -> loadSales(dir.resolve("shop_sales.csv"), itemIdByCode));
        // 三策略日度汇总：让效果看板一打开就有对比数据（由 scripts/evaluate_strategies.py 导出）
        tx.executeWithoutResult(s -> loadStrategyStats(dir.resolve("strategy_daily_stat.csv")));
        // v2 说明：推送已拆成 push_task / push_log，历史推送不再由种子导入——
        // 计划与日志应当由系统运行时的接口产生，这样打开率与转化归因才是真实的。
        // 种子文件 shop_push_message.csv 仍保留（供报告与仿真口径使用），系统不导入。
    }

    // ------------------------------------------------------------------ 单品
    private Map<String, Long> loadItems(Path csv) {
        Map<String, Long> itemIdByCode = new LinkedHashMap<>();
        if (alreadyLoaded("shop_item")) {
            itemMapper.selectList(null).forEach(i -> itemIdByCode.put(i.getItemCode(), i.getId()));
            log.info("[seed] shop_item 已导入过，复用 {} 条", itemIdByCode.size());
            return itemIdByCode;
        }
        if (!Files.isRegularFile(csv)) {
            return itemIdByCode;
        }
        List<String[]> rows = readCsv(csv);
        String[] header = headerOf(rows, csv);
        int added = 0;
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> m = toMap(header, rows.get(i));
            FruitItem item = new FruitItem();
            item.setItemCode(m.get("item_code"));
            item.setItemName(m.get("item_name"));
            item.setCategoryName(m.get("category_name"));
            item.setUnit(m.getOrDefault("unit", "份"));
            item.setCostPrice(dec(m.get("cost_price")));
            item.setBasePrice(dec(m.get("base_price")));
            item.setShelfLifeDays(intOf(m.get("shelf_life_days"), 3));
            item.setLossRatePct(dec(m.getOrDefault("loss_rate_pct", "0")));
            item.setStatus("ON");
            item.setCreatedAt(LocalDateTime.now());
            item.setUpdatedAt(LocalDateTime.now());
            itemMapper.insert(item);
            itemIdByCode.put(item.getItemCode(), item.getId());
            added++;
        }
        logLoaded("shop_item", added);
        return itemIdByCode;
    }

    // ------------------------------------------------------------------ 批次
    private void loadBatches(Path csv, Map<String, Long> itemIdByCode) {
        if (alreadyLoaded("shop_batch") || !Files.isRegularFile(csv)) {
            return;
        }
        List<String[]> rows = readCsv(csv);
        String[] header = headerOf(rows, csv);
        int added = 0;
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> m = toMap(header, rows.get(i));
            Long itemId = itemIdByCode.get(m.get("item_code"));
            if (itemId == null) {
                continue;
            }
            FruitBatch b = new FruitBatch();
            b.setItemId(itemId);
            b.setBatchNo(m.get("batch_no"));
            b.setArriveDate(date(m.get("arrive_date")));
            b.setExpireDate(date(m.get("expire_date")));
            b.setInitQty(dec(m.get("init_qty")));
            b.setRemainQty(dec(m.getOrDefault("remain_qty", m.get("init_qty"))));
            b.setCostPrice(dec(m.get("cost_price")));
            b.setScrapQty(dec(m.getOrDefault("scrap_qty", "0")));
            b.setStatus(m.getOrDefault("status", "ON_SALE"));
            b.setCreatedAt(LocalDateTime.now());
            b.setUpdatedAt(LocalDateTime.now());
            batchMapper.insert(b);
            added++;
        }
        logLoaded("shop_batch", added);
    }

    // ------------------------------------------------------------------ 会员
    private Map<String, Long> loadMembers(Path csv) {
        Map<String, Long> memberIdByNo = new LinkedHashMap<>();
        if (alreadyLoaded("shop_member")) {
            memberMapper.selectList(null).forEach(m -> memberIdByNo.put(m.getMemberNo(), m.getId()));
            log.info("[seed] shop_member 已导入过，复用 {} 条", memberIdByNo.size());
            return memberIdByNo;
        }
        if (!Files.isRegularFile(csv)) {
            return memberIdByNo;
        }
        List<String[]> rows = readCsv(csv);
        String[] header = headerOf(rows, csv);
        int added = 0;
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> m = toMap(header, rows.get(i));
            Member member = new Member();
            member.setMemberNo(m.get("member_no"));
            member.setNickname(m.getOrDefault("nickname", m.get("member_no")));
            member.setPhoneTail(emptyToNull(m.get("phone_tail")));
            member.setJoinedDate(date(m.getOrDefault("joined_date", LocalDate.now().toString())));
            member.setPushEnabled(!"false".equalsIgnoreCase(m.getOrDefault("push_enabled", "true")));
            member.setCreatedAt(LocalDateTime.now());
            memberMapper.insert(member);
            memberIdByNo.put(member.getMemberNo(), member.getId());
            added++;
        }
        logLoaded("shop_member", added);
        return memberIdByNo;
    }

    private void loadMemberPrefs(Path csv, Map<String, Long> memberIdByNo) {
        if (alreadyLoaded("shop_member_pref") || !Files.isRegularFile(csv)) {
            return;
        }
        List<String[]> rows = readCsv(csv);
        String[] header = headerOf(rows, csv);
        int added = 0;
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> m = toMap(header, rows.get(i));
            Long memberId = memberIdByNo.get(m.get("member_no"));
            if (memberId == null) {
                continue;
            }
            MemberPref p = new MemberPref();
            p.setMemberId(memberId);
            p.setCategoryName(m.get("category_name"));
            p.setBuyCount(intOf(m.get("buy_count"), 0));
            String lastBuy = emptyToNull(m.get("last_buy_date"));
            p.setLastBuyDate(lastBuy == null ? null : date(lastBuy));
            p.setScore(dec(m.getOrDefault("score", "0")));
            memberPrefMapper.insert(p);
            added++;
        }
        logLoaded("shop_member_pref", added);
    }

    // ------------------------------------------------------------------ 客流
    private void loadTraffic(Path csv) {
        if (alreadyLoaded("shop_traffic_hourly") || !Files.isRegularFile(csv)) {
            return;
        }
        List<String[]> rows = readCsv(csv);
        String[] header = headerOf(rows, csv);
        int added = 0;
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> m = toMap(header, rows.get(i));
            TrafficHourly t = new TrafficHourly();
            t.setStatDate(date(m.get("stat_date")));
            t.setHourOfDay(intOf(m.get("hour_of_day"), 0));
            t.setOrderCount(intOf(m.get("order_count"), 0));
            t.setCustomerCount(intOf(m.getOrDefault("customer_count", m.get("order_count")), 0));
            t.setIsPeak(Boolean.parseBoolean(m.getOrDefault("is_peak", "false")));
            trafficMapper.insert(t);
            added++;
        }
        logLoaded("shop_traffic_hourly", added);
    }

    // ------------------------------------------------------------------ 销售
    private void loadSales(Path csv, Map<String, Long> itemIdByCode) {
        if (alreadyLoaded("shop_sales") || !Files.isRegularFile(csv)) {
            return;
        }
        List<String[]> rows = readCsv(csv);
        String[] header = headerOf(rows, csv);
        Map<String, Long> batchIdByNo = new HashMap<>();
        batchMapper.selectList(null).forEach(b -> batchIdByNo.put(b.getBatchNo(), b.getId()));
        int added = 0;
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> m = toMap(header, rows.get(i));
            Long itemId = itemIdByCode.get(m.get("item_code"));
            if (itemId == null) {
                continue;
            }
            SalesRecord s = new SalesRecord();
            s.setBizDate(date(m.get("biz_date")));
            s.setSaleTime(datetime(m.get("sale_time")));
            s.setItemId(itemId);
            s.setBatchId(batchIdByNo.get(m.get("batch_no")));
            s.setQuantity(dec(m.get("quantity")));
            s.setUnitPrice(dec(m.get("unit_price")));
            s.setIsDiscount(Boolean.parseBoolean(m.getOrDefault("is_discount", "false")));
            s.setIsReturn(Boolean.parseBoolean(m.getOrDefault("is_return", "false")));
            s.setSource(m.getOrDefault("source", "SIM"));
            salesMapper.insert(s);
            added++;
        }
        logLoaded("shop_sales", added);
    }

    // ------------------------------------------------------------------ 策略日统计
    /**
     * 导入 scripts/evaluate_strategies.py 导出的三策略日度汇总。
     *
     * 为什么需要它：效果看板 GET /api/sales/effect 读 strategy_daily_stat，
     * 而这张表只有"现场销售回流"才会写入；没有种子数据时，答辩打开看板是空的。
     * 列契约（与 pricing 约定，勿改）：
     *   biz_date,strategy,sold_qty,scrap_qty,revenue,cost,gross_profit,scrap_rate,stockout_rate
     */
    private void loadStrategyStats(Path csv) {
        if (alreadyLoaded("strategy_daily_stat") || !Files.isRegularFile(csv)) {
            return;
        }
        List<String[]> rows = readCsv(csv);
        String[] header = headerOf(rows, csv);
        int added = 0;
        for (int i = 1; i < rows.size(); i++) {
            Map<String, String> m = toMap(header, rows.get(i));
            StrategyDailyStat stat = new StrategyDailyStat();
            stat.setBizDate(date(m.get("biz_date")));
            stat.setStrategy(m.get("strategy"));
            stat.setSoldQty(dec(m.get("sold_qty")));
            stat.setScrapQty(dec(m.get("scrap_qty")));
            stat.setRevenue(dec(m.get("revenue")));
            stat.setCost(dec(m.get("cost")));
            stat.setGrossProfit(dec(m.get("gross_profit")));
            stat.setScrapRate(dec(m.get("scrap_rate")));
            stat.setStockoutRate(dec(m.getOrDefault("stockout_rate", "0")));
            statMapper.insert(stat);
            added++;
        }
        logLoaded("strategy_daily_stat", added);
    }

    // ------------------------------------------------------------------ 工具
    private boolean alreadyLoaded(String seedName) {
        return seedLogMapper.selectCount(new QueryWrapper<SeedLoadLog>().eq("seed_name", seedName)) > 0;
    }

    private void logLoaded(String seedName, int rows) {
        SeedLoadLog row = new SeedLoadLog();
        row.setSeedName(seedName);
        row.setRowCount(rows);
        row.setLoadedAt(LocalDateTime.now());
        seedLogMapper.insert(row);
        log.info("[seed] {} 导入 {} 条", seedName, rows);
    }

    private static List<String[]> readCsv(Path path) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rows.add(splitCsvLine(line));
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取种子文件失败: " + path, e);
        }
        return rows;
    }

    /** 极简 CSV 切分：支持双引号包裹与 "" 转义。仿真器产出的字段不含逗号，够用且不引额外依赖。 */
    private static String[] splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuote) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        inQuote = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                inQuote = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        // 去掉 UTF-8 BOM（PowerShell/Excel 导出的 CSV 常见）
        if (!cells.isEmpty() && cells.get(0).startsWith("\uFEFF")) {
            cells.set(0, cells.get(0).substring(1));
        }
        return cells.toArray(new String[0]);
    }

    private static String[] headerOf(List<String[]> rows, Path csv) {
        if (rows.isEmpty()) {
            throw new IllegalStateException("种子文件为空: " + csv);
        }
        return rows.get(0);
    }

    private static Map<String, String> toMap(String[] header, String[] row) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < header.length && i < row.length; i++) {
            map.put(header[i].trim(), row[i].trim());
        }
        return map;
    }

    private static BigDecimal dec(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.trim());
    }

    private static Integer intOf(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private static LocalDate date(String value) {
        return LocalDate.parse(value.trim().substring(0, 10));
    }

    private static LocalDateTime datetime(String value) {
        String v = value.trim();
        if (v.length() <= 10) {
            return LocalDateTime.parse(v + "T09:00:00");
        }
        return LocalDateTime.parse(v.replace(' ', 'T'));
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) {
            return null;
        }
        return v;
    }
}
