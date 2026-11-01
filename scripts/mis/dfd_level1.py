"""数据流程图（一层图）：把顶层图的 P0 分解成 P1–P8 八个处理，画出 D1–D10 十个数据存储和三个外部实体之间的数据流。

内容依据（2026-11 对照代码核实）：
  - 处理 = 真实模块：P1/P2 batch（P1 含 FR-06 报废人工登记 POST /api/batches/{id}/scrap），P3 pricing，P4 scripts/export_model_suggestions.py，P5 pricing + batch(on-sale)，
    P6 traffic，P7 push，P8 sales（Controller 路径见各处理框下的小字）。
  - 数据存储 = 建表脚本里的真实表（src/main/resources/db/schema-h2.sql），D10 是 MODEL 策略读取的文件产物。
  - Python 定价模块画成处理 P4（在系统边界内）：docs/01 §1 架构图把它列为系统的一部分；它读系统自己的
    D1/D2/D8（经 GET /api/pricing/model-input），写 D10，D10 再被 P3 读取。外部实体不能直接读写数据存储，
    所以它不能画成外部实体。
  - 报废登记（BatchScrapService）：店员填批次、数量（空 = 整批）、原因；写 D2（现存↓、累计报废↑、整批报废置 SCRAPPED、
    留痕列），读 D3 找该批次生效策略，把报废量计入 D9 当日该策略的损耗（没有定价建议的批次不计入）。
  - 不画：供应商（docs 里没有这个角色）、FIXED 打折（FIXED 现在保持原价）。

为图面清晰，下列读取没有画（图注里写明）：P2/P5/P7 读 D1 取品名和原价；P3 生成前读 D3 做幂等与调价计数；
P6 的“重算周内客流规律”（POST /api/traffic/profile/recompute，没有页面调用）。

用法（仓库根目录）：python3 scripts/mis/dfd_level1.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from svgkit import FILL, INK, INK2, MUTED, OUT_DIR, Svg  # noqa: E402

W, H = 1600, 900
PH, SH, EH = 80, 34, 42          # 处理 / 数据存储 / 外部实体 的高度
LS = 12                          # 数据流名称字号


def tw(s: str, size: float) -> float:
    """粗略估计文字宽度：中文按 1 个字号，ASCII 按 0.56 个字号。"""
    return sum(size * (0.56 if ord(c) < 128 else 1.0) for c in s)


svg = Svg(W, H, title="数据流程图（一层图）",
          subtitle="把顶层图的 P0 分解为 8 个处理、10 个数据存储；系统只给建议，改价和推送都由店主确认")
svg.text(W - 32, 70, "依据：代码（Controller / Service / schema-h2.sql）与 docs/01、03、04", size=13, color=MUTED, anchor="end")


# ---------------------------------------------------------------- 本图用到的符号（在 svgkit 基础上加“重复”标记）
def proc(pid, x, y, w, name, tag, h=PH):
    """处理：圆角矩形，上栏编号，中间处理名，下面一行灰字写对应模块。"""
    svg.rect(x, y, w, h, fill=FILL["process"], stroke=INK, sw=1.6, rx=12)
    svg.line(x, y + 24, x + w, y + 24, stroke=INK, sw=1.2)
    svg.text(x + w / 2, y + 17, pid, size=12, color=INK2, anchor="middle", weight=600)
    cy = y + 24 + (h - 24) / 2
    rows = tag.split("\n")
    if len(rows) == 1:
        svg.text(x + w / 2, cy - 1, name, size=14, anchor="middle", weight=600)
        svg.text(x + w / 2, cy + 17, tag, size=11.5, color=MUTED, anchor="middle")
    else:                         # 两行模块说明：处理名上移
        svg.text(x + w / 2, cy - 7, name, size=14, anchor="middle", weight=600)
        for i, r in enumerate(rows):
            svg.text(x + w / 2, cy + 10 + i * 13, r, size=11, color=MUTED, anchor="middle")


def store(sid, x, y, w, name, dup=False):
    """数据存储：左格编号、右侧开口；dup=True 时左边多画一条竖线（重复画出的同一存储）。"""
    svg.store(x, y, w, SH, name, sid=sid, size=13)
    if dup:
        svg.line(x + 7, y, x + 7, y + SH, stroke=INK, sw=1.2)


def ent(x, y, w, name, dup=False):
    """外部实体：带阴影的方框；dup=True 时左边多画一条竖线（重复画出的同一实体）。"""
    svg.entity(x, y, w, EH, name, size=15)
    if dup:
        svg.line(x + 7, y, x + 7, y + EH, stroke=INK, sw=1.3)


def lab(x, y, s, anchor="middle"):
    """数据流名称：灰字、白色描边；多行用 \\n，y 为第一行基线。"""
    for i, row in enumerate(s.split("\n")):
        svg.text(x, y + i * (LS + 2), row, size=LS, color=INK2, anchor=anchor, halo=True)


def flow(points, name=None, at=None, anchor="middle"):
    svg.arrow(points)
    if name:
        lab(at[0], at[1], name, anchor)


def vflow(x, y_from, y_to, name, side, ly=None):
    """竖直数据流，名称写在线的左侧（side='l'）或右侧（'r'）。"""
    flow([(x, y_from), (x, y_to)])
    rows = name.split("\n")
    ly = (y_from + y_to) / 2 + 4 - (len(rows) - 1) * (LS + 2) / 2 if ly is None else ly
    lab(x - 6 if side == "l" else x + 6, ly, name, "end" if side == "l" else "start")


# ---------------------------------------------------------------- 纵向分层
TOP_P4 = 96                      # P4（离线脚本）与 D10
S2 = 196                         # 第一行处理上方的数据存储
R1 = 286                         # 第一行处理（顶边）
C1 = R1 + PH / 2                 # 326
BAND = 424                       # 外部实体带（店员、店主）
R2 = 524                         # 第二行处理（顶边）
S3 = 664                         # 第二行处理下方的数据存储
S4 = 730

# ---------------------------------------------------------------- 第一行：到货 → 临期 → 定价 → 确认/公示
store("D1", 20, 291, 130, "单品档案")
store("D9", 20, 335, 130, "策略效果统计", dup=True)
proc("P1", 210, R1, 160, "到货与报废登记", "batch · POST /batches\n与 /batches/{id}/scrap")
store("D6", 400, C1 - SH / 2, 120, "客流统计")
proc("P2", 580, R1, 140, "临期识别", "batch · 含每小时扫描")
proc("P3", 870, R1, 140, "定价建议生成", "pricing · generate\n每单品一条建议")
store("D4", 1080, 290, 120, "定价决策")
store("D3", 1080, 332, 120, "定价建议")
proc("P5", 1270, R1, 140, "定价确认与特价公示", "pricing · batch/on-sale")
ent(1480, C1 - EH / 2, 100, "顾客/会员")

# 第一行上方
proc("P4", 520, TOP_P4, 240, "模型定价计算", "Python 离线脚本 · 人工运行", h=64)
store("D10", 840, 111, 180, "模型定价产物（文件）")
store("D3", 88, S2, 147, "定价建议", dup=True)
store("D2", 250, S2, 430, "批次台账（含临期判定指标）")
store("D8", 700, S2, 140, "销售流水")
store("D1", 930, S2, 140, "单品档案", dup=True)
store("D2", 1270, S2, 150, "批次台账", dup=True)

# ---- P1
flow([(150, 308), (210, 308)], "单品档案", (180, 302))
flow([(210, 352), (150, 352)], "报废量", (180, 367))
vflow(225, S2 + SH, R1, "生效策略", "l")
vflow(330, R1, S2 + SH, "新批次 / 报废记录", "r")
# ---- P2
flow([(520, C1), (580, C1)], "营业天数", (550, C1 - 6))
vflow(600, S2 + SH, R1, "在售批次", "l")
vflow(625, R1, S2 + SH, "临期\n判定指标", "r")
vflow(710, S2 + SH, R1, "近7天销量", "r")
flow([(720, 340), (870, 340)], "在售批次及临期指标", (795, 334))
# ---- P4（离线）
flow([(60, 291), (60, 128), (520, 128)], "单品档案", (290, 122))
vflow(560, S2, TOP_P4 + 64, "批次数据", "l")
vflow(740, S2, TOP_P4 + 64, "历史销量", "r")
flow([(760, 128), (840, 128)], "模型定价产物", (800, 122))
# ---- P3
vflow(900, 145, R1, "模型建议", "r", ly=272)
vflow(990, S2 + SH, R1, "原价", "r")
flow([(1080, 306), (1010, 306)], "改价次数", (1045, 300))
flow([(1010, 349), (1080, 349)], "定价建议", (1045, 365))
# ---- P5
vflow(1340, S2 + SH, R1, "在售批次", "l")
flow([(1200, 298), (1270, 298)], "改价次数", (1235, 292))
flow([(1270, 314), (1200, 314)], "决策记录", (1235, 328))
flow([(1200, 352), (1270, 352)], "待确认建议", (1235, 368))
flow([(1410, C1), (1480, C1)], "当日特价", (1445, C1 - 6))

# ---------------------------------------------------------------- 外部实体带
ent(230, BAND, 100, "店员")
ent(600, BAND, 90, "店主")
store("D4", 715, BAND + (EH - SH) / 2, 145, "定价决策", dup=True)
ent(890, BAND, 100, "店主", dup=True)
ent(1290, BAND, 100, "店主", dup=True)

YM1 = (R1 + PH + BAND) / 2 + 4      # 第一行与实体带之间的标注基线
YM2 = (BAND + EH + R2) / 2 + 4      # 实体带与第二行之间
vflow(265, BAND, R1 + PH, "到货信息\n报废登记（批次·数量·原因）", "l")
vflow(295, R1 + PH, BAND, "批次登记回执\n报废回执", "r")
flow([(595, R1 + PH), (595, 435), (330, 435)], "临期批次清单", (462, 429))
vflow(670, R1 + PH, BAND, "临期批次清单", "r")
vflow(925, BAND, R1 + PH, "定价生成请求", "l")
vflow(955, R1 + PH, BAND, "定价建议", "r")
vflow(1325, BAND, R1 + PH, "定价决策", "l")
vflow(1355, R1 + PH, BAND, "决策回执", "r")

# ---------------------------------------------------------------- 第二行：销售回流 · 推送 · 客流
store("D9", 20, 543, 130, "策略效果统计")
proc("P8", 230, R2, 160, "销售回流与效果统计", "sales · feedback / effect")
store("D7", 540, 547, 160, "推送计划与日志")
proc("P7", 840, R2, 260, "推送计划与发送", "push · preview / send / open / stats")
ent(1190, 545, 88, "顾客/会员", dup=True)
proc("P6", 1310, R2, 140, "客流时段分析", "traffic · hourly / peaks")

# 实体带 ↔ 第二行
vflow(265, BAND + EH, R2, "销售流水", "l")
vflow(295, R2, BAND + EH, "回流回执", "r")
flow([(370, R2), (370, 455), (600, 455)], "效果统计", (485, 470))
vflow(850, BAND + (EH + SH) / 2, R2, "店主确认价", "l")
vflow(925, BAND + EH, R2, "推送请求\n发送确认", "l")
vflow(955, R2, BAND + EH, "推送预览、发送回执\n全店群发文案\n推送效果", "r", ly=489)
vflow(1090, 366, R2, "定价建议", "r", ly=YM2)
vflow(1325, BAND + EH, R2, "查询日期", "l")
vflow(1355, R2, BAND + EH, "高峰时段\n建议推送时间", "r")

# ---- P8
flow([(230, 551), (150, 551)], "策略日累加", (190, 545))
flow([(150, 569), (230, 569)], "策略日统计", (190, 584))
flow([(390, 552), (540, 552)], "转化归因", (465, 546))
flow([(540, 576), (390, 576)], "3小时内送达推送", (465, 591))
store("D2", 130, S3, 130, "批次台账", dup=True)
store("D8", 330, S3, 140, "销售流水", dup=True)
store("D3", 260, S4, 130, "定价建议", dup=True)
vflow(250, R2 + PH, S3, "库存扣减", "l")
vflow(360, R2 + PH, S3, "销售记录", "r")
vflow(305, S4, R2 + PH, "生效策略", "l", ly=718)
# ---- P7
flow([(700, 552), (840, 552)], "当日已送达条数", (770, 546))
flow([(840, 576), (700, 576)], "推送计划、推送日志", (770, 591))
flow([(1100, 566), (1190, 566)], "站内推送消息", (1145, 560))
flow([(1190, 588), (1100, 588)], "推送打开回执", (1145, 603))
flow([(1310, 534), (1100, 534)], "建议推送时间", (1205, 528))
store("D5", 850, S3, 140, "会员及偏好")
store("D2", 1010, S3, 140, "批次台账", dup=True)
vflow(900, S3, R2 + PH, "目标会员\n推送开关", "r")
vflow(1060, S3, R2 + PH, "在售批次", "r")
# ---- P6
store("D6", 1310, S3, 140, "客流统计", dup=True)
vflow(1380, S3, R2 + PH, "分时成交笔数", "r")

# ---------------------------------------------------------------- 图例与图注
def h_legend(x, y):
    svg.text(x, y + 15, "图例", size=13, color=MUTED)
    x += 44

    def label(s):
        nonlocal x
        svg.text(x, y + 15, s, size=13, color=INK2)
        x += tw(s, 13) + 28

    svg.rect(x + 3, y + 3, 34, 20, fill="#dcdfe6", stroke="none", sw=0)
    svg.rect(x, y, 34, 20, fill=FILL["entity"], stroke=INK, sw=1.3)
    x += 44
    label("外部实体")
    svg.rect(x, y - 2, 40, 26, fill=FILL["process"], stroke=INK, sw=1.3, rx=6)
    svg.line(x, y + 7, x + 40, y + 7, stroke=INK, sw=1)
    x += 50
    label("处理（上栏为编号）")
    svg.rect(x, y, 56, 20, fill=FILL["store"], stroke="none", sw=0)
    svg.line(x, y, x + 56, y, stroke=INK, sw=1.3)
    svg.line(x, y + 20, x + 56, y + 20, stroke=INK, sw=1.3)
    svg.line(x, y, x, y + 20, stroke=INK, sw=1.3)
    svg.line(x + 22, y, x + 22, y + 20, stroke=INK, sw=1)
    x += 66
    label("数据存储（左格为编号）")
    svg.arrow([(x, y + 10), (x + 34, y + 10)])
    x += 44
    label("数据流（箭头上写名称）")
    svg.rect(x + 3, y + 3, 34, 20, fill="#dcdfe6", stroke="none", sw=0)
    svg.rect(x, y, 34, 20, fill=FILL["entity"], stroke=INK, sw=1.3)
    svg.line(x + 6, y, x + 6, y + 20, stroke=INK, sw=1.2)
    x += 44
    label("左边多一竖线：同一实体/存储重复画出（为避免交叉）")


h_legend(32, 790)
notes = [
    "表：D1 fruit_item · D2 fruit_batch · D3 pricing_suggestion · D4 pricing_decision · D5 member、member_pref · "
    "D6 shop_traffic_hourly、shop_traffic_profile · D7 push_task、push_log · D8 sales_record · "
    "D9 strategy_daily_stat · D10 文件 data/seed/pricing_model.json",
    "P4 经 GET /api/pricing/model-input 读 D1/D2/D8；产物不可用时 P3 回退规则版。D5、D6 运行时只读（种子数据装载）；"
    "推送只是站内记录，不接微信/短信；“打开回执”演示中由工作台代标记。",
    "为图面清晰未画：P2/P5/P7 读 D1 取品名和原价；P3 生成前读 D3 做幂等与调价计数。一条箭头写两个名称 = 同方向的两条数据流合画。",
]
for i, s in enumerate(notes):
    svg.text(32, 836 + i * 21, s, size=12.5, color=MUTED)

out = svg.save(OUT_DIR / "dfd_level1.svg")
print(f"[dfd_level1] → {out}")
