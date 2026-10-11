"""核对 data/interim 产物的字段口径，输出给文档用的事实数据。

用法: python scripts/profile_interim.py
"""
from __future__ import annotations

from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
INTERIM = ROOT / "data" / "interim"


def main() -> int:
    sales = pd.read_csv(INTERIM / "sales_detail.csv", encoding="utf-8-sig")
    product = pd.read_csv(INTERIM / "product_info.csv", encoding="utf-8-sig")
    whole = pd.read_csv(INTERIM / "wholesale_price.csv", encoding="utf-8-sig")
    loss = pd.read_csv(INTERIM / "loss_rate_category.csv", encoding="utf-8-sig")

    print("== sales_detail ==")
    print(sales.dtypes.to_string())
    print("rows:", len(sales))
    print("date range:", sales["sale_date"].min(), "->", sales["sale_date"].max())
    print("sale_type:", sales["sale_type"].value_counts().to_dict())
    print("discount values:", sales["discount"].unique()[:5])
    print("sample hour-like values:", sales["time_slot"].astype(str).str.slice(0, 5).unique()[:8])
    print("items in sales:", sales["item_code"].nunique())

    print("\n== product_info ==")
    print("rows:", len(product), "| categories:", product["category_name"].nunique())
    print(product.head(3).to_string())
    print("loss_rate_pct describe:")
    print(product["loss_rate_pct"].describe().to_string())

    print("\n== wholesale_price ==")
    print("rows:", len(whole), "| items:", whole["item_code"].nunique())
    print(whole.head(3).to_string())

    print("\n== loss_rate_category ==")
    print(loss.to_string())

    print("\n== coverage ==")
    sales_items = set(sales["item_code"].unique())
    print("sales items missing in product_info:", len(sales_items - set(product["item_code"])))
    print("product items never sold:", len(set(product["item_code"]) - sales_items))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
