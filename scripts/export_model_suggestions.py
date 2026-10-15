# -*- coding: utf-8 -*-
"""
导出在线模型定价产物（Issue #2）：让后端选 MODEL 时真的用上模型，而不是一直回退规则版。

流程
====
    后端 GET /api/pricing/model-input  ──►  本脚本  ──►  data/seed/pricing_model.json  ──►  后端 MODEL 策略
    （或 --seed-dir 读种子 CSV，后端没启动时用）

1. 取"可观测"数据：单品档案、批次台账、业务日之前的逐日逐批次销量（不含业务日当天及以后，和离线评测同一条"不偷看"口径）。
2. 用台账 + 销量重建每个单品每天的 期初库存 / 当日到货，交给 `fit_demand_model` 做缺货截断剔除
   （销售流水里没有"断货"标记，`sold_qty >= 0.98 × (期初 + 到货)` 的天会被当成截断样本剔除）。
3. 复用 `pricing_model.ModelPricer`：拟合需求模型，再按单品联合决策（一个单品一个货架价），套最低折扣线。
4. 写产物：业务日、模型版本（pricing_model.py 内容哈希）、生成时间、输入指纹、模型状态，以及逐批次建议。
   后端只认业务日一致、modelStatus=ok 的产物；否则如实回退规则版并在理由里写原因。

用法
====
    # 后端在跑（推荐：用系统里的真实数据）
    python scripts/export_model_suggestions.py --biz-date 2026-06-29
    # 后端没跑：直接读种子 CSV（只覆盖种子里的批次；建议按单品给出，后端按 itemCode 匹配）
    python scripts/export_model_suggestions.py --biz-date 2026-06-29 --seed-dir data/seed
    # 自检（不依赖任何数据文件和后端）
    python scripts/export_model_suggestions.py --selftest
"""
from __future__ import annotations

import argparse
import datetime as _dt
import hashlib
import json
import math
import os
import sys
import urllib.error
import urllib.request
from typing import Dict, List, Optional

import numpy as np
import pandas as pd

from pricing_model import ModelPricer, _dow
from pricing_rule import DISCOUNT_GRID, snap_to_grid

SCHEMA_VERSION = 1
DEFAULT_OUT = os.path.join("data", "seed", "pricing_model.json")
DEFAULT_API = "http://localhost:8080"
DEFAULT_HISTORY_DAYS = 60
DEFAULT_MIN_DISCOUNT = 0.60


# ---------------------------------------------------------------------------
# 1. 取数：两种来源统一成同一个快照结构（字段名与后端 ModelInputResponse 一致）
# ---------------------------------------------------------------------------


def load_from_api(api: str, biz_date: _dt.date, history_days: int) -> dict:
    url = f"{api.rstrip('/')}/api/pricing/model-input?bizDate={biz_date.isoformat()}&historyDays={history_days}"
    try:
        with urllib.request.urlopen(url, timeout=60) as resp:
            body = json.loads(resp.read().decode("utf-8"))
    except urllib.error.URLError as e:
        raise SystemExit(f"连不上后端 {url}：{e}。后端没启动时可以加 --seed-dir data/seed 读种子 CSV")
    if body.get("code") != 0:
        raise SystemExit(f"后端返回错误：{body.get('message')}")
    snap = body["data"]
    snap["source"] = "api"
    return snap


def load_from_seed(seed_dir: str, biz_date: _dt.date, history_days: int) -> dict:
    """从种子 CSV 拼出与接口同形的快照。种子里没有数据库 id：建议只能按 itemCode 给（后端按单品匹配）。"""
    items = pd.read_csv(os.path.join(seed_dir, "shop_item.csv"), dtype={"item_code": str})
    batches = pd.read_csv(os.path.join(seed_dir, "shop_batch.csv"), dtype={"item_code": str, "batch_no": str})
    sales = pd.read_csv(os.path.join(seed_dir, "shop_sales.csv"), dtype={"item_code": str, "batch_no": str})
    hist_from = biz_date - _dt.timedelta(days=history_days)

    batches["arrive_date"] = pd.to_datetime(batches["arrive_date"]).dt.date
    batches["expire_date"] = pd.to_datetime(batches["expire_date"]).dt.date
    batches = batches[(batches["arrive_date"] <= biz_date) & (batches["expire_date"] >= hist_from)]
    sales_from = min([hist_from] + list(batches["arrive_date"]))

    sales["biz_date"] = pd.to_datetime(sales["biz_date"]).dt.date
    sales = sales[(sales["biz_date"] >= sales_from) & (sales["biz_date"] < biz_date)].copy()
    sales["revenue"] = sales["quantity"] * sales["unit_price"]
    daily = (sales.groupby(["biz_date", "batch_no", "item_code"], dropna=False)
             .agg(qty=("quantity", "sum"), revenue=("revenue", "sum")).reset_index())

    # 种子的 remain_qty 是仿真结束时的状态，这里按"业务日开始时"重建：到货量 − 业务日之前卖掉的
    sold_before = daily.groupby("batch_no")["qty"].sum()
    snap_batches = []
    for b in batches.itertuples():
        remain = max(float(b.init_qty) - float(sold_before.get(b.batch_no, 0.0)), 0.0)
        snap_batches.append({
            "batchId": None, "batchNo": b.batch_no, "itemId": None, "itemCode": b.item_code,
            "arriveDate": b.arrive_date.isoformat(), "expireDate": b.expire_date.isoformat(),
            "initQty": float(b.init_qty), "remainQty": round(remain, 2), "costPrice": float(b.cost_price),
            "status": "ON_SALE" if remain > 0 else "SOLD_OUT",
        })
    return {
        "source": "seed",
        "bizDate": biz_date.isoformat(),
        "historyFrom": hist_from.isoformat(),
        "historyTo": (biz_date - _dt.timedelta(days=1)).isoformat(),
        "historyDays": history_days,
        "salesFrom": sales_from.isoformat(),
        "items": [{"itemId": None, "itemCode": r.item_code, "itemName": r.item_name,
                   "basePrice": float(r.base_price), "costPrice": float(r.cost_price)} for r in items.itertuples()],
        "batches": snap_batches,
        "sales": [{"bizDate": r.biz_date.isoformat(), "batchId": None, "batchNo": r.batch_no,
                   "itemCode": r.item_code, "qty": round(float(r.qty), 2), "revenue": round(float(r.revenue), 2)}
                  for r in daily.itertuples()],
    }


# ---------------------------------------------------------------------------
# 2. 重建按日历史：每个单品每天的 净销量 / 实收均价 / 期初库存 / 当日到货
# ---------------------------------------------------------------------------


def _batch_key(row: dict) -> str:
    return f"id:{row['batchId']}" if row.get("batchId") is not None else f"no:{row.get('batchNo')}"


def build_history(snap: dict) -> pd.DataFrame:
    """训练窗口 [historyFrom, historyTo] 内每个 单品 × 日 一行；只用业务日之前的成交。"""
    hist_from = _dt.date.fromisoformat(snap["historyFrom"])
    hist_to = _dt.date.fromisoformat(snap["historyTo"])
    base = {i["itemCode"]: float(i["basePrice"]) for i in snap["items"] if i.get("basePrice") is not None}
    batches = snap["batches"]
    if not batches or hist_to < hist_from:
        return pd.DataFrame()

    keys = [_batch_key(b) for b in batches]
    arrive = np.array([_dt.date.fromisoformat(b["arriveDate"]) for b in batches])
    expire = np.array([_dt.date.fromisoformat(b["expireDate"]) for b in batches])
    init_qty = np.array([float(b["initQty"]) for b in batches])
    codes = np.array([b["itemCode"] for b in batches])

    sales = pd.DataFrame(snap["sales"])
    if sales.empty:
        sales = pd.DataFrame(columns=["bizDate", "batchId", "batchNo", "itemCode", "qty", "revenue"])
    sales["bizDate"] = pd.to_datetime(sales["bizDate"]).dt.date
    sales["key"] = [_batch_key(r) for r in sales.to_dict("records")]

    sales_from = _dt.date.fromisoformat(snap.get("salesFrom") or snap["historyFrom"])
    days = [sales_from + _dt.timedelta(days=i) for i in range((hist_to - sales_from).days + 1)]
    # 批次 × 日 的销量矩阵，算"截至当天开始已卖出"
    per_batch = (sales[sales["key"].isin(keys)]
                 .pivot_table(index="key", columns="bizDate", values="qty", aggfunc="sum", fill_value=0.0)
                 .reindex(index=keys, columns=days, fill_value=0.0))
    sold_before = per_batch.cumsum(axis=1).shift(1, axis=1).fillna(0.0).to_numpy()

    item_daily = (sales.groupby(["bizDate", "itemCode"])
                  .agg(sold_qty=("qty", "sum"), revenue=("revenue", "sum")).reset_index())
    item_daily = item_daily.set_index(["bizDate", "itemCode"])

    rows = []
    for di, day in enumerate(days):
        if day < hist_from:
            continue
        onhand_mask = (arrive < day) & (expire >= day)
        on_hand = np.where(onhand_mask, np.clip(init_qty - sold_before[:, di], 0.0, None), 0.0)
        arrival = np.where(arrive == day, init_qty, 0.0)
        frame = pd.DataFrame({"itemCode": codes, "on_hand_start": on_hand, "arrival_qty": arrival})
        stock = frame.groupby("itemCode")[["on_hand_start", "arrival_qty"]].sum()
        for code, srow in stock.iterrows():
            key = (day, code)
            sold = float(item_daily.loc[key, "sold_qty"]) if key in item_daily.index else 0.0
            revenue = float(item_daily.loc[key, "revenue"]) if key in item_daily.index else 0.0
            if srow["on_hand_start"] + srow["arrival_qty"] <= 0 and sold <= 0:
                continue  # 那天根本没货、也没卖，不是有效样本
            rows.append({
                "biz_date": day,
                "item_code": code,
                "sold_qty": sold,
                "unit_price": revenue / sold if sold > 0 else float("nan"),
                "base_price": base.get(code, float("nan")),
                "on_hand_start": float(srow["on_hand_start"]),
                "arrival_qty": float(srow["arrival_qty"]),
            })
    return pd.DataFrame(rows)


def decision_batches(snap: dict) -> List[dict]:
    """业务日当天在售的批次（ModelPricer.decide_item 的输入）。"""
    biz = _dt.date.fromisoformat(snap["bizDate"])
    items = {i["itemCode"]: i for i in snap["items"]}
    out = []
    for b in snap["batches"]:
        arrive = _dt.date.fromisoformat(b["arriveDate"])
        expire = _dt.date.fromisoformat(b["expireDate"])
        remain = float(b.get("remainQty") or 0.0)
        item = items.get(b["itemCode"])
        if item is None or b.get("status") != "ON_SALE" or remain <= 0 or arrive > biz or expire < biz:
            continue
        out.append({
            "batch_id": b.get("batchId"), "batch_no": b.get("batchNo"),
            "item_id": item.get("itemId"), "item_code": b["itemCode"],
            "days_left": (expire - biz).days,  # 与后端 BatchService.daysLeft 同口径
            "remain_qty": remain,
            "base_price": float(item["basePrice"]),
            "cost_price": float(b.get("costPrice") or item.get("costPrice") or 0.0),
        })
    return out


# ---------------------------------------------------------------------------
# 3. 拟合 + 决策 + 写产物
# ---------------------------------------------------------------------------


def _file_hash(path: str) -> str:
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()[:12]


def _jsonable(v):
    if isinstance(v, (np.integer,)):
        return int(v)
    if isinstance(v, (np.floating,)):
        return None if not np.isfinite(v) else float(v)
    if isinstance(v, float) and not math.isfinite(v):
        return None
    if isinstance(v, (_dt.date, _dt.datetime)):
        return v.isoformat()
    return v


def build_artifact(snap: dict, min_discount_rate: float, generated_at: Optional[str] = None) -> dict:
    biz = _dt.date.fromisoformat(snap["bizDate"])
    history = build_history(snap)
    batches = decision_batches(snap)
    here = os.path.dirname(os.path.abspath(__file__))
    canonical = json.dumps({k: snap[k] for k in ("bizDate", "items", "batches", "sales")},
                           sort_keys=True, ensure_ascii=False, default=str)

    pricer = ModelPricer(min_discount_rate=min_discount_rate)
    pricer.update_base_prices(batches)
    pricer.maybe_refit(0, history if not history.empty else None)
    meta = {k: _jsonable(v) for k, v in (pricer.model.meta.items() if pricer.model else [])}

    suggestions: List[dict] = []
    if pricer._model_usable():
        status = "ok"
        by_item: Dict[str, List[dict]] = {}
        for b in batches:
            by_item.setdefault(b["item_code"], []).append(b)
        dow = _dow(biz)
        n_items_decided = 0
        for code, rows in sorted(by_item.items()):
            dec = pricer.decide_item(code, rows, dow)
            if dec is None:
                continue
            n_items_decided += 1
            rate = pricer._apply_floor(snap_to_grid(dec.discount_rate, DISCOUNT_GRID))
            total = sum(r["remain_qty"] for r in rows) or 1.0
            for r in rows:
                suggestions.append({
                    "batchId": r["batch_id"], "batchNo": r["batch_no"],
                    "itemId": r["item_id"], "itemCode": code,
                    "discountRate": round(float(rate), 2),
                    # 模型按单品联合决策给出总期望销量，按批次现存占比分摊
                    "expectedQty": round(float(dec.expected_qty) * r["remain_qty"] / total, 2),
                    "reason": dec.reason,
                })
        note = (f"按单品联合决策 {n_items_decided} 个单品、{len(suggestions)} 个在售批次；"
                f"训练样本 {meta.get('n_obs_raw')} 行，剔除缺货截断 {meta.get('n_dropped_stockout')} 行")
    else:
        status = str(meta.get("status") or "unusable")
        note = (f"需求模型没有拟合出来（status={status}，训练样本 {meta.get('n_obs_raw')} 行），"
                "本产物不含建议，后端会回退规则版")

    return {
        "schemaVersion": SCHEMA_VERSION,
        "bizDate": biz.isoformat(),
        "generatedAt": generated_at or _dt.datetime.now().replace(microsecond=0).isoformat(),
        "modelVersion": f"pricing_model.py@{_file_hash(os.path.join(here, 'pricing_model.py'))}",
        "modelStatus": status,
        "modelNote": note,
        "inputSource": snap.get("source"),
        "inputHash": hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16],
        "historyWindow": {"from": snap["historyFrom"], "to": snap["historyTo"], "days": snap["historyDays"],
                          "rows": int(len(history))},
        "minDiscountRate": min_discount_rate,
        "fit": meta,
        "suggestions": suggestions,
    }


def write_atomic(path: str, artifact: dict) -> None:
    """先写临时文件再改名：后端按 mtime 重新加载，不会读到写了一半的 JSON。"""
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(artifact, f, ensure_ascii=False, indent=2, default=_jsonable)
    os.replace(tmp, path)


# ---------------------------------------------------------------------------
# 4. 自检：自造小样本，验证 不偷看 / 库存重建 / 产物结构 / 底线
# ---------------------------------------------------------------------------


def _synthetic_snapshot(biz: _dt.date, days: int = 40) -> dict:
    rng = np.random.default_rng(20261015)
    items = [{"itemId": 1, "itemCode": "A", "itemName": "测试苹果", "basePrice": 10.0, "costPrice": 4.0},
             {"itemId": 2, "itemCode": "B", "itemName": "测试香蕉", "basePrice": 6.0, "costPrice": 2.5}]
    batches, sales = [], []
    bid = 0
    start = biz - _dt.timedelta(days=days)
    for it in items:
        # 每 4 天到一批、保质期 6 天；价格比在 0.6~1.0 之间变化，需求随价格下降而上升
        d = start - _dt.timedelta(days=4)
        while d <= biz:
            bid += 1
            init = 60.0
            batches.append({"batchId": bid, "batchNo": f"B{bid}", "itemId": it["itemId"], "itemCode": it["itemCode"],
                            "arriveDate": d.isoformat(), "expireDate": (d + _dt.timedelta(days=6)).isoformat(),
                            "initQty": init, "remainQty": init, "costPrice": it["costPrice"], "status": "ON_SALE"})
            sold_total = 0.0
            for k in range(4):
                day = d + _dt.timedelta(days=k)
                if day >= biz:
                    break
                ratio = float(rng.choice([1.0, 0.9, 0.8, 0.7, 0.6]))
                lam = 8.0 * ratio ** -1.6 * (1.2 if day.weekday() >= 5 else 1.0)
                q = float(min(rng.poisson(lam), init - sold_total))
                if q > 0:
                    sold_total += q
                    sales.append({"bizDate": day.isoformat(), "batchId": bid, "batchNo": f"B{bid}",
                                  "itemCode": it["itemCode"], "qty": q, "revenue": round(q * it["basePrice"] * ratio, 2)})
            batches[-1]["remainQty"] = max(init - sold_total, 0.0)
            batches[-1]["status"] = "ON_SALE" if batches[-1]["remainQty"] > 0 else "SOLD_OUT"
            d += _dt.timedelta(days=4)
    return {"source": "selftest", "bizDate": biz.isoformat(),
            "historyFrom": start.isoformat(), "historyTo": (biz - _dt.timedelta(days=1)).isoformat(),
            "historyDays": days, "salesFrom": (start - _dt.timedelta(days=4)).isoformat(),
            "items": items, "batches": batches, "sales": sales}


def _selftest() -> int:
    biz = _dt.date(2026, 6, 29)
    snap = _synthetic_snapshot(biz)
    hist = build_history(snap)
    assert not hist.empty, "历史为空"
    assert hist["biz_date"].max() < biz, "历史里出现了业务日当天或以后的数据（偷看）"

    # 库存重建：手算第一批（A，到货日 d0）在 d0+1 的期初库存 = 到货量 − d0 当天卖出
    first = snap["batches"][0]
    d0 = _dt.date.fromisoformat(first["arriveDate"])
    d1 = d0 + _dt.timedelta(days=1)
    sold_d0 = sum(s["qty"] for s in snap["sales"] if s["batchId"] == first["batchId"] and s["bizDate"] == d0.isoformat())
    if d1 >= _dt.date.fromisoformat(snap["historyFrom"]):
        row = hist[(hist["biz_date"] == d1) & (hist["item_code"] == "A")]
        others = [b for b in snap["batches"] if b["itemCode"] == "A" and b is not first
                  and _dt.date.fromisoformat(b["arriveDate"]) < d1 <= _dt.date.fromisoformat(b["expireDate"])]
        assert not others, "自检样本设计应保证该日只有一个在售批次"
        assert abs(float(row["on_hand_start"].iloc[0]) - (first["initQty"] - sold_d0)) < 1e-6, "期初库存重建错误"

    art = build_artifact(snap, min_discount_rate=DEFAULT_MIN_DISCOUNT, generated_at="2026-10-15T00:00:00")
    assert art["bizDate"] == biz.isoformat()
    assert art["modelStatus"] == "ok", f"模型应可用：{art['modelNote']}"
    assert art["suggestions"], "应至少给出一条建议"
    for s in art["suggestions"]:
        assert s["discountRate"] in DISCOUNT_GRID, s
        assert s["discountRate"] >= DEFAULT_MIN_DISCOUNT - 1e-9, s
        assert s["batchId"] is not None and s["itemId"] is not None and s["reason"]
    json.dumps(art, ensure_ascii=False, default=_jsonable)  # 能序列化

    # 训练样本不足：模型不可用 → 不给建议、状态如实
    thin = _synthetic_snapshot(biz, days=3)
    thin_art = build_artifact(thin, min_discount_rate=DEFAULT_MIN_DISCOUNT)
    assert thin_art["modelStatus"] != "ok" and not thin_art["suggestions"], thin_art["modelNote"]
    print(f"[selftest] OK：历史 {len(hist)} 行、建议 {len(art['suggestions'])} 条；样本不足时状态 {thin_art['modelStatus']}")
    return 0


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="导出在线模型定价产物（pricing_model.json）")
    ap.add_argument("--biz-date", help="要做定价决策的业务日 yyyy-MM-dd（与后端生成建议的业务日一致）")
    ap.add_argument("--api", default=DEFAULT_API, help=f"后端地址，默认 {DEFAULT_API}")
    ap.add_argument("--seed-dir", help="不连后端，改读该目录下的种子 CSV（如 data/seed）")
    ap.add_argument("--history-days", type=int, default=DEFAULT_HISTORY_DAYS, help="训练窗口天数，默认 60")
    ap.add_argument("--min-discount-rate", type=float, default=DEFAULT_MIN_DISCOUNT,
                    help="最低折扣线，与 app.pricing.min-discount-rate 保持一致，默认 0.60")
    ap.add_argument("--out", default=DEFAULT_OUT, help=f"产物路径（后端 app.pricing.model-file），默认 {DEFAULT_OUT}")
    ap.add_argument("--selftest", action="store_true", help="自检（不依赖数据文件与后端）")
    args = ap.parse_args(argv)

    if args.selftest:
        return _selftest()
    if not args.biz_date:
        ap.error("需要 --biz-date（或用 --selftest）")
    biz = _dt.date.fromisoformat(args.biz_date)
    snap = (load_from_seed(args.seed_dir, biz, args.history_days) if args.seed_dir
            else load_from_api(args.api, biz, args.history_days))
    art = build_artifact(snap, args.min_discount_rate)
    write_atomic(args.out, art)
    print(f"[export] {art['bizDate']} 模型状态 {art['modelStatus']}，建议 {len(art['suggestions'])} 条 → {args.out}")
    print(f"[export] {art['modelNote']}")
    return 0 if art["modelStatus"] == "ok" else 2


if __name__ == "__main__":
    sys.exit(main())
