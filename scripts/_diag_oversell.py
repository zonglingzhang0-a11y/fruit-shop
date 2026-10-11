"""定位超卖是"按单品口径"还是"按批次归因"的问题。

做法：对每个 (单品, 交易日)，把该单品所有在售批次按 FEFO（先到期先出）排序，
逐日模拟一次扣减，看"当日销量"是否会超过"当日该单品全部可售余量"。
  - 若单品层面不超卖、只有批次层面超卖 → 销售归因/输出 bug（批次台账不可信）
  - 若单品层面也超卖 → 需求未受库存约束，仿真核心逻辑 bug（评测结论不可用）
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "data" / "seed"

batch = pd.read_csv(SEED / "shop_batch.csv", encoding="utf-8")
sales = pd.read_csv(SEED / "shop_sales.csv", encoding="utf-8")

batch["arrive"] = pd.to_datetime(batch["arrive_date"])
batch["expire"] = pd.to_datetime(batch["expire_date"])
sales["biz"] = pd.to_datetime(sales["biz_date"])

days = sorted(sales["biz"].unique())
items = sorted(batch["item_code"].unique())

# 单品层面的库存模拟：按 FEFO 扣减，检查是否超卖
item_days_oversold = []
batch_level_violation = 0

for code in items:
    batches = batch[batch["item_code"] == code].sort_values(["expire", "batch_no"]).copy()
    remain = dict(zip(batches["batch_no"], batches["init_qty"].astype(float)))
    scrap_acc = dict(zip(batches["batch_no"], batches["scrap_qty"].astype(float)))
    exp = dict(zip(batches["batch_no"], batches["expire"]))
    by_day = sales[sales["item_code"] == code].groupby("biz")["quantity"].sum()

    for day in days:
        # 先处理当天到期的报废（simulator 口径：到期未售即报废）
        for bn, e in exp.items():
            if e <= day and remain.get(bn, 0) > 0:
                remain[bn] = 0.0
        demand = float(by_day.get(day, 0.0))
        available = sum(v for v in remain.values() if v > 0)
        if demand > available + 0.01:
            item_days_oversold.append((code, day.date(), demand, available))
        # FEFO 扣减
        take = demand
        for bn in sorted(remain, key=lambda b: exp[b]):
            if take <= 0:
                break
            if remain[bn] <= 0:
                continue
            used = min(remain[bn], take)
            remain[bn] -= used
            take -= used

print("=== 单品×日 层面是否超卖（核心逻辑） ===")
print("超卖的单品×日 组合数:", len(item_days_oversold))
if item_days_oversold:
    df = pd.DataFrame(item_days_oversold, columns=["item_code", "biz_date", "demand", "available"])
    print(df.head(10).to_string())
    print("涉及单品数:", df["item_code"].nunique())
else:
    print("=> 单品层面不超卖：需求确实受库存约束，仿真核心逻辑没坏。")

print()
print("=== 批次台账层面的超卖（输出/归因） ===")
batch["oversold"] = batch["sold_qty"] if "sold_qty" in batch.columns else None
sold = sales.groupby("batch_no")["quantity"].sum().rename("sold_qty")
b = batch.set_index("batch_no").join(sold, how="left").fillna({"sold_qty": 0})
viol = b[b["sold_qty"] > b["init_qty"] + 0.01]
print("sold_qty > init_qty 的批次数:", len(viol), "/", len(b))
if len(viol):
    print(viol[["item_code", "arrive_date", "expire_date", "init_qty", "sold_qty", "scrap_qty", "remain_qty"]].head(10).to_string())
    print()
    print("这类批次的共同特征：")
    print("  - 保质期分布:", viol["init_qty"].notna().sum(), "条;  保质期=",
          sorted(set((pd.to_datetime(viol["expire_date"]) - pd.to_datetime(viol["arrive_date"])).dt.days)))
    print("  - 平均 init_qty: %.2f，平均超卖量: %.2f" %
          (viol["init_qty"].mean(), (viol["sold_qty"] - viol["init_qty"]).mean()))
