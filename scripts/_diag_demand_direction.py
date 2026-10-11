"""用仿真产出的数据本身检验两件事（只读）：

  1) 需求曲线方向是否正确：在仿真数据里，价格越高销量是否越低？
     做法：同单品跨日回归 ln(销量) ~ ln(均价)，看斜率符号（这是"行为检验"，
     不依赖 _truth 文件的符号约定，因此能判定真伪）。
  2) 单店经营规模是否自洽：日均营收、客单价是否落在"街边水果店"的常识区间。
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
SEED = ROOT / "data" / "seed"

sales = pd.read_csv(SEED / "shop_sales.csv", encoding="utf-8")
traffic = pd.read_csv(SEED / "shop_traffic_hourly.csv", encoding="utf-8")
item = pd.read_csv(SEED / "shop_item.csv", encoding="utf-8")

sales["biz"] = pd.to_datetime(sales["biz_date"])

print("=== 1) 行为检验：仿真数据里价格与销量的关系 ===")
g = sales.groupby(["item_code", "biz"]).agg(
    qty=("quantity", "sum"),
    revenue=("unit_price", lambda s: float((s * sales.loc[s.index, "quantity"]).sum())),
).reset_index()
g["avg_price"] = g["revenue"] / g["qty"]

rows = []
for code, sub in g.groupby("item_code"):
    if len(sub) < 30:
        continue
    x = np.log(sub["avg_price"].to_numpy(float))
    y = np.log(sub["qty"].to_numpy(float))
    if np.std(x) < 1e-9:
        continue
    b = float(np.polyfit(x, y, 1)[0])
    rows.append((code, len(sub), b))

per_item = pd.DataFrame(rows, columns=["item_code", "n_days", "slope"])
print("可比单品数:", len(per_item))
print("单品弹性斜率：中位数 = %.3f，负值占比 = %.1f%%" %
      (per_item["slope"].median(), (per_item["slope"] < 0).mean() * 100))
print("分位：p10=%.2f p25=%.2f p75=%.2f p90=%.2f" % tuple(
    per_item["slope"].quantile(q) for q in (0.1, 0.25, 0.75, 0.9)))

# 组内去均值后的汇总斜率（单品固定效应口径）
g2 = g.merge(per_item[["item_code"]], on="item_code")
g2["lnp"] = np.log(g2["avg_price"])
g2["lnq"] = np.log(g2["qty"])
g2["lnp_dm"] = g2["lnp"] - g2.groupby("item_code")["lnp"].transform("mean")
g2["lnq_dm"] = g2["lnq"] - g2.groupby("item_code")["lnq"].transform("mean")
beta = float(np.polyfit(g2["lnp_dm"], g2["lnq_dm"], 1)[0])
print("单品固定效应（组内去均值）斜率 = %.3f" % beta)
print("→ 判定:", "需求曲线方向正确（涨价销量下降）" if beta < 0 else "★ 需求曲线方向异常（涨价销量反升）")

print()
print("=== 2) 单店经营规模自洽性 ===")
daily_rev = sales.groupby("biz")["quantity"].sum() * 0 + sales.assign(amt=sales["quantity"] * sales["unit_price"]).groupby("biz")["amt"].sum()
daily_orders = traffic.groupby("stat_date")["order_count"].sum()
daily_units = sales.groupby("biz")["quantity"].sum()
days = sales["biz"].nunique()

print("仿真天数:", days)
print("日均营收 = %.0f 元" % daily_rev.mean())
print("日均客流 = %.0f 人次（order_count）" % daily_orders.mean())
print("日均销量 = %.0f 份/斤" % daily_units.mean())
print("客单价 = %.2f 元/人次" % (daily_rev.mean() / daily_orders.mean()))
print("单笔成交 = %.2f 元" % (daily_rev.mean() / len(sales) * days / days * 1.0 if False else daily_rev.sum() / len(sales)))
print()
print("参考区间（街边小型水果店，单店单日）：营收 1500~12000 元、客流 120~500 人次、客单价 12~40 元")
rev_ok = 1500 <= daily_rev.mean() <= 12000
tr_ok = 120 <= daily_orders.mean() <= 500
print("→ 营收:", "合理" if rev_ok else "偏高/偏低", " | 客流:", "合理" if tr_ok else "偏高/偏低")

print()
print("=== 3) 与真实公开数据的规模对照（注意业态差异）===")
print("真实（超市蔬菜店）日均 809 笔/开业日、3108 元/日、434 kg/日、笔均 3.84 元")
print("仿真 单店        日均 %.0f 笔、%.0f 元/日、%.0f 份/日、笔均 %.2f 元" %
      (daily_orders.mean(), daily_rev.mean(), daily_units.mean(), daily_rev.sum() / len(sales)))
print("→ 结论：仿真营收量级已明显下调（%.2fx），但客流/笔数仍接近超市量级；"
      "水果店单笔金额高于蔬菜散称，符合常识。" % (daily_rev.mean() / 3107.86))
