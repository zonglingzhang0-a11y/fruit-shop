"""数据流程图（顶层图 / 关联图）：整个系统画成一个处理 P0，只画它和外部实体之间的数据流。

外部实体只有三类（docs/04 §2 角色表 + 实际代码）：店主、店员、顾客/会员。
  - 顾客/会员：H5 特价页对所有顾客匿名开放；站内推送只发给会员。
  - 不画成外部实体的：供应商（docs 里没有这个角色，进货不在范围）；Python 定价脚本（系统内的处理 P4，
    它要写模型定价产物 D10，外部实体不能直接写数据存储）；定时器（每小时只读扫描是 P2 的内部动作）。
顶层图与一层图平衡：这里每条数据流都能在一层图里找到同名的流（一层图 dfd_level1.py）。

用法（仓库根目录）：python3 scripts/mis/dfd_context.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from svgkit import FILL, INK, INK2, MUTED, OUT_DIR, Svg  # noqa: E402

W, H = 1600, 900
LS = 16          # 数据流名称字号
LH = 23          # 名称行距


def tw(s: str, size: float) -> float:
    return sum(size * (0.56 if ord(c) < 128 else 1.0) for c in s)


svg = Svg(W, H, title="数据流程图（顶层图）",
          subtitle="整个系统看成一个处理 P0：店员录入到货、报废与销售，店主定价、推送，顾客看到店主确认的特价")
svg.text(W - 32, 70, "内部分解见《数据流程图（一层图）》", size=13, color=MUTED, anchor="end")


def names(x, y, rows, anchor="middle"):
    """一组数据流名称（同方向合画的一条箭头），y 为第一行基线。"""
    for i, r in enumerate(rows):
        svg.text(x, y + i * LH, r, size=LS, color=INK2, anchor=anchor, halo=True)


# ---------------------------------------------------------------- 符号
PX, PY, PW, PHH = 590, 205, 420, 250          # P0
CY = PY + PHH / 2                               # 330
svg.rect(PX, PY, PW, PHH, fill=FILL["process"], stroke=INK, sw=1.8, rx=18)
svg.line(PX, PY + 34, PX + PW, PY + 34, stroke=INK, sw=1.4)
svg.text(PX + PW / 2, PY + 24, "P0", size=16, color=INK2, anchor="middle", weight=600)
svg.text(PX + PW / 2, CY + 10, "临期果品定价与推送工作台", size=22, anchor="middle", weight=600)
svg.text(PX + PW / 2, CY + 44, "Vue 工作台 · Spring Boot 后端 · Python 定价脚本", size=13.5, color=MUTED, anchor="middle")

EW, EH = 170, 58
svg.entity(110, CY - EH / 2, EW, EH, "店主", size=19)
svg.text(110 + EW / 2, CY + EH / 2 + 26, "决策：定价、推送", size=13, color=MUTED, anchor="middle")
svg.entity(W - 110 - EW, CY - EH / 2, EW, EH, "店员", size=19)
svg.text(W - 110 - EW / 2, CY + EH / 2 + 26, "执行：登记到货与报废、销售回流", size=13, color=MUTED, anchor="middle")
GX = PX + PW / 2 - 100
GY = 625
svg.entity(GX, GY, 200, EH, "顾客/会员", size=19)
svg.text(GX + 100, GY + EH + 26, "H5 匿名看特价；站内推送只发会员", size=13, color=MUTED, anchor="middle")

# ---------------------------------------------------------------- 数据流
LX0, LX1 = 110 + EW, PX                 # 店主 ↔ P0
RX0, RX1 = PX + PW, W - 110 - EW        # P0 ↔ 店员
YU, YD = CY - 30, CY + 30

# 店主
svg.arrow([(LX0, YU), (LX1, YU)])
names((LX0 + LX1) / 2, YU - 10 - 4 * LH, ["定价生成请求", "定价决策（采纳 / 改价 / 不采纳）", "查询日期", "推送请求", "发送确认"])
svg.arrow([(LX1, YD), (LX0, YD)])
names((LX0 + LX1) / 2, YD + 24, ["临期批次清单", "定价建议", "决策回执", "高峰时段、建议推送时间", "推送预览、发送回执",
                                  "全店群发文案", "推送效果、效果统计"])

# 店员
svg.arrow([(RX1, YU), (RX0, YU)])
names((RX0 + RX1) / 2, YU - 10 - 2 * LH, ["到货信息", "报废登记", "销售流水"])
svg.arrow([(RX0, YD), (RX1, YD)])
names((RX0 + RX1) / 2, YD + 24, ["批次登记回执", "报废回执", "临期批次清单", "回流回执"])

# 顾客/会员
XA, XB = PX + PW / 2 - 40, PX + PW / 2 + 40
svg.arrow([(XA, PY + PHH), (XA, GY)])
names(XA - 12, (PY + PHH + GY) / 2 - 6, ["当日特价（店主确认价）", "站内推送消息"], anchor="end")
svg.arrow([(XB, GY), (XB, PY + PHH)])
names(XB + 12, (PY + PHH + GY) / 2 + 6, ["推送打开回执"], anchor="start")

# ---------------------------------------------------------------- 图例与图注
x, y = 32, 800
svg.text(x, y + 15, "图例", size=13, color=MUTED)
x += 44
svg.rect(x + 3, y + 3, 34, 20, fill="#dcdfe6", stroke="none", sw=0)
svg.rect(x, y, 34, 20, fill=FILL["entity"], stroke=INK, sw=1.3)
svg.text(x + 44, y + 15, "外部实体", size=13, color=INK2)
x += 44 + tw("外部实体", 13) + 28
svg.rect(x, y - 2, 40, 26, fill=FILL["process"], stroke=INK, sw=1.3, rx=6)
svg.line(x, y + 7, x + 40, y + 7, stroke=INK, sw=1)
svg.text(x + 50, y + 15, "处理（上栏为编号）", size=13, color=INK2)
x += 50 + tw("处理（上栏为编号）", 13) + 28
svg.arrow([(x, y + 10), (x + 34, y + 10)])
svg.text(x + 44, y + 15, "数据流（一条箭头列多个名称 = 同方向的多条数据流合画）", size=13, color=INK2)

notes = [
    "不画成外部实体：供应商（文档里没有这个角色，进货不在范围）；Python 定价脚本（在系统内，即一层图 P4）；"
    "定时器（每小时只读扫描，是 P2 的内部动作）。",
    "推送只是系统内的站内记录，不接微信/短信；全店群发文案由店主自己转发到微信群。“推送打开回执”在演示中由工作台代为标记。",
]
for i, s in enumerate(notes):
    svg.text(32, 850 + i * 22, s, size=12.5, color=MUTED)

out = svg.save(OUT_DIR / "dfd_context.svg")
print(f"[dfd_context] → {out}")
