"""把原始 Excel 附件转成 UTF-8 CSV（一次性、可重跑），避免每次分析都等 openpyxl 解析。

产出（全部在 data/interim/ 下，不改动 data/raw/）：
    product_info.csv        附件1 商品信息
    wholesale_price.csv     附件3 批发价
    loss_rate_category.csv  附件4 品类平均损耗率
    loss_rate_item.csv      附件4 单品损耗率
    sales_detail.csv        附件2 销售流水（约 88 万行，导出耗时较久）

用法:
    python scripts/prepare_raw_data.py            # 全部
    python scripts/prepare_raw_data.py --skip-sales
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
RAW = ROOT / "data" / "raw"
OUT = ROOT / "data" / "interim"


def _dump(df: pd.DataFrame, name: str) -> None:
    path = OUT / name
    df.to_csv(path, index=False, encoding="utf-8-sig")
    print(f"  -> {path.relative_to(ROOT)}  rows={len(df)} cols={list(df.columns)}")


def convert_product_info() -> None:
    print("[1/5] 附件1 商品信息")
    df = pd.read_excel(RAW / "attachment1_product_info.xlsx", sheet_name=0)
    df.columns = ["item_code", "item_name", "category_code", "category_name", "loss_rate_pct"]
    _dump(df, "product_info.csv")


def convert_wholesale() -> None:
    print("[2/5] 附件3 批发价")
    df = pd.read_excel(RAW / "attachment3_wholesale_price.xlsx", sheet_name=0)
    df.columns = ["sale_date", "item_code", "wholesale_price"]
    df["sale_date"] = pd.to_datetime(df["sale_date"]).dt.strftime("%Y-%m-%d")
    _dump(df, "wholesale_price.csv")


def convert_loss_rate() -> None:
    print("[3/5] 附件4 损耗率")
    xl = pd.ExcelFile(RAW / "attachment4_loss_rate.xlsx")
    cat_sheet = xl.sheet_names[0]
    df_cat = xl.parse(cat_sheet)
    df_cat.columns = ["category_code", "category_name", "avg_loss_rate_pct"]
    _dump(df_cat, "loss_rate_category.csv")

    item_sheet = xl.sheet_names[1]
    df_item = xl.parse(item_sheet)
    df_item.columns = ["item_code", "item_name", "loss_rate_pct"]
    _dump(df_item, "loss_rate_item.csv")


def convert_sales(limit: int | None = None) -> None:
    print("[4/5] 附件2 销售流水（大文件，请耐心等待）")
    t0 = time.time()
    df = pd.read_excel(RAW / "attachment2_sales_detail.xlsx", sheet_name=0)
    print(f"  read in {time.time() - t0:.1f}s, rows={len(df)}")
    df.columns = ["sale_date", "time_slot", "item_code", "quantity", "unit_price", "sale_type", "discount"]
    df["sale_date"] = pd.to_datetime(df["sale_date"]).dt.strftime("%Y-%m-%d")
    if limit:
        df = df.head(limit)
    _dump(df, "sales_detail.csv")


def summarize() -> None:
    print("[5/5] 概览")
    sales = pd.read_csv(OUT / "sales_detail.csv", encoding="utf-8-sig")
    print("  日期区间:", sales["sale_date"].min(), "->", sales["sale_date"].max())
    print("  单品数:", sales["item_code"].nunique())
    print("  销售类型:", sales["sale_type"].value_counts().to_dict())
    print("  折扣取值:", sorted(sales["discount"].unique())[:12])
    print("  时段数:", sales["time_slot"].nunique())


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--skip-sales", action="store_true")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    for f in ["attachment1_product_info.xlsx", "attachment2_sales_detail.xlsx",
              "attachment3_wholesale_price.xlsx", "attachment4_loss_rate.xlsx"]:
        if not (RAW / f).exists():
            print(f"缺少 {f}，请先下载（见 docs/01-技术方案.md 数据来源章节）", file=sys.stderr)
            return 2

    convert_product_info()
    convert_wholesale()
    convert_loss_rate()
    if args.skip_sales:
        print("[4/5] 跳过销售流水")
    else:
        convert_sales()
        summarize()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
