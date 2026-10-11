package com.fruit.shop.item;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fruit.shop.item.domain.FruitItem;
import com.fruit.shop.item.dto.ItemCreateRequest;
import com.fruit.shop.item.dto.ItemView;
import com.fruit.shop.item.mapper.FruitItemMapper;
import com.fruit.shop.support.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 商品档案（契约外补充：批次登记、定价建议都要先有单品）。 */
@Service
public class ItemService {

    private final FruitItemMapper itemMapper;

    public ItemService(FruitItemMapper itemMapper) {
        this.itemMapper = itemMapper;
    }

    public List<ItemView> list(String keyword, String categoryName) {
        List<FruitItem> items = itemMapper.selectList(Wrappers.<FruitItem>lambdaQuery()
                .like(keyword != null && !keyword.isBlank(), FruitItem::getItemName, keyword)
                .eq(categoryName != null && !categoryName.isBlank(), FruitItem::getCategoryName, categoryName)
                .orderByAsc(FruitItem::getId));
        return items.stream().map(ItemService::toView).toList();
    }

    /** 按 itemCode 幂等新增或更新。 */
    @Transactional
    public ItemView save(ItemCreateRequest request) {
        FruitItem existing = itemMapper.selectOne(Wrappers.<FruitItem>lambdaQuery()
                .eq(FruitItem::getItemCode, request.getItemCode().trim())
                .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now();
        FruitItem item = existing == null ? new FruitItem() : existing;
        item.setItemCode(request.getItemCode().trim());
        item.setItemName(request.getItemName().trim());
        item.setCategoryName(request.getCategoryName().trim());
        item.setUnit(request.getUnit() == null || request.getUnit().isBlank() ? "份" : request.getUnit().trim());
        item.setCostPrice(request.getCostPrice());
        item.setBasePrice(request.getBasePrice());
        item.setShelfLifeDays(request.getShelfLifeDays());
        item.setLossRatePct(request.getLossRatePct() == null ? BigDecimal.ZERO : request.getLossRatePct());
        item.setStatus(request.getStatus() == null || request.getStatus().isBlank() ? "ON" : request.getStatus().trim());
        item.setUpdatedAt(now);
        if (existing == null) {
            item.setCreatedAt(now);
            itemMapper.insert(item);
        } else {
            itemMapper.updateById(item);
        }
        return toView(item);
    }

    public FruitItem requireItem(Long itemId) {
        FruitItem item = itemMapper.selectById(itemId);
        if (item == null) {
            throw BusinessException.notFound("单品不存在：itemId=" + itemId);
        }
        return item;
    }

    static ItemView toView(FruitItem item) {
        return new ItemView(item.getId(), item.getItemCode(), item.getItemName(), item.getCategoryName(), item.getUnit(),
                item.getCostPrice(), item.getBasePrice(), item.getShelfLifeDays(), item.getLossRatePct(), item.getStatus());
    }
}
