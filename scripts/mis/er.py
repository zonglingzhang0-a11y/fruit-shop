"""E-R 图（Chen 记法）：实体画矩形，联系画菱形，连线靠近实体一端标基数 1 / N / M。

内容全部取自建表脚本 src/main/resources/db/schema-h2.sql 与 Service 代码：
  - 8 张业务表各画一个实体：单品、到货批次、定价建议、定价决策、会员、推送任务、推送日志、销售流水；
  - “品类”没有表，以 category_name 作自然键，画成虚线框的概念实体，这样“会员—偏好—品类”是 M:N，
    联系属性（购买次数 / 最近购买日 / 偏好分）转换后就是关系模式 member_pref；
  - 不画属性椭圆（属性放在文档的关系模式表里），实体框里只写表名；
  - 连线：实线 = 外键列 NOT NULL（必选联系），虚线 = 外键列可空（可选联系）。
不画的辅助表、未画的逻辑外键见图下脚注。

用法（仓库根目录）：python3 scripts/mis/er.py
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from svgkit import FILL, INK, INK2, MUTED, OUT_DIR, Svg  # noqa: E402

W, H = 1600, 900
EW, EH = 132, 60          # 实体框
DH = 50                   # 菱形高
DASH = "7 5"              # 可选联系的虚线


def tw(s: str, size: float) -> float:
    """粗略估计文字宽度：中文按 1 个字号，ASCII 按 0.56 个字号。"""
    return sum(size * (0.56 if ord(c) < 128 else 1.0) for c in s)


# ---------------------------------------------------------------- 几何
class Ent:
    def __init__(self, name, table, cx, cy, concept=False):
        self.name, self.table, self.cx, self.cy, self.concept = name, table, cx, cy, concept
        self.w, self.h = EW, EH

    def clip(self, dx, dy):
        """从中心沿 (dx, dy) 方向射出，与矩形边界的交点。"""
        tx = (self.w / 2) / abs(dx) if dx else math.inf
        ty = (self.h / 2) / abs(dy) if dy else math.inf
        t = min(tx, ty)
        return self.cx + dx * t, self.cy + dy * t


class Rel:
    def __init__(self, label, cx, cy):
        self.label, self.cx, self.cy = label, cx, cy
        self.w, self.h = 44 + 20 * len(label), DH

    def clip(self, dx, dy):
        """从中心沿 (dx, dy) 方向射出，与菱形边界的交点。"""
        t = 1 / (abs(dx) / (self.w / 2) + abs(dy) / (self.h / 2))
        return self.cx + dx * t, self.cy + dy * t


# ---------------------------------------------------------------- 本图自带的形状
def draw_entity(svg: Svg, e: Ent):
    x, y = e.cx - e.w / 2, e.cy - e.h / 2
    if e.concept:   # 概念实体：虚线框、白底
        svg.rect(x, y, e.w, e.h, fill="#ffffff", stroke=INK, sw=1.6, dash="6 4")
    else:
        svg.rect(x, y, e.w, e.h, fill=FILL["entity"], stroke=INK, sw=1.6)
    svg.text(e.cx, e.cy - 3, e.name, size=17, anchor="middle", weight=600)
    svg.text(e.cx, e.cy + 18, e.table, size=12, color=MUTED, anchor="middle")


def draw_rel(svg: Svg, r: Rel):
    svg.diamond(r.cx, r.cy, r.w, r.h, r.label, size=15)


CARDS: list[tuple[float, float, str]] = []


def link(svg: Svg, e: Ent, r: Rel, card: str, optional=False, side=None, anchor=None, back=17, off=13):
    """实体 e 与联系 r 之间的一条连线（无箭头），并在靠近实体一端标基数。

    anchor: 实体边上的固定连接点（默认沿两中心连线裁剪）；side: 基数标在连线的哪一侧（+1 / -1）。
    """
    if anchor:
        ax, ay = anchor
        dx, dy = r.cx - ax, r.cy - ay
    else:
        dx, dy = r.cx - e.cx, r.cy - e.cy
        ax, ay = e.clip(dx, dy)
    bx, by = r.clip(-dx, -dy)
    svg.line(ax, ay, bx, by, stroke=INK, sw=1.6, dash=DASH if optional else None)
    # 基数：沿线离开实体 back 像素，再向一侧偏 off 像素
    n = math.hypot(dx, dy)
    ux, uy = dx / n, dy / n
    px, py = -uy, ux                      # 法向
    if side is None:                      # 默认：横线标上方，竖线标右侧
        side = 1 if (py < 0 if abs(uy) < 0.6 else px > 0) else -1
    lx, ly = ax + ux * back + px * off * side, ay + uy * back + py * off * side
    CARDS.append((lx, ly, card))


def note(svg: Svg, x, y, rows, size=12.5, color=INK2, anchor="start"):
    for i, r in enumerate(rows):
        svg.text(x, y + i * size * 1.35, r, size=size, color=color, anchor=anchor)


# ---------------------------------------------------------------- 画布
svg = Svg(W, H, title="E-R 图",
          subtitle="临期果品定价与推送工作台的概念模型：9 个实体、13 个联系（Chen 记法）")
svg.text(W - 32, 70, "依据：src/main/resources/db/schema-h2.sql 与各 Service 代码", size=13, color=MUTED,
         anchor="end")

Y1, Y2, Y3 = 190, 440, 690
CAT = Ent("品类", "无表 · category_name", 120, Y1, concept=True)
ITEM = Ent("单品", "fruit_item", 440, Y1)
BATCH = Ent("到货批次", "fruit_batch", 790, Y1)
SUGG = Ent("定价建议", "pricing_suggestion", 1140, Y1)
DECI = Ent("定价决策", "pricing_decision", 1460, Y1)
SALE = Ent("销售流水", "sales_record", 615, Y2)
MEM = Ent("会员", "member", 120, Y3)
LOG = Ent("推送日志", "push_log", 615, Y3)
TASK = Ent("推送任务", "push_task", 1140, Y3)
ENTS = [CAT, ITEM, BATCH, SUGG, DECI, SALE, MEM, LOG, TASK]

R_BELONG = Rel("属于", 280, Y1)                        # 单品 N : 1 品类
R_OWN = Rel("拥有", 615, Y1)                           # 单品 1 : N 到货批次
R_GEN = Rel("生成", 965, Y1)                           # 到货批次 1 : N 定价建议
R_DECIDE = Rel("决策", 1300, Y1)                       # 定价建议 1 : N 定价决策
R_PREF = Rel("偏好", 120, Y2)                          # 会员 M : N 品类
R_SOLD = Rel("属于", 527, 315)                         # 销售流水 N : 1 单品
R_FROM = Rel("出自", 703, 315)                         # 销售流水 N : 1 到货批次（batch_id 可空）
R_PUSHB = Rel("推送", 965, Y2)                         # 到货批次 1 : N 推送任务（可空）
R_BASIS = Rel("依据", 1140, Y2)                        # 定价建议 1 : N 推送任务（可空）
R_BUY = Rel("购买", 370, 560)                          # 会员 1 : N 销售流水（可空）
R_CONV = Rel("转化为", 615, 565)                       # 推送日志 1 : 1 销售流水（两边可选）
R_RECV = Rel("接收", 367, Y3)                          # 会员 1 : N 推送日志（可空）
R_PROD = Rel("产生", 877, Y3)                          # 推送任务 1 : N 推送日志
RELS = [R_BELONG, R_OWN, R_GEN, R_DECIDE, R_PREF, R_SOLD, R_FROM, R_PUSHB, R_BASIS, R_BUY, R_CONV, R_RECV,
        R_PROD]

# ---------------------------------------------------------------- 连线（先画线，再画框盖住线头）
# 第一行：品类 — 单品 — 到货批次 — 定价建议 — 定价决策
link(svg, CAT, R_BELONG, "1")
link(svg, ITEM, R_BELONG, "N")
link(svg, ITEM, R_OWN, "1")
link(svg, BATCH, R_OWN, "N")
link(svg, BATCH, R_GEN, "1")
link(svg, SUGG, R_GEN, "N")
link(svg, SUGG, R_DECIDE, "1")
link(svg, DECI, R_DECIDE, "N")
# 会员 — 偏好 — 品类（M:N）
link(svg, CAT, R_PREF, "N")
link(svg, MEM, R_PREF, "M")
# 销售流水 — 单品 / 到货批次
link(svg, ITEM, R_SOLD, "1", side=-1, back=24)
link(svg, SALE, R_SOLD, "N", side=-1, back=30)
link(svg, BATCH, R_FROM, "1", optional=True, side=1, back=24)
link(svg, SALE, R_FROM, "N", optional=True, side=1, back=30)
# 推送任务 — 到货批次 / 定价建议
link(svg, BATCH, R_PUSHB, "1", optional=True, side=-1, back=24)
link(svg, TASK, R_PUSHB, "N", optional=True, side=-1, anchor=(TASK.cx - 30, TASK.cy - EH / 2), back=30)
link(svg, SUGG, R_BASIS, "1", optional=True)
link(svg, TASK, R_BASIS, "N", optional=True)
# 会员 — 购买 — 销售流水；会员 — 接收 — 推送日志
link(svg, MEM, R_BUY, "1", optional=True, anchor=(MEM.cx + 50, MEM.cy - EH / 2), side=-1, back=30)
link(svg, SALE, R_BUY, "N", optional=True, anchor=(SALE.cx - EW / 2, SALE.cy + 12), side=1)
link(svg, MEM, R_RECV, "1", optional=True)
link(svg, LOG, R_RECV, "N", optional=True)
# 推送日志 — 转化为 — 销售流水（1:1，两边都可选）
link(svg, SALE, R_CONV, "1", optional=True)
link(svg, LOG, R_CONV, "1", optional=True)
# 推送任务 — 产生 — 推送日志
link(svg, TASK, R_PROD, "1")
link(svg, LOG, R_PROD, "N")

for e in ENTS:
    draw_entity(svg, e)
for r in RELS:
    draw_rel(svg, r)
for x, y, c in CARDS:
    svg.text(x, y + 5.5, c, size=16, anchor="middle", weight=700, halo=True)

# ---------------------------------------------------------------- 联系旁的说明（灰色小字）
# 偏好：M:N 联系的属性 → 关系模式 member_pref
nx, ny, nw, nh = 178, 404, 206, 72
svg.line(R_PREF.cx + R_PREF.w / 2, R_PREF.cy, nx, R_PREF.cy, stroke=MUTED, sw=1.2, dash="2 3")
svg.rect(nx, ny, nw, nh, fill="#fafafa", stroke=MUTED, sw=1.1, rx=6)
svg.text(nx + 10, ny + 21, "联系属性", size=12.5, color=INK, weight=600)
note(svg, nx + 10, ny + 41, ["购买次数 · 最近购买日 · 偏好分", "→ 转换为关系 member_pref"], size=12.5)
# 决策：同一建议可多次决策
note(svg, R_DECIDE.cx, R_DECIDE.cy + 44, ["每次确认/改价新增一行", "以 id 最大者为准"], size=12, anchor="middle")
# 转化为：归因规则
note(svg, R_CONV.cx + R_CONV.w / 2 + 14, R_CONV.cy - 4, ["同会员、同单品、3 小时内", "一条销售只归因一条日志"], size=12)

# ---------------------------------------------------------------- 图例（右下空白处）
lx, ly, lw, lh = 1290, 318, 250, 290
svg.rect(lx, ly, lw, lh, fill="#ffffff", stroke="#c0c4cc", sw=1.2, rx=6)
svg.text(lx + 16, ly + 26, "图例", size=13, color=MUTED)
x0, yy = lx + 16, ly + 44
sx = x0 + 52          # 说明文字起点


def legend_row(desc):
    global yy
    svg.text(sx, yy + 15, desc, size=13, color=INK2)
    yy += 36


svg.rect(x0, yy, 40, 22, fill=FILL["entity"], stroke=INK, sw=1.3)
legend_row("实体（对应一张表）")
svg.rect(x0, yy, 40, 22, fill="#ffffff", stroke=INK, sw=1.3, dash="5 3")
legend_row("概念实体（无表）")
svg.diamond(x0 + 20, yy + 11, 40, 24, "", size=1)
legend_row("联系")
svg.line(x0, yy + 11, x0 + 40, yy + 11, stroke=INK, sw=1.6)
legend_row("必选联系（外键非空）")
svg.line(x0, yy + 11, x0 + 40, yy + 11, stroke=INK, sw=1.6, dash=DASH)
legend_row("可选联系（外键可空）")
svg.text(x0 + 20, yy + 16, "1 N M", size=14, anchor="middle", weight=700)
legend_row("基数，标在实体一侧")
svg.rect(x0, yy, 40, 22, fill="#fafafa", stroke=MUTED, sw=1.1, rx=4)
legend_row("联系属性 / 说明")

# ---------------------------------------------------------------- 脚注
fy = 790
svg.text(32, fy, "注：", size=13, color=INK2, weight=600)
notes = [
    "① 品类没有单独建表，以 category_name 作自然键（存于 fruit_item、member_pref）；M:N 联系“偏好”"
    "转换为关系 member_pref，唯一键 (member_id, category_name)。",
    "② 建表脚本未声明任何 FOREIGN KEY 约束：图中联系都是 *_id 列构成的逻辑外键，由 Service 代码维护；"
    "实体框中小字为表名，各表主键均为自增 id。",
    "③ 未画：辅助表 shop_traffic_hourly、shop_traffic_profile、strategy_daily_stat、seed_load_log、health_check；"
    "逻辑外键 pricing_suggestion.item_id（冗余）、push_task.related_item_id。",
]
for i, s in enumerate(notes):
    svg.text(62, fy + i * 26, s, size=13, color=INK2)

out = svg.save(OUT_DIR / "er.svg")
print(f"[er] → {out}")
