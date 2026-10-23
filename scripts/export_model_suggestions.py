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
    # 后端没跑：直接读种子 CSV（只覆盖种子里的批次；种子里没有数据库 id，后端按批次号 batchNo 匹配）
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
    except urllib.error.HTTPError as e:
        # 后端回了错误（例如参数不合法 400）：把后端给的原因原样告诉用户，而不是说"连不上"
        try:
            message = json.loads(e.read().decode("utf-8")).get("message") or str(e)
        except (ValueError, AttributeError):
            message = str(e)
        raise SystemExit(f"后端拒绝了请求（HTTP {e.code}）：{message}")
    except urllib.error.URLError as e:
        raise SystemExit(f"连不上后端 {url}：{e}。后端没启动时可以加 --seed-dir data/seed 读种子 CSV")
    if body.get("code") != 0:
        raise SystemExit(f"后端返回错误：{body.get('message')}")
    snap = body["data"]
    snap["source"] = "api"
    return snap


def load_from_seed(seed_dir: str, biz_date: _dt.date, history_days: int) -> dict:
    """从种子 CSV 拼出与接口同形的快照。

    种子里没有数据库 id，建议带 batchNo（后端按批次号匹配，批次号随种子原样导入、唯一）。
    批次的现存/状态取 CSV 里的值，也就是后端导入种子后数据库里的状态——后端给建议时看的就是它，
    这样种子模式与接口模式对同一份数据给出同样的决策。"""
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

    snap_batches = [{
        "batchId": None, "batchNo": b.batch_no, "itemId": None, "itemCode": b.item_code,
        "arriveDate": b.arrive_date.isoformat(), "expireDate": b.expire_date.isoformat(),
        "initQty": float(b.init_qty), "remainQty": float(b.remain_qty), "costPrice": float(b.cost_price),
        "status": b.status,
    } for b in batches.itertuples()]
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
    """批次键：有数据库 id 用 id，否则用批次号。必须在原始 dict 上算——一旦进了 DataFrame，
    "int + None" 的列会被 pandas 变成浮点（5 → 5.0、None → nan），键就对不上了。"""
    batch_id = row.get("batchId")
    if batch_id is not None:
        return f"id:{int(batch_id)}"
    batch_no = row.get("batchNo")
    return f"no:{batch_no}" if batch_no else "none"


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

    sales = pd.DataFrame({
        "bizDate": [_dt.date.fromisoformat(r["bizDate"]) for r in snap["sales"]],
        "key": [_batch_key(r) for r in snap["sales"]],
        "itemCode": [r["itemCode"] for r in snap["sales"]],
        "qty": [float(r["qty"]) for r in snap["sales"]],
        "revenue": [float(r["revenue"]) for r in snap["sales"]],
    })

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

    # 只在最低折扣线以内的候选档里决策：这样理由与期望销量描述的就是实际给出的价格。
    # 另用全档位再算一次"不受底线约束时会选哪档"，触底时把它带给后端，由后端记 price_floor_hit / raw_discount_rate。
    allowed = tuple(g for g in DISCOUNT_GRID if g >= min_discount_rate - 1e-9)
    pricer = ModelPricer(grid=allowed)
    pricer.update_base_prices(batches)
    pricer.maybe_refit(0, history if not history.empty else None)
    meta = {k: _jsonable(v) for k, v in (pricer.model.meta.items() if pricer.model else [])}
    unconstrained = ModelPricer(grid=DISCOUNT_GRID)
    unconstrained.model = pricer.model

    suggestions: List[dict] = []
    if pricer._model_usable():
        status = "ok"
        by_item: Dict[str, List[dict]] = {}
        for b in batches:
            by_item.setdefault(b["item_code"], []).append(b)
        dow = _dow(biz)
        decided, uncovered, floor_hits = [], [], 0
        for code, rows in sorted(by_item.items()):
            # 模型没有这个单品的参数（窗口内没有可用样本，例如新上架）：不给建议，后端对它回退规则版，
            # 而不是让 predict 返回 0 需求、每档打平、默认"维持原价"冒充模型决策
            if code not in pricer.model.alpha or code not in pricer.model.beta:
                uncovered.append(code)
                continue
            dec = pricer.decide_item(code, rows, dow)
            if dec is None:
                uncovered.append(code)
                continue
            decided.append(code)
            rate = float(snap_to_grid(dec.discount_rate, allowed))
            raw = unconstrained.decide_item(code, rows, dow)
            raw_rate = float(snap_to_grid(raw.discount_rate, DISCOUNT_GRID)) if raw is not None else rate
            floor_hit = raw_rate < rate - 1e-9 and raw_rate < min_discount_rate - 1e-9
            # 触底的说明由后端按 rawDiscountRate 统一写（与其它策略同一句话），这里不重复，免得理由超长被截断
            reason = dec.reason
            if floor_hit:
                floor_hits += 1
            total = sum(r["remain_qty"] for r in rows) or 1.0
            for r in rows:
                row = {
                    "batchId": r["batch_id"], "batchNo": r["batch_no"],
                    "itemId": r["item_id"], "itemCode": code,
                    "discountRate": round(rate, 2),
                    # 模型按单品联合决策给出总期望销量，按批次现存占比分摊
                    "expectedQty": round(float(dec.expected_qty) * r["remain_qty"] / total, 2),
                    "reason": reason,
                }
                if floor_hit:
                    row["rawDiscountRate"] = round(raw_rate, 2)
                suggestions.append(row)
        note = (f"按单品联合决策 {len(decided)} 个单品、{len(suggestions)} 个在售批次；"
                f"训练样本 {meta.get('n_obs_raw')} 行，剔除缺货截断 {meta.get('n_dropped_stockout')} 行"
                f"（期初库存由台账与销量重建，未扣日常损耗，部分断货日可能识别不到）")
        if floor_hits:
            note += f"；{floor_hits} 个单品触到最低折扣线"
        if uncovered:
            note += f"；{len(uncovered)} 个单品模型没有覆盖、不给建议（后端对其回退规则版）：{', '.join(uncovered)}"
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


def _hand_snapshot() -> dict:
    """手算得出期望值的小样本：窗口前到货、两批重叠、一笔没挂批次的销售（见 _selftest 里的期望表）。"""
    return {
        "source": "selftest", "bizDate": "2026-06-10", "historyFrom": "2026-06-05", "historyTo": "2026-06-09",
        "historyDays": 5, "salesFrom": "2026-06-03",
        "items": [{"itemId": 7, "itemCode": "X", "itemName": "手算果", "basePrice": 10.0, "costPrice": 4.0}],
        "batches": [
            {"batchId": 1, "batchNo": "X-1", "itemId": 7, "itemCode": "X", "arriveDate": "2026-06-03",
             "expireDate": "2026-06-08", "initQty": 20.0, "remainQty": 0.0, "costPrice": 4.0, "status": "SOLD_OUT"},
            {"batchId": 2, "batchNo": "X-2", "itemId": 7, "itemCode": "X", "arriveDate": "2026-06-07",
             "expireDate": "2026-06-12", "initQty": 10.0, "remainQty": 7.0, "costPrice": 4.0, "status": "ON_SALE"},
        ],
        "sales": [
            {"bizDate": "2026-06-04", "batchId": 1, "batchNo": "X-1", "itemCode": "X", "qty": 4.0, "revenue": 40.0},
            {"bizDate": "2026-06-05", "batchId": 1, "batchNo": "X-1", "itemCode": "X", "qty": 3.0, "revenue": 27.0},
            {"bizDate": "2026-06-07", "batchId": 1, "batchNo": "X-1", "itemCode": "X", "qty": 2.0, "revenue": 16.0},
            {"bizDate": "2026-06-07", "batchId": 2, "batchNo": "X-2", "itemCode": "X", "qty": 1.0, "revenue": 10.0},
            {"bizDate": "2026-06-08", "batchId": None, "batchNo": None, "itemCode": "X", "qty": 0.5, "revenue": 5.0},
            {"bizDate": "2026-06-08", "batchId": 2, "batchNo": "X-2", "itemCode": "X", "qty": 2.0, "revenue": 18.0},
        ],
    }


def _selftest() -> int:
    # 1) 库存重建：手算期望。先过一遍 JSON（与接口模式一样），让"int + None"的批次列走真实路径
    hand = json.loads(json.dumps(_hand_snapshot()))
    hist = build_history(hand).set_index("biz_date")
    expect = {  # 日期: (期初库存, 当日到货, 净销量)
        "2026-06-05": (16.0, 0.0, 3.0),   # X-1 窗口前已卖 4
        "2026-06-06": (13.0, 0.0, 0.0),   # 当天没卖也是有效样本（有货）
        "2026-06-07": (13.0, 10.0, 3.0),  # X-2 当天到货：算到货，不算期初
        "2026-06-08": (20.0, 0.0, 2.5),   # X-1 到期当天仍在售 11 + X-2 剩 9；没挂批次的 0.5 只算销量
        "2026-06-09": (7.0, 0.0, 0.0),    # X-1 已过期不再计入
    }
    for day, (on_hand, arrival, sold) in expect.items():
        row = hist.loc[_dt.date.fromisoformat(day)]
        got = (float(row["on_hand_start"]), float(row["arrival_qty"]), float(row["sold_qty"]))
        assert np.allclose(got, (on_hand, arrival, sold)), f"{day} 期望 {(on_hand, arrival, sold)}，实际 {got}"

    # 2) 不偷看：往快照里塞业务日当天和以后的成交，历史与决策都不能变
    biz = _dt.date(2026, 6, 29)
    snap = _synthetic_snapshot(biz)
    base_art = build_artifact(snap, min_discount_rate=DEFAULT_MIN_DISCOUNT, generated_at="2026-10-15T00:00:00")
    leaked = json.loads(json.dumps(snap))
    for k in range(3):
        leaked["sales"].append({"bizDate": (biz + _dt.timedelta(days=k)).isoformat(), "batchId": leaked["batches"][-1]["batchId"],
                                "batchNo": leaked["batches"][-1]["batchNo"], "itemCode": "A", "qty": 500.0, "revenue": 1.0})
    leaked_art = build_artifact(leaked, min_discount_rate=DEFAULT_MIN_DISCOUNT, generated_at="2026-10-15T00:00:00")
    assert leaked_art["suggestions"] == base_art["suggestions"] and leaked_art["fit"] == base_art["fit"], "业务日及以后的成交影响了结果（偷看）"
    assert build_history(leaked).equals(build_history(snap)), "业务日及以后的成交进入了训练历史"

    # 3) 产物结构 + 最低折扣线：只在底线以内的档位里决策；触底的行带 rawDiscountRate，理由与期望销量对应实际给出的档位
    art = base_art
    assert art["bizDate"] == biz.isoformat() and art["modelStatus"] == "ok", art["modelNote"]
    assert art["suggestions"], "应至少给出一条建议"
    for s in art["suggestions"]:
        assert s["discountRate"] in DISCOUNT_GRID and s["discountRate"] >= DEFAULT_MIN_DISCOUNT - 1e-9, s
        assert s["batchId"] is not None and s["itemId"] is not None and s["reason"], s
        if "rawDiscountRate" in s:
            assert s["rawDiscountRate"] < DEFAULT_MIN_DISCOUNT < s["discountRate"] + 1e-9, s
    strict = build_artifact(snap, min_discount_rate=0.9, generated_at="2026-10-15T00:00:00")
    assert all(s["discountRate"] >= 0.9 - 1e-9 for s in strict["suggestions"]), "底线 0.9 时出现了更深的折扣"
    for s in strict["suggestions"]:
        label = "维持原价" if s["discountRate"] >= 0.999 else f"打{s['discountRate'] * 10:.0f}折"
        assert label in s["reason"], f"理由描述的档位与实际给出的不一致：{s}"

    # 4) 模型没覆盖的单品（新上架、没有历史）：不给建议，modelNote 里写明，后端会回退规则版
    with_new = json.loads(json.dumps(snap))
    with_new["items"].append({"itemId": 3, "itemCode": "C", "itemName": "新上架", "basePrice": 8.0, "costPrice": 3.0})
    with_new["batches"].append({"batchId": 999, "batchNo": "C-1", "itemId": 3, "itemCode": "C",
                                "arriveDate": biz.isoformat(), "expireDate": (biz + _dt.timedelta(days=2)).isoformat(),
                                "initQty": 50.0, "remainQty": 50.0, "costPrice": 3.0, "status": "ON_SALE"})
    new_art = build_artifact(with_new, min_discount_rate=DEFAULT_MIN_DISCOUNT)
    assert all(s["itemCode"] != "C" for s in new_art["suggestions"]) and "C" in new_art["modelNote"], new_art["modelNote"]

    # 5) 底线以后端为准：接口模式默认用后端给的底线；显式参数与后端不一致直接报错，不导出对不上的产物
    assert resolve_floor(None, {"minDiscountRate": 0.7}) == 0.7
    assert resolve_floor(None, {}) == DEFAULT_MIN_DISCOUNT
    assert resolve_floor(0.6, {"minDiscountRate": 0.60}) == 0.6
    try:
        resolve_floor(0.6, {"minDiscountRate": 0.7})
        raise AssertionError("显式底线与后端不一致时应报错")
    except SystemExit:
        pass

    # 6) 训练样本不足：模型不可用 → 不给建议、状态如实
    thin_art = build_artifact(_synthetic_snapshot(biz, days=3), min_discount_rate=DEFAULT_MIN_DISCOUNT)
    assert thin_art["modelStatus"] != "ok" and not thin_art["suggestions"], thin_art["modelNote"]
    json.dumps(art, ensure_ascii=False, default=_jsonable)  # 能序列化
    print(f"[selftest] OK：库存重建 {len(expect)} 天与手算一致；不偷看；底线；未覆盖单品回退；"
          f"建议 {len(art['suggestions'])} 条；样本不足时状态 {thin_art['modelStatus']}")
    return 0


def resolve_floor(cli_floor: Optional[float], snap: dict) -> float:
    """最低折扣线：后端快照里给了就以后端为准（产物底线与后端配置不一致时，后端会整份不用）。"""
    backend = snap.get("minDiscountRate")
    if backend is None:
        return DEFAULT_MIN_DISCOUNT if cli_floor is None else cli_floor
    backend = float(backend)
    if cli_floor is not None and abs(cli_floor - backend) > 1e-9:
        raise SystemExit(f"--min-discount-rate {cli_floor} 与后端当前配置 app.pricing.min-discount-rate={backend} "
                         "不一致：这样导出的产物后端不会采用。去掉该参数（自动用后端的）或改成一致的值")
    return backend


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="导出在线模型定价产物（pricing_model.json）")
    ap.add_argument("--biz-date", help="要做定价决策的业务日 yyyy-MM-dd（与后端生成建议的业务日一致）")
    ap.add_argument("--api", default=DEFAULT_API, help=f"后端地址，默认 {DEFAULT_API}")
    ap.add_argument("--seed-dir", help="不连后端，改读该目录下的种子 CSV（如 data/seed）")
    ap.add_argument("--history-days", type=int, default=DEFAULT_HISTORY_DAYS, help="训练窗口天数，默认 60")
    ap.add_argument("--min-discount-rate", type=float, default=None,
                    help="最低折扣线。接口模式默认用后端的 app.pricing.min-discount-rate；"
                         "种子模式默认 0.60（后端改过底线时要传一致的值，否则后端不用该产物）")
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
    art = build_artifact(snap, resolve_floor(args.min_discount_rate, snap))
    write_atomic(args.out, art)
    print(f"[export] {art['bizDate']} 模型状态 {art['modelStatus']}，建议 {len(art['suggestions'])} 条 → {args.out}")
    print(f"[export] {art['modelNote']}")
    return 0 if art["modelStatus"] == "ok" else 2


if __name__ == "__main__":
    sys.exit(main())
