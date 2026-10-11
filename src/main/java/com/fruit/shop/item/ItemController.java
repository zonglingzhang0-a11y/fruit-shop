package com.fruit.shop.item;

import com.fruit.shop.item.dto.ItemCreateRequest;
import com.fruit.shop.item.dto.ItemView;
import com.fruit.shop.support.ApiResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品档案接口（契约外补充，docs/01 第 5 节未列）。
 * 前端"登记入库"必须先选品，没有这个接口就只能靠种子 CSV，演示时没法手工加品。
 */
@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping
    public ApiResult<List<ItemView>> list(@RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String categoryName) {
        return ApiResult.ok(itemService.list(keyword, categoryName));
    }

    @PostMapping
    public ApiResult<ItemView> save(@Valid @RequestBody ItemCreateRequest request) {
        return ApiResult.ok(itemService.save(request));
    }
}
