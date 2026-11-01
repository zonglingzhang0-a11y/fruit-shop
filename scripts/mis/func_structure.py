"""功能结构图：临期果品定价与推送工作台，按代码实际实现画成三层树。

第一层是系统名（frontend/src/App.vue 页头），第二层是 8 个子系统，第三层是功能模块（竖排文字框）。
每个模块都对应代码里的接口、页面、定时任务或脚本，出处见各条注释；代码里没有的功能一律不画
（登录 / 权限 / 用户管理、会员管理、新增单品页面、自动改价、自动发送）。

框的三种画法：
  - 实线白底：工作台在线功能（页面操作，或后台定时 / 启动时自动执行）
  - 虚线白底：仅接口、无页面（前端没有页面调用它）
  - 浅色底双线框：离线脚本（scripts/ 下的 Python，不在系统里运行）

用法（仓库根目录）：python3 scripts/mis/func_structure.py
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from svgkit import FILL, INK, INK2, MUTED, OUT_DIR, Svg  # noqa: E402

W, H = 1600, 900
ON, API, OFF = "on", "api", "off"
OFF_FILL = "#fbecd5"     # 离线脚本底色（比 svgkit 的数据存储底色稍深，黑白打印时也呈浅灰）
DASH = "5 3"

# (子系统, 对应页面说明, [(模块名, 类型)])，模块名 ≤ 8 字；出处路径：J/ = src/main/java/com/fruit/shop/，
# F/ = frontend/src/，S/ = scripts/
TREE = [
    ("批次与临期管理", "页面：临期清单", [
        ("商品档案维护", API),   # GET/POST /api/items（J/item/ItemController.java:30,36），新增单品无页面
        ("到货批次登记", ON),    # POST /api/batches；F/views/NearExpiryView.vue 登记卡片
        ("临期清单查询", ON),    # GET /api/batches/near-expiry
        ("风险批次识别", ON),    # GET /api/batches/at-risk；效果看板"高风险库存"
        ("临期定时扫描", ON),    # J/batch/BatchScanJob.java，每小时只读扫描
        ("报废人工登记", ON),    # POST /api/batches/{id}/scrap（BatchController.java:49，BatchScrapService）；
                                 # 临期清单页每行「登记报废」对话框（NearExpiryView.vue）
    ]),
    ("定价决策支持", "页面：定价建议", [
        ("定价建议生成", ON),    # POST /api/pricing/suggestions/generate
        ("建议查询展示", ON),    # GET /api/pricing/suggestions
        ("店主确认改价", ON),    # POST /api/pricing/decisions
        ("模型状态检查", ON),    # GET /api/pricing/model-status（PricingView.vue:35）
        ("模型输入导出", API),   # GET /api/pricing/model-input，供 S/export_model_suggestions.py 调用
    ]),
    ("客流分析", "页面：客流时段", [
        ("时段客流统计", ON),    # GET /api/traffic/hourly
        ("高峰时段识别", ON),    # GET /api/traffic/peaks
        ("周内客流规律", API),   # GET /api/traffic/profile
        ("客流规律重算", API),   # POST /api/traffic/profile/recompute
    ]),
    ("精准推送", "页面：推送消息", [
        ("推送计划预览", ON),    # POST /api/push/preview（只建计划，不发送）
        ("推送定向发送", ON),    # POST /api/push/send
        ("群发文案复制", ON),    # PushView.vue"复制文案"按钮
        ("推送效果统计", ON),    # GET /api/push/stats、/api/push/tasks
        ("打开转化归因", ON),    # POST /api/push/open、/api/push/attribute
    ]),
    ("销售回流", "页面：销售回流", [
        ("销售成交登记", ON),    # POST /api/sales/feedback
        ("退货回补登记", ON),    # 同一接口，isReturn=true
        ("在售批次选取", ON),    # GET /api/batches/in-stock（先进先出）
        ("策略统计累加", ON),    # J/sales/StrategyStatService.java，成交时自动累加
    ]),
    ("效果评价", "页面：效果看板", [
        ("策略效果看板", ON),    # GET /api/sales/effect；DashboardView.vue
        ("首页待办提醒", ON),    # DashboardView.vue 三个待办入口
        ("三策略离线评测", OFF),  # S/evaluate_strategies.py → release/evaluation-report.md
        ("弹性敏感性分析", OFF),  # S/sensitivity_elasticity.py → release/sensitivity-elasticity.md
    ]),
    ("顾客端 H5", "页面：H5 特价页", [
        ("当日特价列表", ON),    # GET /api/batches/on-sale；路由 #/h5
        ("折扣与余量提示", ON),  # H5SpecialsView.vue 折扣徽标、"仅剩 N 份"
        ("合计可省提示", ON),    # H5SpecialsView.vue 页脚"合计可省约 ¥X"
    ]),
    ("系统管理与\n数据初始化", "无页面（启动时 / 配置文件）", [
        ("系统健康自检", API),   # GET /api/health（前端未调用）
        ("库表初始化", ON),      # J/config/SchemaInitConfig.java，schema-h2/mysql.sql
        ("种子数据导入", ON),    # J/support/SeedDataLoader.java，data/seed/shop_*.csv
        ("仿真种子生成", OFF),   # S/simulator.py、S/calibrate_from_public.py
        ("业务参数配置", ON),    # application.yml app.pricing/push/scan → AppProperties
    ]),
]


def tw(s: str, size: float) -> float:
    """粗略估计文字宽度：中文按 1 个字号，ASCII 按 0.56 个字号。"""
    return sum(size * (0.56 if ord(c) < 128 else 1.0) for c in s)


def leaf(svg: Svg, x, y, w, h, label, kind, size=18, gap=1.28):
    """竖排模块框；按类型区分边框与底色（svgkit.vbox 不支持虚线 / 双线，故在此自画）。"""
    if kind == OFF:
        svg.rect(x, y, w, h, fill=OFF_FILL, stroke=INK, sw=1.4, rx=3)
        svg.rect(x + 3.5, y + 3.5, w - 7, h - 7, fill="none", stroke=INK, sw=0.9, rx=2)
    elif kind == API:
        svg.rect(x, y, w, h, fill="#fff", stroke=INK, sw=1.4, rx=3, dash=DASH)
    else:
        svg.rect(x, y, w, h, fill="#fff", stroke=INK, sw=1.4, rx=3)
    chars = list(label)
    lh = size * gap
    y0 = y + h / 2 - (len(chars) - 1) * lh / 2 + size * 0.36
    for i, c in enumerate(chars):
        svg.text(x + w / 2, y0 + i * lh, c, size=size, anchor="middle")


# ---------------------------------------------------------------- 画布
n_mod = sum(len(m) for _, _, m in TREE)
n_api = sum(1 for _, _, m in TREE for _, k in m if k == API)
n_off = sum(1 for _, _, m in TREE for _, k in m if k == OFF)
svg = Svg(W, H, title="功能结构图",
          subtitle=f"按代码实际实现整理：{len(TREE)} 个子系统、{n_mod} 个功能模块；系统只给建议，不自动改价、不自动发送")
svg.text(W - 32, 70, "依据：后端各 Controller 与定时任务、前端路由与菜单、scripts/ 离线脚本",
         size=13, color=MUTED, anchor="end")

# ---------------------------------------------------------------- 几何参数
LW, LG = 34, 6             # 模块框宽、同组模块间距
MARGIN = 32
ROOT_Y, ROOT_H, ROOT_W = 116, 60, 380
BUS1 = 222                 # 第一层汇流线
SUB_Y, SUB_H = 258, 64
BUS2 = 364                 # 第二层汇流线
LEAF_Y, LEAF_H = 398, 240
CAP_Y = LEAF_Y + LEAF_H + 30

group_w = [len(m) * LW + (len(m) - 1) * LG for _, _, m in TREE]
G = (W - 2 * MARGIN - sum(group_w)) / (len(TREE) - 1)   # 组间距

# ---------------------------------------------------------------- 根
rx = (W - ROOT_W) / 2
svg.rect(rx, ROOT_Y, ROOT_W, ROOT_H, fill=FILL["entity"], stroke=INK, sw=2, rx=4)
svg.lines(W / 2, ROOT_Y + ROOT_H / 2, "临期果品定价与推送工作台", size=22, weight=600)
svg.line(W / 2, ROOT_Y + ROOT_H, W / 2, BUS1)

# ---------------------------------------------------------------- 子系统与模块
x = MARGIN
sub_cx = []
for (name, page, mods), gw in zip(TREE, group_w):
    cx = x + gw / 2
    sub_cx.append(cx)
    sw_ = max(gw - 12, 104)
    svg.line(cx, BUS1, cx, SUB_Y)
    svg.rect(cx - sw_ / 2, SUB_Y, sw_, SUB_H, fill="#fff", stroke=INK, sw=1.6, rx=4)
    svg.lines(cx, SUB_Y + SUB_H / 2, name, size=17, weight=600, gap=1.25)
    svg.line(cx, SUB_Y + SUB_H, cx, BUS2)

    leaf_cx = [x + i * (LW + LG) + LW / 2 for i in range(len(mods))]
    svg.line(leaf_cx[0], BUS2, leaf_cx[-1], BUS2)
    for lcx, (label, kind) in zip(leaf_cx, mods):
        svg.line(lcx, BUS2, lcx, LEAF_Y)
        leaf(svg, lcx - LW / 2, LEAF_Y, LW, LEAF_H, label, kind)

    svg.text(cx, CAP_Y, page, size=13, color=MUTED, anchor="middle")
    x += gw + G

svg.line(sub_cx[0], BUS1, sub_cx[-1], BUS1)

# ---------------------------------------------------------------- 图例（横排）与脚注
ly = 764
lx = 32
svg.text(lx, ly + 15, "图例", size=13, color=MUTED)
lx += 44


def legend_item(draw, desc):
    global lx
    draw(lx)
    lx += 44
    svg.text(lx, ly + 15, desc, size=13, color=INK2)
    lx += tw(desc, 13) + 30


legend_item(lambda x0: svg.rect(x0, ly, 34, 20, fill=FILL["entity"], stroke=INK, sw=1.6, rx=3), "系统")
legend_item(lambda x0: svg.rect(x0, ly, 34, 20, fill="#fff", stroke=INK, sw=1.5, rx=3), "子系统 / 功能模块（在线：页面操作或后台自动执行）")
legend_item(lambda x0: svg.rect(x0, ly, 34, 20, fill="#fff", stroke=INK, sw=1.4, rx=3, dash=DASH),
            f"仅接口、无页面（{n_api} 个）")


def _off(x0):
    svg.rect(x0, ly, 34, 20, fill=OFF_FILL, stroke=INK, sw=1.4, rx=3)
    svg.rect(x0 + 3, ly + 3, 28, 14, fill="none", stroke=INK, sw=0.9, rx=2)


legend_item(_off, f"离线脚本（scripts/ 下的 Python，{n_off} 个）")

notes = [
    "注 1：各组下方灰字是该子系统在工作台左侧菜单中的页面；效果看板即首页，其待办汇总待确认建议、高风险库存、待发送计划。",
    "注 2：代码中没有登录 / 权限 / 用户管理、会员管理（会员数据来自种子 CSV）、新增单品页面，也没有自动改价或自动发送，图中不画。",
]
for i, s in enumerate(notes):
    svg.text(32, 828 + i * 26, s, size=13, color=MUTED)

out = svg.save(OUT_DIR / "func_structure.svg")
print(f"[func_structure] → {out}")
