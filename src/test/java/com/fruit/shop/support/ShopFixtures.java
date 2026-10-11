package com.fruit.shop.support;

import com.fruit.shop.batch.domain.FruitBatch;
import com.fruit.shop.batch.mapper.FruitBatchMapper;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.member.domain.Member;
import com.fruit.shop.member.domain.MemberPref;
import com.fruit.shop.member.mapper.MemberMapper;
import com.fruit.shop.member.mapper.MemberPrefMapper;
import com.fruit.shop.traffic.domain.TrafficHourly;
import com.fruit.shop.traffic.mapper.TrafficHourlyMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 测试夹具：每个测试都用"唯一编码 + 唯一日期"造自己的数据，
 * 这样即使 H2 内存库被同一 JVM 里的多个测试类共享、以及将来 seed CSV 导入真实数据，测试之间也不会互相污染。
 */
public final class ShopFixtures {

    private ShopFixtures() {
    }

    public static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static FruitItem item(FruitItemMapper mapper, String name, String category,
                                 String costPrice, String basePrice, int shelfLifeDays) {
        FruitItem item = new FruitItem();
        item.setItemCode(unique("T"));
        item.setItemName(name);
        item.setCategoryName(category);
        item.setUnit("份");
        item.setCostPrice(new BigDecimal(costPrice));
        item.setBasePrice(new BigDecimal(basePrice));
        item.setShelfLifeDays(shelfLifeDays);
        item.setLossRatePct(new BigDecimal("12.50"));
        item.setStatus("ON");
        item.setCreatedAt(LocalDateTime.now());
        item.setUpdatedAt(LocalDateTime.now());
        mapper.insert(item);
        return item;
    }

    /** 造批次：expire_date = arrive_date + shelf_life_days（与 docs/01 第 3 节第 1 条一致）。 */
    public static FruitBatch batch(FruitBatchMapper mapper, Long itemId, LocalDate arriveDate, int shelfLifeDays,
                                   String qty, String costPrice) {
        FruitBatch batch = new FruitBatch();
        batch.setItemId(itemId);
        batch.setBatchNo("TB" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        batch.setArriveDate(arriveDate);
        batch.setExpireDate(arriveDate.plusDays(shelfLifeDays));
        batch.setInitQty(new BigDecimal(qty));
        batch.setRemainQty(new BigDecimal(qty));
        batch.setCostPrice(new BigDecimal(costPrice));
        batch.setScrapQty(BigDecimal.ZERO);
        batch.setStatus("ON_SALE");
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        mapper.insert(batch);
        return batch;
    }

    public static Member member(MemberMapper mapper, String nickname, boolean pushEnabled) {
        Member member = new Member();
        member.setMemberNo(unique("M"));
        member.setNickname(nickname);
        member.setPhoneTail("1234");
        member.setJoinedDate(LocalDate.now());
        member.setPushEnabled(pushEnabled);
        member.setCreatedAt(LocalDateTime.now());
        mapper.insert(member);
        return member;
    }

    public static MemberPref pref(MemberPrefMapper mapper, Long memberId, String category, String score, int buyCount) {
        MemberPref pref = new MemberPref();
        pref.setMemberId(memberId);
        pref.setCategoryName(category);
        pref.setBuyCount(buyCount);
        pref.setLastBuyDate(LocalDate.now());
        pref.setScore(new BigDecimal(score));
        mapper.insert(pref);
        return pref;
    }

    public static TrafficHourly traffic(TrafficHourlyMapper mapper, LocalDate date, int hour,
                                        int orderCount, int customerCount, boolean isPeak) {
        TrafficHourly row = new TrafficHourly();
        row.setStatDate(date);
        row.setHourOfDay(hour);
        row.setOrderCount(orderCount);
        row.setCustomerCount(customerCount);
        row.setIsPeak(isPeak);
        mapper.insert(row);
        return row;
    }
}
