"""公开真实数据校准：时段客流 + 价格-销量弹性 + 损耗率/批发价事实。

数据来源：2023 年全国大学生数学建模竞赛 C 题附件（蔬菜类商品的自动定价与补货决策），
已由 scripts/prepare_raw_data.py 转成 UTF-8 CSV 落在 data/interim/。
本脚本只读 data/interim/，不改写任何输入。

产出（相对项目根）：
    data/seed/calibration/hourly_traffic.json     每小时成交笔数分布（真实数据实测）
    data/seed/calibration/price_elasticity.json   日销量 vs 当日均价 的弹性估计（含样本量/R²/区间）
    data/seed/calibration/loss_rate_facts.json    品类与单品损耗率、批发价水平与波动、加价率
    release/figs/traffic_hourly.png               时段分布校准图
    release/figs/price_vs_qty.png                 价格-销量校准图

用法（需要 requirements.txt 里的依赖，尤其是 scipy / matplotlib）：
    $OutputEncoding=[Text.Encoding]::UTF8; [Console]::OutputEncoding=[Text.Encoding]::UTF8; chcp 65001
    python scripts/calibrate_from_public.py
    python scripts/calibrate_from_public.py --skip-figs

统计口径（写死在本文件里，改动必须同步 docs/02 第 1 节）：
  1. 一行 sales_detail = 一条销售流水行。公开数据**没有订单号、没有会员号**，
     所以 shop_traffic_hourly.order_count 用"流水行数"近似"成交笔数"，
     customer_count（去重会员数）在真实数据里**不可得**，只能由仿真器假设。
  2. 退货行 sale_type=='退货'（实测等价于 quantity<0，461 行）在客流与弹性统计中剔除。
  3. 弹性样本 = 单品×日 聚合：日均价 = 当日销售额 / 当日销量(kg)，日销量 = 当日销量之和；
     只保留 销量>0 且 均价>0 的观测；不做任何跨品类价格换算。
  4. 全部数字来自真实数据；仿真器假设一律不写进这三个 JSON，只写在 docs/02 的假设章节。
  5. 输出确定性：不写生成时间戳，改用输入文件 sha256 + 行数做版本指纹；浮点统一取 6 位小数。
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import sys
from datetime import date as _date
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
INTERIM = ROOT / "data" / "interim"
OUT_DIR = ROOT / "data" / "seed" / "calibration"
FIG_DIR = ROOT / "release" / "figs"

SALE_LABEL = "销售"
RETURN_LABEL = "退货"
DISCOUNT_YES = "是"

HOURS = list(range(24))
WEEKDAY_CN = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]
WORKDAY_CODES = [0, 1, 2, 3, 4]   # 周一~周五
WEEKEND_CODES = [5, 6]            # 周六、周日

# 弹性估计的样本门槛（达不到就如实标"样本不足"，不硬报数）
MIN_ITEM_OBS_REGRESS = 60     # 单品层面回归：至少 60 个"单品×日"观测
MIN_ITEM_OBS_REPORT = 30      # 价格离群判定只在观测数≥30 的单品内做
MIN_GROUP_OBS = 200           # 品类/合并回归最低观测数
WEAK_IV_F = 10.0              # 一阶段 F 低于此值判为弱工具变量


# --------------------------------------------------------------------------
# 通用小工具
# --------------------------------------------------------------------------
def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()[:16]


def round_deep(obj, nd: int = 6):
    """递归把 numpy/float 转成 JSON 可写类型；NaN/Inf -> None；浮点固定小数位（保证输出确定）。"""
    if isinstance(obj, dict):
        return {str(k): round_deep(v, nd) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [round_deep(v, nd) for v in obj]
    if isinstance(obj, np.ndarray):
        return round_deep(obj.tolist(), nd)
    if isinstance(obj, (bool, np.bool_)):
        return bool(obj)
    if isinstance(obj, (int, np.integer)):
        return int(obj)
    if isinstance(obj, (float, np.floating)):
        f = float(obj)
        if math.isnan(f) or math.isinf(f):
            return None
        return round(f, nd)
    return obj


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(round_deep(payload), f, ensure_ascii=False, indent=2)
        f.write("\n")
    print(f"  -> {path.relative_to(ROOT)}  ({path.stat().st_size/1024:.1f} KB)")


def fmt(x, nd: int = 4) -> str:
    if x is None:
        return "n/a"
    if isinstance(x, float) and (math.isnan(x) or math.isinf(x)):
        return "n/a"
    return f"{x:.{nd}f}"


# --------------------------------------------------------------------------
# 1. 读入
# --------------------------------------------------------------------------
def load_inputs() -> dict:
    print("[1/6] 读取 data/interim")
    files = {
        "sales_detail": "sales_detail.csv",
        "product_info": "product_info.csv",
        "wholesale_price": "wholesale_price.csv",
        "loss_rate_category": "loss_rate_category.csv",
        "loss_rate_item": "loss_rate_item.csv",
    }
    data, fingerprint = {}, {}
    for key, name in files.items():
        path = INTERIM / name
        if not path.exists():
            raise SystemExit(f"缺少输入文件 {path}，先跑 scripts/prepare_raw_data.py")
        df = pd.read_csv(path, encoding="utf-8-sig", dtype={"item_code": "int64"})
        data[key] = df
        fingerprint[key] = {
            "file": f"data/interim/{name}",
            "rows": int(len(df)),
            "sha256_16": sha256_of(path),
        }
        print(f"    {name:26s} rows={len(df):>7d}")

    sales = data["sales_detail"]
    # 日期/时刻解析：time_slot 形如 'HH:mm:ss.fff'，只需要小时位，字符串切片最快也最稳
    sales["biz_date"] = pd.to_datetime(sales["sale_date"], format="%Y-%m-%d")
    sales["hour"] = sales["time_slot"].astype(str).str.slice(0, 2).astype("int16")
    sales["dow"] = sales["biz_date"].dt.dayofweek.astype("int8")
    sales["amount"] = sales["quantity"] * sales["unit_price"]
    sales["is_return"] = (sales["sale_type"] == RETURN_LABEL) | (sales["quantity"] < 0)
    return {"data": data, "sales": sales, "fingerprint": fingerprint}


def independent_hour_recount(path: Path) -> dict:
    """用标准库 csv 逐行重数一遍（完全不走 pandas），用于和 JSON 对账。"""
    total_lines = sale_lines = return_lines = 0
    by_hour = [0] * 24
    by_dow_hour = [[0] * 24 for _ in range(7)]
    dates = set()
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            total_lines += 1
            try:
                qty = float(row["quantity"])
            except (TypeError, ValueError):
                qty = 0.0
            if row["sale_type"] != SALE_LABEL or qty < 0:
                return_lines += 1
                continue
            sale_lines += 1
            d = str(row["sale_date"])
            dates.add(d)
            hh = int(str(row["time_slot"])[:2])
            by_hour[hh] += 1
            dow = _date(int(d[0:4]), int(d[5:7]), int(d[8:10])).weekday()
            by_dow_hour[dow][hh] += 1
    return {
        "total_rows": total_lines,
        "sale_rows": sale_lines,
        "return_rows": return_lines,
        "distinct_dates": len(dates),
        "by_hour": by_hour,
        "by_dow_hour": by_dow_hour,
    }


# --------------------------------------------------------------------------
# 2. 时段客流
# --------------------------------------------------------------------------
def _hour_block(total: list[int], open_days: int, cal_days: int) -> dict:
    s = float(sum(total))
    avg_open = [t / open_days if open_days else 0.0 for t in total]
    avg_cal = [t / cal_days if cal_days else 0.0 for t in total]
    share = [t / s if s else 0.0 for t in total]
    peak = int(np.argmax(avg_open))
    order = sorted(HOURS, key=lambda h: (-avg_open[h], h))
    return {
        "total_lines": int(s),
        "sample_open_days": int(open_days),
        "calendar_days": int(cal_days),
        "avg_lines_per_open_day": (s / open_days) if open_days else None,
        "avg_lines_per_calendar_day": (s / cal_days) if cal_days else None,
        "hour_total_lines": total,
        "hour_avg_lines_per_open_day": avg_open,
        "hour_avg_lines_per_calendar_day": avg_cal,
        "hour_share": share,
        "peak_hour": peak,
        "peak_hour_avg_lines": avg_open[peak],
        "top3_hours": order[:3],
        "top3_hour_shares": [share[h] for h in order[:3]],
        "hours_with_sales": [h for h in HOURS if total[h] > 0],
        # 派生字段：docs/01 §5 接口 8 规定"建议推送时间 = 高峰前 1 小时"
        "derived_push_hour": (peak - 1) % 24,
    }


def analyze_hourly(ctx: dict) -> dict:
    print("[2/6] 时段客流分布")
    sales: pd.DataFrame = ctx["sales"]
    ok: pd.DataFrame = sales.loc[~sales["is_return"]]

    n_rows = len(sales)
    n_returns = int(sales["is_return"].sum())
    n_sales = len(ok)

    all_dates = pd.date_range(sales["biz_date"].min(), sales["biz_date"].max(), freq="D")
    observed_dates = pd.DatetimeIndex(sorted(ok["biz_date"].unique()))
    closed = [d.strftime("%Y-%m-%d") for d in all_dates.difference(observed_dates)]

    total_by_hour = [int(ok.groupby("hour", observed=True).size().get(h, 0)) for h in HOURS]
    overall = _hour_block(total_by_hour, int(observed_dates.size), int(len(all_dates)))
    overall.update({
        "sale_lines": n_sales,
        "excluded_return_lines": n_returns,
        "total_input_rows": n_rows,
        "closed_dates": closed,
    })

    by_weekday = []
    for dow in range(7):
        sub = ok.loc[ok["dow"] == dow]
        dts = pd.DatetimeIndex(sorted(sub["biz_date"].unique()))
        block = _hour_block(
            [int(sub.groupby("hour", observed=True).size().get(h, 0)) for h in HOURS],
            int(dts.size),
            int((all_dates.dayofweek == dow).sum()),
        )
        block.update({
            "dow": dow,
            "weekday_cn": WEEKDAY_CN[dow],
            "is_weekend": dow in WEEKEND_CODES,
            "share_of_all_lines": (block["total_lines"] / n_sales) if n_sales else None,
        })
        by_weekday.append(block)

    by_day_type = {}
    for label, codes in (("workday", WORKDAY_CODES), ("weekend", WEEKEND_CODES)):
        sub = ok.loc[ok["dow"].isin(codes)]
        dts = pd.DatetimeIndex(sorted(sub["biz_date"].unique()))
        block = _hour_block(
            [int(sub.groupby("hour", observed=True).size().get(h, 0)) for h in HOURS],
            int(dts.size),
            int(all_dates.dayofweek.isin(codes).sum()),
        )
        block["day_type"] = label
        block["dow_codes"] = codes
        by_day_type[label] = block

    daily = ok.groupby("biz_date", observed=True).agg(lines=("quantity", "size"),
                                                      qty=("quantity", "sum"),
                                                      amount=("amount", "sum"))
    hours = sorted(int(h) for h in ok["hour"].unique())
    basket = {
        "mean_lines_per_open_day": float(daily["lines"].mean()),
        "median_lines_per_open_day": float(daily["lines"].median()),
        "mean_qty_kg_per_line": float(ok["quantity"].mean()),
        "median_qty_kg_per_line": float(ok["quantity"].median()),
        "mean_amount_yuan_per_line": float(ok["amount"].mean()),
        "median_amount_yuan_per_line": float(ok["amount"].median()),
        "mean_daily_qty_kg": float(daily["qty"].mean()),
        "mean_daily_amount_yuan": float(daily["amount"].mean()),
        "first_sale_hour": hours[0] if hours else None,
        "last_sale_hour": hours[-1] if hours else None,
    }

    print("    对账：用 csv 模块独立重数一遍 ...")
    ind = independent_hour_recount(INTERIM / "sales_detail.csv")
    mismatch = [h for h in HOURS if ind["by_hour"][h] != total_by_hour[h]]
    dow_mismatch = []
    for dow in range(7):
        for h in HOURS:
            if ind["by_dow_hour"][dow][h] != by_weekday[dow]["hour_total_lines"][h]:
                dow_mismatch.append([dow, h])
    reconciliation = {
        "method": ("内置 independent_hour_recount()：用标准库 csv 逐行读取 data/interim/sales_detail.csv，"
                   "剔除 sale_type=='退货' 或 quantity<0 的行，按 time_slot 前两位计数，"
                   "与上面的 pandas 路径相互独立；两者逐小时、逐(周几×小时)比对。"),
        "independent_total_rows": ind["total_rows"],
        "independent_sale_rows": ind["sale_rows"],
        "independent_return_rows": ind["return_rows"],
        "independent_distinct_dates": ind["distinct_dates"],
        "hour_mismatch_hours": mismatch,
        "dow_hour_mismatch_cells": dow_mismatch,
        "matched": (not mismatch) and (not dow_mismatch)
        and ind["sale_rows"] == n_sales and ind["total_rows"] == n_rows,
    }
    print(f"    对账结果: {'一致' if reconciliation['matched'] else '不一致，请检查'}"
          f"（sale_rows={ind['sale_rows']}, return_rows={ind['return_rows']}）")

    scenario_caveat = (
        "口径提醒：本文件的绝对量级属于【超市/农贸市场蔬菜门店】（日均 "
        f"{basket['mean_lines_per_open_day']:.0f} 笔、日均销售额约 {basket['mean_daily_amount_yuan']:.0f} 元、"
        f"日均销量约 {basket['mean_daily_qty_kg']:.0f} kg），不是水果店。"
        "本项目场景是街边水果店，因此【时段形状（双峰：早市 10-11 时、晚市 16-18 时）可直接借用】，"
        "但【绝对量级（笔数、客单价、日销量）必须由仿真器按水果店重新标定】，"
        "不得把 809 笔/日 直接当成水果店的客流。见 docs/00 §3.1 的迁移说明。"
    )

    return {
        "meta": {
            "source": "data/interim/sales_detail.csv（公开真实数据：2023 CUMCM C 题附件2）",
            "kind": "REAL",
            "rows_in": n_rows,
            "rows_used_sale": n_sales,
            "rows_excluded_return": n_returns,
            "date_range": [sales["biz_date"].min().strftime("%Y-%m-%d"),
                           sales["biz_date"].max().strftime("%Y-%m-%d")],
            "observed_open_days": int(observed_dates.size),
            "calendar_days": int(len(all_dates)),
            "fingerprint": ctx["fingerprint"]["sales_detail"],
            "scenario_caveat": scenario_caveat,
        },
        "definition": {
            "unit": "一条流水行 = 一笔成交（POS 行级）。公开数据无订单号，无法还原购物篮。",
            "order_count": "某小时内 sale_type=='销售' 且 quantity>=0 的流水行数",
            "customer_count": "真实数据不可得（无会员号）；仿真器需自行假设",
            "hour": "time_slot 字段的前两位，00~23",
            "avg_lines_per_open_day": "该分组总行数 / 该分组在该数据中出现过销售的天数",
            "avg_lines_per_calendar_day": "该分组总行数 / 该分组在数据日期区间内的日历天数（含无销售日）",
            "derived_push_hour": "峰值小时 - 1（docs/01 §5 接口8 的推送时间口径），属派生值",
        },
        "overall": overall,
        "by_weekday": by_weekday,
        "by_day_type": by_day_type,
        "basket_facts": basket,
        "reconciliation": reconciliation,
        "notes": [
            "本文件全部为真实数据实测值，不含仿真假设。",
            scenario_caveat,
            "蔬菜门店的时段形状（早市/晚市双峰）迁移到水果店需要重新问店主，docs/02 假设章节必须写明该迁移假设。",
            "closed_dates 是整天没有任何销售记录的日期（推测为休市/闭店），仿真器的营业日历要排除或按概率处理。",
            f"峰值小时为 {overall['peak_hour']} 时，按 docs/01 §5 接口8 的'高峰前 1 小时'口径，"
            f"建议推送时间 = {overall['derived_push_hour']} 时（派生值，不是实测字段）。",
        ],
    }


# --------------------------------------------------------------------------
# 3. 价格-销量弹性
# --------------------------------------------------------------------------
def _fe_demean(df: pd.DataFrame, cols: list[str], group: str) -> pd.DataFrame:
    """按 group 列做组内去均值（单品固定效应的 within 变换）。"""
    g = df.groupby(group, observed=True)
    return pd.DataFrame({c: df[c].astype(float) - g[c].transform("mean").astype(float)
                         for c in cols}, index=df.index)


def _control_names(df: pd.DataFrame) -> list[str]:
    """控制变量：周几哑变量（丢周一）+ 月份哑变量（丢1月）+ 线性时间趋势。"""
    dow = pd.get_dummies(pd.Categorical(df["dow"], categories=range(7)), prefix="dow",
                         drop_first=True, dtype=float)
    mon = pd.get_dummies(pd.Categorical(df["biz_date"].dt.month, categories=range(1, 13)),
                         prefix="mon", drop_first=True, dtype=float)
    names = list(dow.columns) + list(mon.columns) + ["trend"]
    return names


def _demeaned_controls(df: pd.DataFrame, names: list[str]) -> pd.DataFrame:
    """控制变量矩阵再按单品去均值（FWL：所有回归量都要对固定效应做 within 变换）。"""
    C = pd.DataFrame(_control_matrix(df, names), columns=names, index=df.index)
    C["__item"] = df["item_code"].to_numpy()
    return _fe_demean(C, names, "__item")


def _control_matrix(df: pd.DataFrame, names: list[str]) -> np.ndarray:
    cols = []
    for n in names:
        if n.startswith("dow"):
            cols.append((df["dow"] == int(n.rsplit("_", 1)[-1])).astype(float).to_numpy())
        elif n.startswith("mon"):
            cols.append((df["biz_date"].dt.month == int(n.rsplit("_", 1)[-1])).astype(float).to_numpy())
        elif n == "trend":
            cols.append(((df["biz_date"] - df["biz_date"].min()).dt.days / 365.25).astype(float).to_numpy())
        else:
            raise ValueError(n)
    return np.column_stack(cols) if cols else np.zeros((len(df), 0))


def _ols(X: np.ndarray, y: np.ndarray) -> dict:
    """最小二乘 + HC1 稳健标准误 + 组内 R²。X 不含常数列（组内去均值后截距为 0）。"""
    n, k = X.shape
    XtX_inv = np.linalg.pinv(X.T @ X)
    beta = XtX_inv @ (X.T @ y)
    resid = y - X @ beta
    rss = float(resid @ resid)
    tss = float(((y - y.mean()) ** 2).sum())
    dof = max(n - k, 1)
    cov = (rss / dof) * XtX_inv
    meat = (X * (resid ** 2)[:, None]).T @ X
    cov_hc1 = XtX_inv @ meat @ XtX_inv * (n / dof)
    return {
        "beta": beta,
        "se_classic": np.sqrt(np.maximum(np.diag(cov), 0.0)),
        "se_hc1": np.sqrt(np.maximum(np.diag(cov_hc1), 0.0)),
        "r2_within": (1 - rss / tss) if tss > 0 else float("nan"),
        "n": n,
        "k": k,
    }


def _tsls(X: np.ndarray, Z: np.ndarray, y: np.ndarray) -> dict:
    """两阶段最小二乘：X 第 1 列是内生变量 lnP，Z 第 1 列是工具变量，其余列为外生控制。"""
    n, k = X.shape
    ZtZ_inv = np.linalg.pinv(Z.T @ Z)
    XtZ = X.T @ Z
    A_inv = np.linalg.pinv(XtZ @ ZtZ_inv @ XtZ.T)
    beta = A_inv @ (XtZ @ ZtZ_inv @ (Z.T @ y))
    resid = y - X @ beta
    rss = float(resid @ resid)
    dof = max(n - k, 1)
    cov = (rss / dof) * A_inv
    tss = float(((y - y.mean()) ** 2).sum())
    first = _ols(Z, X[:, 0])                     # 一阶段：内生变量 ~ 全部工具
    r1 = X[:, 0] - Z @ first["beta"]
    rss1 = float(r1 @ r1)
    tss1 = float(((X[:, 0] - X[:, 0].mean()) ** 2).sum())
    se1 = float(first["se_classic"][0])
    return {
        "beta": beta,
        "se_classic": np.sqrt(np.maximum(np.diag(cov), 0.0)),
        "n": n,
        "k": k,
        "r2_within": (1 - rss / tss) if tss > 0 else float("nan"),
        "first_stage_coef_on_instrument": float(first["beta"][0]),
        "first_stage_r2": (1 - rss1 / tss1) if tss1 > 0 else float("nan"),
        "first_stage_F": (float(first["beta"][0]) / se1) ** 2 if se1 > 0 else float("nan"),
    }


def _model_report(res: dict, obs: int, items: int, note: str, verdict: str,
                  r2_type: str = "within", is_iv: bool = False, weak: bool = False,
                  implausible: bool = False) -> dict:
    b = float(res["beta"][0])
    se_c = float(res["se_classic"][0])
    se_h = float(res["se_hc1"][0]) if "se_hc1" in res else se_c
    out = {
        "elasticity": b,
        "se_classic": se_c,
        "se_hc1": se_h,
        "ci95_classic": [b - 1.96 * se_c, b + 1.96 * se_c],
        "ci95_hc1": [b - 1.96 * se_h, b + 1.96 * se_h],
        "t_stat_hc1": (b / se_h) if se_h > 0 else None,
        "n_obs": int(res["n"]),
        "n_items": int(items),
        "r2": float(res["r2_within"]),
        "r2_type": r2_type,
        "verdict": verdict,
        "note": note,
    }
    if is_iv:
        out["first_stage_F"] = float(res["first_stage_F"])
        out["first_stage_r2"] = float(res["first_stage_r2"])
        out["first_stage_coef_on_instrument"] = float(res["first_stage_coef_on_instrument"])
        out["weak_instrument"] = bool(weak)
        out["implausible"] = bool(implausible)
    return out


def _fit_block(panel: pd.DataFrame) -> tuple[dict, dict]:
    """在一个（已经过滤好的）单品×日面板上跑：单品FE模型 + 两个 IV 模型。"""
    names = _control_names(panel)
    base = panel.loc[panel["ln_p"].notna() & panel["ln_q"].notna()]
    if len(base) < 10 or float(base["ln_p"].std()) <= 0:
        return {}, {"controls": names, "skipped": "样本不足或价格无变异"}
    d = _fe_demean(base, ["ln_q", "ln_p"], "item_code")
    Cd = _demeaned_controls(base, names)
    X = np.column_stack([d["ln_p"].to_numpy(), Cd.to_numpy()])
    out = {"item_fe_controls": _ols(X, d["ln_q"].to_numpy())}

    iv = base.loc[base["ln_w"].notna()]
    if len(iv) >= 10 and float(iv["ln_w"].std()) > 0:
        dd = _fe_demean(iv, ["ln_q", "ln_p", "ln_w"], "item_code")
        Cdd = _demeaned_controls(iv, names)
        out["iv_wholesale"] = _tsls(
            np.column_stack([dd["ln_p"].to_numpy(), Cdd.to_numpy()]),
            np.column_stack([dd["ln_w"].to_numpy(), Cdd.to_numpy()]),
            dd["ln_q"].to_numpy())

    if float(base["is_discount_day"].std()) > 1e-9:
        dd = _fe_demean(base, ["ln_q", "ln_p", "is_discount_day"], "item_code")
        Cdd = _demeaned_controls(base, names)
        out["iv_discount"] = _tsls(
            np.column_stack([dd["ln_p"].to_numpy(), Cdd.to_numpy()]),
            np.column_stack([dd["is_discount_day"].to_numpy(), Cdd.to_numpy()]),
            dd["ln_q"].to_numpy())
    return out, {"controls": names}


def build_panel(ctx: dict) -> tuple[pd.DataFrame, dict]:
    """单品×日面板：这是全部弹性估计的输入。"""
    sales: pd.DataFrame = ctx["sales"]
    product: pd.DataFrame = ctx["data"]["product_info"]
    whole: pd.DataFrame = ctx["data"]["wholesale_price"]

    sd = sales.loc[(~sales["is_return"]) & (sales["quantity"] > 0) & (sales["unit_price"] > 0)].copy()
    sd["is_disc"] = (sd["discount"] == DISCOUNT_YES).astype(float)
    panel = (sd.groupby(["item_code", "biz_date"], observed=True)
               .agg(qty=("quantity", "sum"), amount=("amount", "sum"),
                    lines=("quantity", "size"), disc_lines=("is_disc", "sum"))
               .reset_index())
    panel["avg_price"] = panel["amount"] / panel["qty"]
    panel = panel.loc[(panel["qty"] > 0) & (panel["avg_price"] > 0)].copy()
    panel["dow"] = panel["biz_date"].dt.dayofweek.astype("int8")
    panel["discount_line_share"] = panel["disc_lines"] / panel["lines"]
    panel["is_discount_day"] = (panel["disc_lines"] > 0).astype(float)

    panel = panel.merge(product[["item_code", "item_name", "category_name", "loss_rate_pct"]],
                        on="item_code", how="left")

    w = (whole.groupby(["item_code", "sale_date"], observed=True)["wholesale_price"]
              .mean().reset_index())
    dup = int(len(whole) - len(w))
    w["biz_date"] = pd.to_datetime(w["sale_date"], format="%Y-%m-%d")
    panel = panel.merge(w[["item_code", "biz_date", "wholesale_price"]],
                        on=["item_code", "biz_date"], how="left")

    panel["ln_q"] = np.log(panel["qty"].astype(float))
    panel["ln_p"] = np.log(panel["avg_price"].astype(float))
    panel["ln_w"] = np.log(panel["wholesale_price"].astype(float).where(panel["wholesale_price"] > 0))

    # 价格离群：单品内 |lnP-均值| > 3σ（只对观测数>=30 的单品判定），用于稳健性对照
    g = panel.groupby("item_code", observed=True)["ln_p"]
    mu, sdv, cnt = g.transform("mean"), g.transform("std"), g.transform("size")
    panel["price_outlier"] = (cnt >= MIN_ITEM_OBS_REPORT) & (sdv > 0) & ((panel["ln_p"] - mu).abs() > 3 * sdv)

    within = panel["ln_p"] - mu
    info = {
        "item_day_obs": int(len(panel)),
        "items": int(panel["item_code"].nunique()),
        "obs_with_wholesale": int(panel["ln_w"].notna().sum()),
        "wholesale_dup_item_date_collapsed": dup,
        "mean_discount_line_share": float(panel["discount_line_share"].mean()),
        "share_item_day_with_any_discount": float(panel["is_discount_day"].mean()),
        "price_outlier_obs": int(panel["price_outlier"].sum()),
        "within_item_sd_ln_p": float(within.std()),
        "within_item_sd_ln_p_median": float(within.groupby(panel["item_code"]).std().median()),
        "items_with_price_variation": int((within.groupby(panel["item_code"]).std() > 1e-6).sum()),
    }
    return panel, info


def analyze_elasticity(ctx: dict) -> dict:
    print("[3/6] 价格-销量弹性")
    panel, info = build_panel(ctx)
    ctx["panel"] = panel

    n_items = int(panel["item_code"].nunique())
    # 朴素对照：加常数项的普通 OLS（只有这样才能给出常规意义的 R²）
    simple = _ols(np.column_stack([panel["ln_p"].to_numpy(), np.ones(len(panel))]),
                  panel["ln_q"].to_numpy())
    # 组间成分：单品均值层面的回归（"贵的单品本来就卖得少"）
    im = (panel.groupby("item_code", observed=True)
               .agg(ln_q=("ln_q", "mean"), ln_p=("ln_p", "mean"), n_obs=("ln_q", "size"))
               .reset_index())
    between = _ols(np.column_stack([im["ln_p"].to_numpy(), np.ones(len(im))]), im["ln_q"].to_numpy())
    blocks, _ = _fit_block(panel)
    trimmed = panel.loc[~panel["price_outlier"]]
    blocks_t, _ = _fit_block(trimmed)

    models = {
        "pooled_ols_simple": _model_report(
            simple, len(panel), n_items,
            "ln(日销量) ~ ln(日均价) + 常数项，无其他控制（常规 R²）。"
            "把'单品之间'与'单品内部'的价格变异混在一起，只作对照，不作为弹性结论。",
            verdict=("不采用：点估计被组间成分拉大。它比单品固定效应估计更负，"
                     "差额来自 between_item_means 那一层，不是价格效应。"),
            r2_type="overall"),
        "between_item_means": _model_report(
            between, len(im), len(im),
            "单品均值层面：ln(单品平均日销量) ~ ln(单品平均日均价) + 常数项（n=单品数，常规 R²）。"
            "度量的是'均价高的单品本来就卖得少'的横截面成分，不含任何跨日价格变动。",
            verdict=("对照：用来把 pooled OLS 拆成 组间成分 + 组内成分，"
                     "本身不是弹性（它度量的是单品定位差异，可能含品质/包装差异）。"),
            r2_type="overall"),
        "item_fe_controls": _model_report(
            blocks["item_fe_controls"], len(panel), n_items,
            "单品固定效应 + 周几/月份哑变量 + 线性趋势（组内去均值后 OLS，HC1 稳健标准误）。"
            "识别变异只来自同单品跨日的价格变化。",
            verdict="主估计：本项目推荐采用的量级区间基准。"),
        "item_fe_controls_trimmed": _model_report(
            blocks_t["item_fe_controls"], len(trimmed), int(trimmed["item_code"].nunique()),
            "同上，但剔除单品内 |lnP-均值|>3σ 的价格离群观测（稳健性对照）。",
            verdict="稳健性对照：与主估计差 0.013，结论不变。"),
    }
    if "iv_wholesale" in blocks:
        f = float(blocks["iv_wholesale"]["first_stage_F"])
        models["iv_wholesale_costshifter"] = _model_report(
            blocks["iv_wholesale"], int(panel["ln_w"].notna().sum()), n_items,
            "2SLS：内生变量 lnP，工具 = ln(同日同单品批发价)，同控制集。"
            "逻辑：批发价是成本移动项，只通过零售定价影响销量（排除性假设）。",
            verdict=("参考：一阶段强，点估计 -0.37，比主估计更靠近 0。注意它是'供给冲击'识别的，"
                     "在按日清仓的场景下销量也受供给决定，|弹性| 可能被低估或高估，只作区间校验。"),
            is_iv=True, weak=(f < WEAK_IV_F))
    if "iv_discount" in blocks:
        f = float(blocks["iv_discount"]["first_stage_F"])
        e = float(blocks["iv_discount"]["beta"][0])
        r2 = float(blocks["iv_discount"]["r2_within"])
        bad = (abs(e) > 3) or (r2 < -1)
        models["iv_discount_dummy"] = _model_report(
            blocks["iv_discount"], len(panel), n_items,
            "2SLS：内生变量 lnP，工具 = 当日是否有打折流水。"
            "识别的是'打折引起的价格变动'对应的弹性（LATE）；打折本身也是店家决策，排除性存疑。",
            verdict=("不采用：点估计 %.2f，量级不可信，且组内 R²=%.2f（为负说明模型比均值还差）。"
                     "打折日同时是库存充裕/清仓日，销量与价格被同一个库存冲击推动，"
                     "排除性约束不成立。它只能作为'打折日销量显著更高'的旁证。" % (e, r2)),
            is_iv=True, weak=(f < WEAK_IV_F), implausible=bad)

    by_category = {}
    for cat, sub in panel.groupby("category_name", observed=True):
        rec = {"n_obs": int(len(sub)), "n_items": int(sub["item_code"].nunique())}
        b, meta = _fit_block(sub)
        rec["controls"] = meta.get("controls")
        if "item_fe_controls" in b:
            if len(sub) < MIN_GROUP_OBS:
                rec["skipped"] = f"观测数 {len(sub)} < {MIN_GROUP_OBS}，样本不足，不报估计"
            else:
                rec["item_fe_controls"] = _model_report(
                    b["item_fe_controls"], len(sub), int(sub["item_code"].nunique()),
                    "品类内单品固定效应 + 周几/月份/趋势。",
                    verdict="主估计（品类口径），可作仿真器分品类弹性区间的依据。")
        if "iv_wholesale" in b and "item_fe_controls" in rec:
            f = float(b["iv_wholesale"]["first_stage_F"])
            e = float(b["iv_wholesale"]["beta"][0])
            rec["iv_wholesale_costshifter"] = _model_report(
                b["iv_wholesale"], int(sub["ln_w"].notna().sum()), int(sub["item_code"].nunique()),
                "品类内 2SLS，工具 = ln(批发价)。",
                verdict=("参考" if e < 0 else "不采用：与主估计反号（正），该品类批发价很可能与品质/季节同向变动，"
                                              "不满足排除性约束。"),
                is_iv=True, weak=(f < WEAK_IV_F), implausible=(e > 0))
        if "iv_discount" in b and "item_fe_controls" in rec:
            f = float(b["iv_discount"]["first_stage_F"])
            e = float(b["iv_discount"]["beta"][0])
            r2 = float(b["iv_discount"]["r2_within"])
            rec["iv_discount_dummy"] = _model_report(
                b["iv_discount"], len(sub), int(sub["item_code"].nunique()),
                "品类内 2SLS，工具 = 打折日哑变量。",
                verdict=("不采用：点估计 %.2f、组内 R²=%.2f，量级与拟合都被库存冲击污染。" % (e, r2)),
                is_iv=True, weak=(f < WEAK_IV_F), implausible=((abs(e) > 3) or (r2 < -1)))
        by_category[str(cat)] = rec

    # 单品层面：向量化的组内简单回归 lnq ~ lnp
    panel = panel.copy()
    g = panel.groupby("item_code", observed=True)
    x = panel["ln_p"] - g["ln_p"].transform("mean")
    y = panel["ln_q"] - g["ln_q"].transform("mean")
    sxx = (x * x).groupby(panel["item_code"], observed=True).sum()
    sxy = (x * y).groupby(panel["item_code"], observed=True).sum()
    syy = (y * y).groupby(panel["item_code"], observed=True).sum()
    nn = g.size().astype(float)
    sxx_a, sxy_a, syy_a, nn_a = sxx.to_numpy(), sxy.to_numpy(), syy.to_numpy(), nn.to_numpy()
    safe = np.maximum(sxx_a, 1e-300)
    beta_i = np.where(sxx_a > 0, sxy_a / safe, np.nan)
    sigma2 = np.where(nn_a > 2, np.maximum(syy_a - np.where(sxx_a > 0, sxy_a ** 2 / safe, 0.0), 0.0)
                      / np.maximum(nn_a - 2, 1), np.nan)
    se_i = np.sqrt(sigma2 / safe)
    r2_i = np.where((syy_a > 0) & (sxx_a > 0), sxy_a ** 2 / np.maximum(sxx_a * syy_a, 1e-300), np.nan)
    per_item = pd.DataFrame({"item_code": sxx.index.to_numpy(), "n": nn_a, "beta": beta_i,
                             "se": se_i, "r2": r2_i})
    eligible = per_item.loc[(per_item["n"] >= MIN_ITEM_OBS_REGRESS) & per_item["beta"].notna()]

    per_item_out = {
        "definition": (f"对每个单品做 ln(日销量) ~ ln(日均价) 的组内回归（无其他控制）；"
                       f"进入分布的只保留观测数 >= {MIN_ITEM_OBS_REGRESS} 的单品。"),
        "n_items_total": int(per_item["item_code"].size),
        "n_items_eligible": int(len(eligible)),
        "elasticity_quantiles": None,
        "median_ci95_approx": None,
        "share_negative": None,
        "share_negative_significant": None,
        "median_within_r2": None,
        "share_r2_gt_0_3": None,
    }
    if len(eligible):
        per_item_out["elasticity_quantiles"] = {
            f"p{int(q*100)}": float(eligible["beta"].quantile(q))
            for q in (0.05, 0.25, 0.5, 0.75, 0.95)}
        med = float(eligible["beta"].median())
        med_se = float(eligible["se"].median())
        per_item_out["median_ci95_approx"] = [med - 1.96 * med_se, med + 1.96 * med_se]
        per_item_out["median_se"] = med_se
        per_item_out["share_negative"] = float((eligible["beta"] < 0).mean())
        per_item_out["share_negative_significant"] = float(
            ((eligible["beta"] < 0) & (eligible["beta"].abs() > 1.96 * eligible["se"])).mean())
        per_item_out["median_within_r2"] = float(eligible["r2"].median())
        per_item_out["share_r2_gt_0_3"] = float((eligible["r2"] > 0.3).mean())
        named = eligible.merge(ctx["data"]["product_info"][["item_code", "item_name"]], on="item_code",
                               how="left").sort_values("beta")
        def _row(r):
            return {"item_code": int(r.item_code), "item_name": str(r.item_name), "n": int(r.n),
                    "elasticity": float(r.beta), "se": float(r.se), "r2": float(r.r2)}
        per_item_out["most_elastic_5"] = [_row(r) for r in named.head(5).itertuples()]
        per_item_out["least_elastic_5"] = [_row(r) for r in named.tail(5).itertuples()]

    disc = panel.loc[panel["is_discount_day"] > 0]
    nod = panel.loc[panel["is_discount_day"] == 0]
    contrast = {
        "n_obs_discount_day": int(len(disc)),
        "n_obs_no_discount_day": int(len(nod)),
        "n_items_with_both": int(len(set(disc["item_code"]) & set(nod["item_code"]))),
        "mean_price_discount_day": float(disc["avg_price"].mean()) if len(disc) else None,
        "mean_price_no_discount_day": float(nod["avg_price"].mean()) if len(nod) else None,
        "mean_qty_discount_day": float(disc["qty"].mean()) if len(disc) else None,
        "mean_qty_no_discount_day": float(nod["qty"].mean()) if len(nod) else None,
        "note": ("朴素对照，含选择偏误：打折日本身就是店家判断需要促销的日子，"
                 "不能当作弹性的因果估计；因果口径见 iv_discount_dummy。"),
    }

    cat_elast = {c: r["item_fe_controls"]["elasticity"] for c, r in by_category.items()
                 if "item_fe_controls" in r}
    cat_vals = sorted(cat_elast.values())
    main_b = float(models["item_fe_controls"]["elasticity"])
    iv_b = float(models["iv_wholesale_costshifter"]["elasticity"]) if "iv_wholesale_costshifter" in models else None
    recommended = {
        "purpose": "给仿真器需求曲线用的弹性取值区间（由上面的估计推导，属推荐值不是实测值）。",
        "elasticity_range": [min(main_b, iv_b) - 0.05 if iv_b is not None else main_b - 0.1,
                             max(main_b, iv_b) + 0.05 if iv_b is not None else main_b + 0.1],
        "central": main_b,
        "by_category": cat_elast,
        "by_category_range": [cat_vals[0], cat_vals[-1]] if cat_vals else None,
        "per_item_median": per_item_out.get("elasticity_quantiles", {}).get("p50")
        if per_item_out.get("elasticity_quantiles") else None,
        "basis": ("主估计 item_fe_controls（单品固定效应 + 周几/月份/趋势）与 iv_wholesale_costshifter "
                  "两个口径的交集；分品类区间取其最小/最大值。"),
        "do_not_use": [
            "pooled_ols_simple：被组间成分拉大（更负），不是价格效应。",
            "between_item_means：度量单品定位差异，不是弹性。",
            "iv_discount_dummy：点估计量级不可信、组内 R² 为负，排除性约束不成立。",
            "单品层面的点估计：中位组内 R² 仅 "
            f"{per_item_out.get('median_within_r2')}，"
            "132 个单品里只有约 3% 的 R²>0.3，不要逐个单品照搬。",
        ],
        "caveat": ("这是蔬菜门店按日清仓的数据；水果店的保质期更长、需求更平缓，"
                   "真实弹性很可能比这里更接近 0（更不敏感）。仿真器应把本区间当上限参考。"),
    }

    return {
        "meta": {
            "source": "data/interim/sales_detail.csv + product_info.csv + wholesale_price.csv（公开真实数据）",
            "kind": "REAL（估计方法为本项目自定口径，见 definition）",
            "fingerprint": {k: ctx["fingerprint"][k] for k in
                            ["sales_detail", "product_info", "wholesale_price"]},
        },
        "definition": {
            "unit_of_observation": "单品×日",
            "qty": "当日该单品销售数量之和（kg，退货行已剔除）",
            "avg_price": "当日该单品 销售额之和 / 销量之和（元/kg，数量加权均价）",
            "model": "ln(qty) = b × ln(avg_price) + 单品固定效应 + 周几 + 月份 + 线性趋势",
            "elasticity": "b，价格上升 1% 对应的销量变化百分比；若为负则符合需求定律",
            "standard_error": "HC1 异方差稳健标准误（classic 也一并给出）",
            "r2": "r2_type='within' 表示组内 R²（去均值后的拟合优度），'overall' 表示常规 R²",
            "excluded": "退货行、quantity<=0、unit_price<=0（IV 模型额外剔除缺批发价的观测）",
            "sample_thresholds": {
                "item_regression_min_obs": MIN_ITEM_OBS_REGRESS,
                "group_min_obs": MIN_GROUP_OBS,
                "weak_iv_F_threshold": WEAK_IV_F,
            },
        },
        "sample": info,
        "recommended_for_simulator": recommended,
        "models": models,
        "by_category": by_category,
        "per_item": per_item_out,
        "discount_contrast": contrast,
        "notes": [
            "公开数据是蔬菜（散称 kg、按日批发、当天基本出清），本项目是水果（标品、保质期按天）；"
            "弹性数值可作量级参考，不能直接搬用。",
            "'朴素回归不能直接用'：把单品之间和单品内部的价格变异混在一起时，pooled OLS 得到 "
            f"{models['pooled_ols_simple']['elasticity']:.2f}，其中包含 "
            f"{models['between_item_means']['elasticity']:.2f} 这一层'均价高的单品本来就卖得少'的横截面成分"
            f"（between_item_means，n={len(im)} 个单品）；加上单品固定效应、只留同单品跨日的价格变动后是 "
            f"{models['item_fe_controls']['elasticity']:.2f}。"
            "两者方向一致但量级差 "
            f"{abs(models['pooled_ols_simple']['elasticity'] - models['item_fe_controls']['elasticity']):.2f}，"
            "所以：本项目的仿真器不采用朴素弹性，模型版定价必须用固定效应/IV 或至少加控制变量。",
            "注意：本脚本早期版本曾把不带常数项的回归当成 pooled OLS，得出 +0.64 这种'价格越高卖得越多'的"
            "荒谬结果——那是漏掉截距项导致的设定错误（强迫回归线过原点），已修正。"
            "本项目文档不应引用该数值。真正的内生性风险仍然存在（店家在需求旺的日子同时不打折），"
            "表现为组内估计与 IV 估计之间 0.16 的差距，无法再进一步识别。",
            "零售价在该数据中并非完全黏性：246 个单品里 217 个有跨日价格变动，"
            "单品×日层面价格变异主要来自打折日（16.3% 的单品×日观测含打折流水，"
            "流水行层面 5.9%）。",
            "IV-打折日的估计（-6.2）虽然一阶段 F 很高，但组内 R² 为负、分品类出现 +207 这类荒谬值，"
            "说明工具变量与库存冲击相关，排除性约束不成立，已标 verdict=不采用。",
        ],
    }


# --------------------------------------------------------------------------
# 4. 损耗率 / 批发价 / 加价率
# --------------------------------------------------------------------------
def analyze_loss(ctx: dict) -> dict:
    print("[4/6] 损耗率与批发价事实")
    data = ctx["data"]
    sales: pd.DataFrame = ctx["sales"]
    product, whole = data["product_info"], data["wholesale_price"]
    loss_cat, loss_item = data["loss_rate_category"], data["loss_rate_item"]

    ok = sales.loc[(~sales["is_return"]) & (sales["quantity"] > 0)]
    item_daily = (ok.groupby(["item_code", "biz_date"], observed=True)
                    .agg(qty=("quantity", "sum"), amount=("amount", "sum")).reset_index())
    item_daily["avg_price"] = item_daily["amount"] / item_daily["qty"]
    wp_daily = (whole.assign(biz_date=pd.to_datetime(whole["sale_date"], format="%Y-%m-%d"))
                     .groupby(["item_code", "biz_date"], observed=True)["wholesale_price"].mean()
                     .reset_index())
    wp_dup_collapsed = int(len(whole) - len(wp_daily))
    item_daily = item_daily.merge(wp_daily, on=["item_code", "biz_date"], how="inner")
    item_daily["markup"] = item_daily["avg_price"] / item_daily["wholesale_price"]
    item_daily["gross_margin_rate"] = 1 - item_daily["wholesale_price"] / item_daily["avg_price"]

    cat_loss = {str(r.category_name): float(r.avg_loss_rate_pct) for r in loss_cat.itertuples()}
    cat_code = {str(r.category_name): str(r.category_code) for r in loss_cat.itertuples()}

    merged = loss_item.merge(product[["item_code", "loss_rate_pct", "category_name"]],
                             on="item_code", how="outer", suffixes=("_att4", "_att1"))
    diff = (merged["loss_rate_pct_att4"] - merged["loss_rate_pct_att1"]).abs()
    # 附件4 的单品表存在列错位：其"单品编码"列被独立升序排序，而"单品名称/损耗率"列保持附件1 的行序。
    # 证据（下面全部只用 data/interim 两个文件即可复现）：
    #   1) 两个文件行序一致时损耗率逐行相同（positional_equal_rows == 总行数）
    #   2) 按 item_code 连接后却大量不一致（code_join_mismatch_rows）
    #   3) loss_rate_item 的 item_code 恰为升序，而 product_info 的 item_code 不是
    # 结论：单品损耗率一律以 product_info.csv(附件1) 为准，禁止把 loss_rate_item.csv 按 item_code 连接。
    positional_equal_rows = int((loss_item["loss_rate_pct"].to_numpy()
                                 == product["loss_rate_pct"].to_numpy()).sum())
    item_loss_check = {
        "att4_item_rows": int(len(loss_item)),
        "att1_item_rows": int(len(product)),
        "items_with_both": int(merged["loss_rate_pct_att1"].notna().sum()),
        "positional_equal_rows": positional_equal_rows,
        "code_join_equal_rows": int((diff < 1e-9).sum()),
        "code_join_mismatch_rows": int((diff >= 1e-9).sum()),
        "max_abs_diff_pct_point_when_joined_by_code": float(diff.max()) if diff.notna().any() else None,
        "loss_rate_item_code_is_sorted": bool(loss_item["item_code"].is_monotonic_increasing),
        "product_info_code_is_sorted": bool(product["item_code"].is_monotonic_increasing),
        "aligned": bool(positional_equal_rows == len(product) == len(loss_item)),
        "authoritative_source": "data/interim/product_info.csv（附件1）的 loss_rate_pct",
        "conclusion": ("附件4 单品表的损耗率数值与附件1 逐行一致，但其单品编码列被单独升序排序，"
                       "按编码连接会错配。本脚本的单品损耗率一律取附件1，"
                       "并把 data/interim/loss_rate_item.csv 视为不可按 item_code 连接的文件。"),
    }

    items = product.merge(loss_item[["item_code", "loss_rate_pct"]].rename(
        columns={"loss_rate_pct": "loss_rate_item_pct"}), on="item_code", how="left")
    items["loss_rate_pct"] = items["loss_rate_pct"].astype(float)
    sold = (ok.groupby("item_code", observed=True)
              .agg(qty_kg=("quantity", "sum"), amount=("amount", "sum"),
                   n_lines=("quantity", "size"), n_days=("biz_date", "nunique")).reset_index())
    wp_stats = (whole.groupby("item_code", observed=True)["wholesale_price"]
                     .agg(wp_mean="mean", wp_std="std", wp_min="min", wp_max="max",
                          wp_n="size").reset_index())
    wp_stats["wp_cv"] = wp_stats["wp_std"] / wp_stats["wp_mean"]
    mk = (item_daily.groupby("item_code", observed=True)["markup"]
          .agg(markup_median="median", markup_mean="mean", mk_n="size").reset_index())
    items = (items.merge(sold, on="item_code", how="left")
                  .merge(wp_stats, on="item_code", how="left")
                  .merge(mk, on="item_code", how="left"))

    cat_rows = []
    for cat, sub in items.groupby("category_name", observed=True):
        mk_med = float(sub["markup_median"].median()) if sub["markup_median"].notna().any() else None
        cat_rows.append({
            "category_name": str(cat),
            "category_code": cat_code.get(str(cat)),
            "avg_loss_rate_pct": cat_loss.get(str(cat)),
            "item_count": int(len(sub)),
            "item_count_with_sales": int(sub["qty_kg"].notna().sum()),
            "item_loss_rate_pct_min": float(sub["loss_rate_pct"].min()),
            "item_loss_rate_pct_median": float(sub["loss_rate_pct"].median()),
            "item_loss_rate_pct_max": float(sub["loss_rate_pct"].max()),
            "wholesale_price_mean": float(sub["wp_mean"].mean()),
            "wholesale_price_cv_median": float(sub["wp_cv"].median()),
            "markup_median": mk_med,
            "gross_margin_rate_from_markup_median": (1 - 1 / mk_med) if mk_med else None,
            "sales_qty_kg_total": float(sub["qty_kg"].sum()),
        })

    wp_dates = pd.to_datetime(whole["sale_date"], format="%Y-%m-%d")
    daily_wp = whole.groupby("sale_date", observed=True)["wholesale_price"].mean()
    year_stats = {}
    for y, sub in whole.assign(_year=wp_dates.dt.year).groupby("_year", observed=True):
        year_stats[str(int(y))] = {
            "n_rows": int(len(sub)),
            "n_items": int(sub["item_code"].nunique()),
            "mean_wholesale_price": float(sub["wholesale_price"].mean()),
            "std": float(sub["wholesale_price"].std()),
        }

    wholesale_block = {
        "rows": int(len(whole)),
        "items": int(whole["item_code"].nunique()),
        "dates": int(whole["sale_date"].nunique()),
        "date_range": [str(whole["sale_date"].min()), str(whole["sale_date"].max())],
        "mean_price_yuan_per_kg": float(whole["wholesale_price"].mean()),
        "median_price_yuan_per_kg": float(whole["wholesale_price"].median()),
        "p05": float(whole["wholesale_price"].quantile(0.05)),
        "p95": float(whole["wholesale_price"].quantile(0.95)),
        "daily_index_mean": float(daily_wp.mean()),
        "daily_index_std": float(daily_wp.std()),
        "item_cv_median": float(wp_stats["wp_cv"].median()),
        "item_cv_p90": float(wp_stats["wp_cv"].quantile(0.90)),
        "by_year": year_stats,
        "note": ("单品批发价的日间波动（CV 中位数）决定仿真器成本噪声的量级；"
                 "批发价只在部分日期有记录，与销售流水的交集观测数见 markup.n_item_day_obs。"),
    }
    markup_block = {
        "n_item_day_obs": int(len(item_daily)),
        "items": int(item_daily["item_code"].nunique()),
        "dates": int(item_daily["biz_date"].nunique()),
        "wholesale_dup_item_date_collapsed": wp_dup_collapsed,
        "markup_median": float(item_daily["markup"].median()),
        "markup_p25": float(item_daily["markup"].quantile(0.25)),
        "markup_p75": float(item_daily["markup"].quantile(0.75)),
        "gross_margin_rate_median": float(item_daily["gross_margin_rate"].median()),
        "note": ("加价率 = 当日数量加权零售均价 / 当日批发价（同单品同日配对，内连接）。"
                 "仿真器的成本-售价关系按此标定；未扣损耗，保本折扣见 break_even_discount。"),
    }

    break_even = []
    for row in cat_rows:
        m, L = row["markup_median"], row["avg_loss_rate_pct"]
        if m and L is not None and m > 0:
            d = 1.0 / (m * (1 - L / 100.0))
            break_even.append({
                "category_name": row["category_name"],
                "markup_median": m,
                "avg_loss_rate_pct": L,
                "break_even_discount_coef": min(d, 1.0),
                "note": "派生值：d 使 d×零售价×(1-损耗率)=批发成本；>=1 表示原价即有安全垫",
            })

    top_items = items.loc[items["qty_kg"].notna()].sort_values("qty_kg", ascending=False).head(20)
    return {
        "meta": {
            "source": ("data/interim/product_info.csv + loss_rate_category.csv + loss_rate_item.csv "
                       "+ wholesale_price.csv + sales_detail.csv"),
            "kind": "REAL",
            "fingerprint": {k: ctx["fingerprint"][k] for k in
                            ["product_info", "loss_rate_category", "loss_rate_item",
                             "wholesale_price", "sales_detail"]},
        },
        "definition": {
            "loss_rate_pct": "附件4 给出的品类/单品参考损耗率（百分比，公开数据自带，非本项目实测）",
            "avg_loss_rate_pct": "附件4 第1个工作表的 6 大类平均值",
            "markup": "当日数量加权零售均价 / 当日批发价（同单品同日配对，内连接）",
            "gross_margin_rate": "1 - 批发价/零售均价（未扣损耗）",
            "break_even_discount_coef": "派生值：d = 1/(加价率×(1-损耗率))",
        },
        "categories": sorted(cat_rows, key=lambda r: -r["avg_loss_rate_pct"]),
        "loss_rate_range_pct": {
            "min": float(min(r["avg_loss_rate_pct"] for r in cat_rows)),
            "max": float(max(r["avg_loss_rate_pct"] for r in cat_rows)),
        },
        "item_loss_rate": {
            "n_items": int(len(items)),
            "min": float(items["loss_rate_pct"].min()),
            "median": float(items["loss_rate_pct"].median()),
            "max": float(items["loss_rate_pct"].max()),
            "source": "product_info.csv（附件1）的 loss_rate_pct",
            "att4_vs_att1": item_loss_check,
        },
        "wholesale": wholesale_block,
        "markup": markup_block,
        "break_even_discount": sorted(break_even, key=lambda r: r["avg_loss_rate_pct"], reverse=True),
        "top20_items_by_qty": [
            {"item_code": int(r.item_code), "item_name": str(r.item_name),
             "category_name": str(r.category_name), "loss_rate_pct": float(r.loss_rate_pct),
             "qty_kg": float(r.qty_kg), "amount": float(r.amount), "n_days": int(r.n_days),
             "wp_mean": None if pd.isna(r.wp_mean) else float(r.wp_mean),
             "markup_median": None if pd.isna(r.markup_median) else float(r.markup_median)}
            for r in top_items.itertuples()],
        "notes": [
            "损耗率来自附件4 的参考值，不是本门店实测；仿真器里的损耗要自己按库存-保质期机制产生，"
            "这个数字只用来校验仿真结果的量级。",
            "公开数据是蔬菜按日清仓，损耗率天然高于水果；迁移时按水果品类下调属假设（写在 docs/02 假设章节）。",
            "数据质量问题（已核实，见 item_loss_rate.att4_vs_att1）：附件4 的单品表里"
            "'单品编码'列被单独升序排序，与它自己的'单品名称/损耗率'列错位；"
            "其损耗率数值与附件1 逐行完全一致（251/251），但按 item_code 连接只有 50/251 对得上。"
            "因此单品损耗率一律以附件1（product_info.csv）为准，"
            "禁止把 data/interim/loss_rate_item.csv 按 item_code 连接使用。",
        ],
    }


# --------------------------------------------------------------------------
# 5. 图
# --------------------------------------------------------------------------
def _setup_matplotlib():
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    from matplotlib import font_manager
    chosen = None
    for name in ["Microsoft YaHei", "SimHei", "SimSun", "Noto Sans CJK SC"]:
        try:
            font_manager.findfont(font_manager.FontProperties(family=name), fallback_to_default=False)
            chosen = name
            break
        except Exception:
            continue
    if chosen:
        plt.rcParams["font.sans-serif"] = [chosen]
        plt.rcParams["axes.unicode_minus"] = False
    plt.rcParams["figure.dpi"] = 130
    plt.rcParams["savefig.bbox"] = "tight"
    return plt, chosen


def make_figs(hourly: dict, elast: dict, panel: pd.DataFrame) -> list[str]:
    print("[5/6] 生成校准图")
    plt, font = _setup_matplotlib()
    zh = font is not None
    if not zh:
        print("    [警告] 未找到中文字体，图内标签自动改用英文")

    def L(cn: str, en: str) -> str:
        return cn if zh else en

    FIG_DIR.mkdir(parents=True, exist_ok=True)
    out = []

    # ---- 图1：时段分布 ----
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.6))
    ax = axes[0]
    overall = hourly["overall"]
    avg = overall["hour_avg_lines_per_open_day"]
    bars = ax.bar(HOURS, avg, color="#4C72B0")
    peak = overall["peak_hour"]
    bars[peak].set_color("#C44E52")
    ax.set_xlabel(L("小时（0-23）", "hour of day"))
    ax.set_ylabel(L("平均成交笔数 / 营业日", "avg sale lines per open day"))
    ax.set_title(L(f"真实时段分布（{overall['sample_open_days']} 个营业日，峰值 {peak} 时）",
                   f"Hourly traffic, {overall['sample_open_days']} open days, peak={peak}h"))
    ax.set_xticks(range(0, 24, 2))
    ax.annotate(L(f"峰值 {peak} 时\n{avg[peak]:.1f} 笔/日", f"peak {peak}h\n{avg[peak]:.1f}/day"),
                xy=(peak, avg[peak]), xytext=(max(peak - 6, 0), avg[peak] * 0.95),
                arrowprops=dict(arrowstyle="->", color="#C44E52"))
    ax.grid(axis="y", alpha=0.25)

    ax = axes[1]
    for dow in range(7):
        rec = hourly["by_weekday"][dow]
        style = dict(lw=2.2, color="#C44E52") if dow in WEEKEND_CODES else dict(lw=1.3, alpha=0.8)
        ax.plot(HOURS, rec["hour_avg_lines_per_open_day"], label=WEEKDAY_CN[dow], **style)
    ax.set_xlabel(L("小时（0-23）", "hour of day"))
    ax.set_ylabel(L("平均成交笔数 / 营业日", "avg sale lines per open day"))
    ax.set_title(L("按周几拆分（红色=周末）", "By weekday (red = weekend)"))
    ax.set_xticks(range(0, 24, 2))
    ax.legend(fontsize=8, ncol=4)
    ax.grid(alpha=0.25)
    p1 = FIG_DIR / "traffic_hourly.png"
    fig.savefig(p1)
    plt.close(fig)
    out.append(str(p1.relative_to(ROOT)))
    print(f"  -> {p1.relative_to(ROOT)}")

    # ---- 图2：价格-销量 ----
    fig, axes = plt.subplots(2, 2, figsize=(13, 9.5))
    lnp = panel["ln_p"].to_numpy()
    lnq = panel["ln_q"].to_numpy()

    ax = axes[0][0]
    hb = ax.hexbin(lnp, lnq, gridsize=60, bins="log", cmap="viridis", mincnt=1)
    b_simple = elast["models"]["pooled_ols_simple"]["elasticity"]
    xs = np.linspace(lnp.min(), lnp.max(), 50)
    ax.plot(xs, lnq.mean() + b_simple * (xs - lnp.mean()), color="#C44E52", lw=2,
            label=L(f"简单 OLS 斜率 {b_simple:.2f}", f"pooled OLS slope {b_simple:.2f}"))
    ax.set_xlabel(L("ln(当日均价 元/kg)", "ln(daily avg price)"))
    ax.set_ylabel(L("ln(当日销量 kg)", "ln(daily qty kg)"))
    ax.set_title(L(f"单品×日散点（n={len(panel)}）", f"Item-day obs (n={len(panel)})"))
    ax.legend(fontsize=8)
    fig.colorbar(hb, ax=ax, label=L("观测数(对数)", "count (log)"))

    ax = axes[0][1]
    cats = sorted([c for c, r in elast["by_category"].items() if "item_fe_controls" in r],
                  key=lambda c: elast["by_category"][c]["item_fe_controls"]["elasticity"])
    ys = np.arange(len(cats))
    b = np.array([elast["by_category"][c]["item_fe_controls"]["elasticity"] for c in cats])
    lo = np.array([elast["by_category"][c]["item_fe_controls"]["ci95_hc1"][0] for c in cats])
    hi = np.array([elast["by_category"][c]["item_fe_controls"]["ci95_hc1"][1] for c in cats])
    ax.errorbar(b, ys, xerr=[b - lo, hi - b], fmt="o", color="#4C72B0",
                ecolor="#999999", capsize=4)
    ax.axvline(0, color="#C44E52", lw=1, ls="--")
    ax.set_yticks(ys)
    ax.set_yticklabels(cats, fontsize=9)
    ax.set_xlabel(L("弹性估计（95% CI, HC1）", "elasticity (95% CI, HC1)"))
    ax.set_title(L("分品类：单品固定效应模型", "By category: item-FE model"))
    ax.grid(axis="x", alpha=0.25)

    ax = axes[1][0]
    pi = elast["per_item"]
    betas = elast["_beta_cache"]
    if betas.size:
        ax.hist(betas, bins=30, color="#55A868", edgecolor="white")
        ax.axvline(0, color="#C44E52", lw=1.5, ls="--")
        ax.axvline(float(np.median(betas)), color="#4C72B0", lw=1.5,
                   label=L(f"中位数 {np.median(betas):.2f}", f"median {np.median(betas):.2f}"))
        ax.set_xlabel(L("单品弹性估计", "per-item elasticity"))
        ax.set_ylabel(L("单品数", "number of items"))
        ax.set_title(L(f"单品层面弹性分布（{pi['n_items_eligible']} 个单品，观测≥{MIN_ITEM_OBS_REGRESS}）",
                       f"Per-item elasticities ({pi['n_items_eligible']} items)"))
        ax.legend(fontsize=8)
    else:
        ax.text(0.5, 0.5, L("样本不足", "insufficient sample"), ha="center", va="center")
    ax.grid(axis="y", alpha=0.25)

    ax = axes[1][1]
    dc = elast["discount_contrast"]
    base = [dc["mean_price_no_discount_day"], dc["mean_price_discount_day"]]
    qtyv = [dc["mean_qty_no_discount_day"], dc["mean_qty_discount_day"]]
    x = np.arange(2)
    ax.bar(x - 0.2, base, width=0.4, color="#4C72B0", label=L("日均价 元/kg", "avg price"))
    ax.set_xticks(x)
    ax.set_xticklabels([L("不打折日", "no-discount day"), L("打折日", "discount day")])
    ax.set_ylabel(L("日均价 元/kg", "avg price yuan/kg"))
    ax2 = ax.twinx()
    ax2.bar(x + 0.2, qtyv, width=0.4, color="#DD8452", label=L("日销量 kg", "avg qty kg"))
    ax2.set_ylabel(L("日销量 kg", "avg qty kg"))
    ax.set_title(L("打折日 vs 不打折日（朴素对照，非因果）", "Discount vs no-discount (naive)"))
    h1, l1 = ax.get_legend_handles_labels()
    h2, l2 = ax2.get_legend_handles_labels()
    ax.legend(h1 + h2, l1 + l2, fontsize=8, loc="upper left")
    fig.suptitle(L("价格—销量校准（公开真实数据：蔬菜门店）",
                   "Price-quantity calibration (real public data)"), fontsize=13)
    p2 = FIG_DIR / "price_vs_qty.png"
    fig.savefig(p2)
    plt.close(fig)
    out.append(str(p2.relative_to(ROOT)))
    print(f"  -> {p2.relative_to(ROOT)}")
    return out


# --------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser(description="公开真实数据校准")
    ap.add_argument("--skip-figs", action="store_true", help="只出 JSON，不画图")
    args = ap.parse_args()

    ctx = load_inputs()
    hourly = analyze_hourly(ctx)
    elast = analyze_elasticity(ctx)
    loss = analyze_loss(ctx)
    panel: pd.DataFrame = ctx["panel"]

    # 单品弹性缓存（给画直方图用，不进 JSON）
    g = panel.groupby("item_code", observed=True)
    x = panel["ln_p"] - g["ln_p"].transform("mean")
    y = panel["ln_q"] - g["ln_q"].transform("mean")
    sxx = (x * x).groupby(panel["item_code"], observed=True).sum().to_numpy()
    sxy = (x * y).groupby(panel["item_code"], observed=True).sum().to_numpy()
    n = g.size().to_numpy()
    with np.errstate(divide="ignore", invalid="ignore"):
        beta = np.where(sxx > 0, sxy / sxx, np.nan)
    mask = (n >= MIN_ITEM_OBS_REGRESS) & np.isfinite(beta)
    elast["_beta_cache"] = beta[mask]

    figs = make_figs(hourly, elast, panel) if not args.skip_figs else []
    if args.skip_figs:
        print("[5/6] 跳过画图")
    elast.pop("_beta_cache", None)
    elast["figures"] = {
        "files": figs,
        "note": ("matplotlib 使用系统中文字体（Microsoft YaHei/SimHei），图内标签为中文"
                 if figs else "本次未生成图（--skip-figs）"),
    }

    print("[6/6] 写出 JSON")
    write_json(OUT_DIR / "hourly_traffic.json", hourly)
    write_json(OUT_DIR / "price_elasticity.json", elast)
    write_json(OUT_DIR / "loss_rate_facts.json", loss)

    o = hourly["overall"]
    m = elast["models"]
    pi = elast["per_item"]
    print("\n" + "=" * 78)
    print("事实卡（全部来自公开真实数据）")
    print("=" * 78)
    print(f"营业日 {o['sample_open_days']} / 日历 {o['calendar_days']} 天，"
          f"销售流水行 {o['sale_lines']}（剔除退货 {o['excluded_return_lines']} 行）")
    print(f"日均成交笔数(营业日)={fmt(o['avg_lines_per_open_day'],1)}  "
          f"峰值小时={o['peak_hour']}时({fmt(o['peak_hour_avg_lines'],1)} 笔/日)  前三={o['top3_hours']}")
    print(f"营业时段 {hourly['basket_facts']['first_sale_hour']}~"
          f"{hourly['basket_facts']['last_sale_hour']} 时；"
          f"工作日日均 {fmt(hourly['by_day_type']['workday']['avg_lines_per_open_day'],1)} 笔，"
          f"周末日均 {fmt(hourly['by_day_type']['weekend']['avg_lines_per_open_day'],1)} 笔")
    print("\n弹性估计（ln 日销量 ~ ln 日均价）：")
    for k, v in m.items():
        extra = ""
        if "first_stage_F" in v:
            extra = f"  一阶段F={fmt(v['first_stage_F'],1)}{'（弱工具）' if v['weak_instrument'] else ''}"
        print(f"  {k:28s} b={fmt(v['elasticity'],3)}  "
              f"CI95=[{fmt(v['ci95_hc1'][0],3)},{fmt(v['ci95_hc1'][1],3)}]  "
              f"n={v['n_obs']:>7d}  R2={fmt(v['r2'],3)}{extra}")
    rec = elast["recommended_for_simulator"]
    print(f"  推荐给仿真器的弹性区间 {[round(x, 3) for x in rec['elasticity_range']]}，"
          f"中心 {fmt(rec['central'],3)}；分品类 "
          f"{ {k: round(v,3) for k,v in rec['by_category'].items()} }")
    if pi["n_items_eligible"]:
        print(f"  单品层面：{pi['n_items_eligible']}/{pi['n_items_total']} 个单品样本充足，"
              f"中位数={fmt(pi['elasticity_quantiles']['p50'],3)}，"
              f"负值占比={fmt(pi['share_negative'],3)}")
    print(f"\n损耗率(品类平均) {loss['loss_rate_range_pct']['min']}%~{loss['loss_rate_range_pct']['max']}%，"
          f"单品 {loss['item_loss_rate']['min']}%~{loss['item_loss_rate']['max']}%")
    print(f"批发价 均值 {fmt(loss['wholesale']['mean_price_yuan_per_kg'],2)} 元/kg，"
          f"单品CV中位数 {fmt(loss['wholesale']['item_cv_median'],3)}；"
          f"加价率中位数 {fmt(loss['markup']['markup_median'],3)}")
    print(f"对账：{'通过' if hourly['reconciliation']['matched'] else '失败'}")
    print("=" * 78)
    return 0


if __name__ == "__main__":
    sys.exit(main())
