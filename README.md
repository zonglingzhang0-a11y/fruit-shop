# 街边水果店临期果品动态定价与到店客流适配推送系统

课程设计项目。核心链路：

```
入库批次 → 临期预警 → 定价建议 → 店主确认 → 会员推送 → 销售回流 → 策略效果对比
```

- 项目定位：**决策辅助系统**。系统给建议、给理由、给对比数据，最终定价权在店主手里。
- 答辩主线：不是"我能算折扣"，而是"在同一份数据上，我的定价策略比固定价格和规则折扣损耗更低、毛利更高"。

## 目录约定

| 路径 | 用途 |
| --- | --- |
| `docs/00-项目启动与执行流程.md` | 启动流程、阶段划分、验收标准、风险、**证据分级制度** |
| `docs/01-技术方案.md` | 技术选型、架构、数据库表、12 个接口契约 |
| `docs/02-仿真器假设与数据字典.md` | 真实数据事实（§1）+ 仿真假设、参数表、列名契约（§2~9） |
| `docs/03-定价模块设计.md` | 三种策略、弹性估计陷阱、评测协议、局限 |
| `docs/04-需求与用户调研.md` | 角色画像、As-Is/To-Be、FR/NFR、访谈提纲与记录表 |
| `docs/05-个人信息保护与合规说明.md` | 最小化原则、推送上限、无自动执行承诺 |
| `docs/06-演示与答辩手册.md` | 8 分钟演示脚本、预设问答、复现命令、已知局限 |
| `docs/07-与参考架构的差异与补齐.md` | 与参考架构的逐条对照、schema v2 口径、补齐结果与验证 |
| `docs/reference/` | **参考架构原件**（Excel + 自动导出的 Markdown，原件未改动） |
| `data/raw/` | 原始 Excel 附件（只读，不改写） |
| `data/interim/` | 清洗后的 UTF-8 CSV（可重跑，不进 git） |
| `data/seed/` | 校准产物 + 仿真种子数据 + 三策略汇总（进系统演示） |
| `scripts/` | 环境引导、数据准备、校准、仿真、评测、冒烟 |
| `release/` | 评测报告与图表、答辩材料 |

## 快速开始

```powershell
# 1. 生成环境配置（检测 JDK/Maven/Node/Python，选定 Maven 镜像）
powershell -ExecutionPolicy Bypass -File scripts/setup.ps1

# 2. 构建与测试（本机 Maven 不在 PATH，统一走 wrapper；154 个用例）
.\scripts\mvn.ps1 -B test

# Python 依赖（一次）：python 指 3.12+ 的解释器；Windows 上 PATH 里的 python 不全时可以用 py -3.14
python -m pip install -r requirements.txt

# 3. 数据链路：原始 Excel -> CSV（一次性）-> 真实数据校准 -> 仿真种子 -> 独立核验
python scripts/prepare_raw_data.py        # 约 1 分钟
python scripts/calibrate_from_public.py   # 约 1 分钟
python scripts/simulator.py run --seed 20260701 --days 120 --warmup-days 60
python scripts/verify_seed_data.py        # 独立核验（不 import 仿真器，避免自证）

# 4. 三策略评测（报告 + 7 张图）、看板 KPI 导出、弹性 ±30% 敏感性
python scripts/evaluate_strategies.py --seeds 20
python scripts/export_strategy_stats.py --rate-seeds 20 --days 120
python scripts/sensitivity_elasticity.py --seeds 6

# 5. 端到端验证：接口冒烟 + 浏览器逐页验证与截图
powershell -ExecutionPolicy Bypass -File scripts/smoke.ps1
powershell -ExecutionPolicy Bypass -File scripts/e2e-browser.ps1

# 6. 前端工作台（开发模式）
cd frontend; npm install; npm run dev   # http://localhost:5173
```

> 业务日期口径：种子数据是 2026-03-02~06-29 的历史区间，页面 `bizDate` 默认取种子最后一天
> （`frontend/src/utils/bizDate.js`）。用"今天"会把 2000+ 个批次全判成过期，这是演示最容易翻车的点。
> 另：`export_strategy_stats.py` 的 `--days` 必须与种子一致（120），否则看板按 `bizDate` 查不到策略数据。
> `e2e-browser.ps1` 会自动挑随机空闲端口（本机别的应用曾占用 5174，导致截到别人的页面）。

## 持续集成（GitHub Actions）

`.github/workflows/ci.yml`：每次推送到 `main`、每个 PR 自动跑，结果在 PR 页面的 Checks 里看。

| 任务 | 内容 |
| --- | --- |
| 后端测试（H2） | `mvn -B test` |
| 后端测试（MySQL 8.0 / 8.4） | 起真实 MySQL 容器，`mvn -B test -Dspring.profiles.active=mysql` |
| 前端构建 | `npm ci` + `npm run build`（CI 在海外，改用 npm 官方源；本地 `.npmrc` 仍用国内镜像） |
| Python 自检 | 按 `requirements.txt` 装固定版本依赖，跑 `pricing_rule` / `pricing_model` / `export_model_suggestions` / `evaluate_strategies` 的 `--selftest` |

## 已验证的事实（可现场复现）

| 验证链 | 命令 | 实测结果 |
| --- | --- | --- |
| 后端测试 | `.\scripts\mvn.ps1 -B clean test` | **154 个用例全绿**（含接口契约锁、NFR-03 无自动执行、定时任务白名单；H2 与 MySQL profile 各跑一遍） |
| 种子数据自洽 | `python scripts/verify_seed_data.py` | **全部检查通过**（台账不一致 0/2083；营业时段形状 Spearman r=0.996） |
| 接口端到端 | `powershell -File scripts/smoke.ps1` | **21 次接口调用全绿**（临期 21 条、建议 6 折并附理由、高峰 10 点→推送 9 点） |
| 浏览器端到端 | `powershell -File scripts/e2e-browser.ps1` | **7 个页面全部渲染正常**（工作台 6 个 + 顾客端 H5），无控制台报错、无失败请求 |
| 弹性敏感性 | `python scripts/sensitivity_elasticity.py --seeds 6` | 三档 × 6 种子：**MODEL 对 RULE 全部 6/6 胜**，见 `release/sensitivity-elasticity.md` |
| 三策略评测 | `python scripts/evaluate_strategies.py --seeds 20` | 20 种子 × 30 天测试期，报告 + 7 张图（`release/evaluation-report.md`） |

## 三策略对照结果（20 个种子，测试期 30 天，含最低折扣线 0.60）

| 策略 | 经营毛利 / 元 | 损耗率 | 售出率 | 平均折扣深度 | 打折成交占比 |
| --- | --- | --- | --- | --- | --- |
| FIXED 固定价格（基线1） | 68,370 ± 2,382 | 13.58% ± 1.56% | 82.83% | 0% | 0% |
| RULE 规则折扣（基线2，店主经验） | 49,861 ± 2,984 | 4.99% ± 0.80% | 95.02% | 20.49% | 约 76% |
| **MODEL 模型定价（本项目）** | **60,194 ± 3,501** | 7.84% ± 1.26% | 89.97% | 9.89% | 约 28% |

> 复现：`evaluate_strategies.py --seeds 20 --min-discount-rate 0.60`；
> 派生指标用 `scripts/report_metrics.py` 从报告里算出来（不手抄）。

**怎么读这张表（重要）**

- **MODEL vs RULE：20/20 个种子全胜**。MODEL 的损耗率比 RULE 高（7.84% vs 4.99%），但毛利高 **20.72%**。
  两者是"效率—彻底度"的取舍：相对 FIXED 口径下，**RULE 把损耗率压得更低（−8.59pp）但代价是 18,509 元毛利（2,155 元/pp）；
  MODEL 只压 −5.74pp，代价 8,176 元（1,424 元/pp）**——也就是 MODEL 的清货"每降 1pp 便宜约 34%"，
  但它不追求把损耗降到 RULE 那么低。
- **MODEL vs FIXED：0/20 个种子毛利更高**，但损耗率从 13.58% 降到 7.84%（−5.74pp）。
  即：在这个毛利结构与弹性下，**降损耗必须付毛利代价**，MODEL 的价值在于"用更少的让价拿到够用的清货效果"。
- **前置证据**：连"只在到期当天打 9 折"这种最精准的清货方式也是 −1.2% 毛利。
  **价格不是解决损耗的正确杠杆**——根治要靠补货量。这是本项目最重要的诚实结论，已写进报告第 2 节。
- **弹性 ±30% 敏感性**（`release/sensitivity-elasticity.md`，三档 × 6 种子）：MODEL 对 RULE
  **三档全部 6/6 胜**（×0.7 +38,731 元 / ×1.0 +18,604 元 / ×1.3 +10,298 元），
  "降损耗必须付毛利代价"三档也都成立。优势随弹性增大而收窄，方向不变。

## 当前状态

- [x] **阶段 0**：环境脚本、选题分析、启动流程文档、技术方案与接口契约、Spring Boot 骨架
- [x] **阶段 1**：真实数据校准（87.8 万条流水 + 独立对账）、仿真器与数据字典（45 项自检 + 12 项 CRN 自测）、需求调研材料
- [x] **阶段 2**：三策略实现与 20 种子对照评测（报告 + 7 张图 + 看板 KPI）+ 弹性 ±30% 敏感性
- [x] **阶段 3**：后端 12 个接口 + v2 新增 8 条接口 / **94 个测试全绿**（含 NFR-03 无自动执行、定时任务白名单）
- [x] **阶段 4**：前端 6 个页面（工作台 5 个 + 顾客端 H5），Playwright 实测无报错、无失败请求
- [x] **阶段 5**：演示手册 `docs/06` + `release/screenshots/` + 三条端到端验证链
- [x] **阶段 6（对照参考架构补齐，见 `docs/07`）**：临期判定指标落库上屏、最低折扣线与调价上限、
  推送计划/日志拆分与转化归因、只读定时扫描、星期×小时客流规律、顾客端 H5

**需人工补做**（不影响系统运行，详见 `docs/01` §8 与 `docs/04` §11）

- 真实店主/会员访谈：`docs/04` §8 三张空表，需求侧 24 行仍标为 C 类"待访谈验证"。
- ~~`data/seed/pricing_model.json` 未产出~~：已接通（#2）。仓库自带 2026-06-29 的模型产物；其它业务日先运行
  `python scripts/export_model_suggestions.py --biz-date <业务日>`，步骤见 `docs/03` §3.8。产物缺失/过期时 MODEL 仍会回退 RULE，
  但会如实标为 RULE（`effective_strategy`），效果看板不再把规则定价算到模型头上。
- `docs/05` 附录 A 的 NFR-03 验证记录待填（可引用 `NoAutonomousActionTest` 三条用例作为证据）。

> 口径一致性说明（2026-10-11 已对齐）：主报告、敏感性报告与看板 KPI 现在**都带最低折扣线 0.60**，
> 与系统侧 `app.pricing.min-discount-rate` 同口径；约束前后的差异保留在
> `release/_evaluation-report.pre-floor.md` 与 `release/_sensitivity.pre-floor.md`（带下划线前缀的对照备份）。

## 环境事实（实测，别再重复排查）

| 项 | 状态 | 用法 |
| --- | --- | --- |
| JDK | 21（作者机器用的是 Android Studio 自带的 OpenJDK 21.0.8） | 由 `scripts/setup.ps1` 自动探测并写入 `.tools.json` |
| Maven | **不在 PATH**；`~/.m2/wrapper/dists` 有 3.9.6/3.9.12/3.10.0 | 一律用 `scripts/mvn.ps1`（自动加镜像 settings） |
| Maven 中央仓 | `repo1.maven.org` 可达；`github.com` **不可达** | 依赖走 aliyun 镜像（`.mvn/settings-local.xml`） |
| Python | 3.12+，依赖固定在 `requirements.txt`（`python -m pip install -r requirements.txt`）；作者机器 PATH 上的 python 是缺包的残缺运行时，`scripts/setup.ps1` 会自动挑一个可用的解释器写进 `.tools.json` | 数据分析、仿真、评测；文档里的 `python` 指装好依赖的那个解释器 |
| Node | v24.14.1；npm 镜像 `registry.npmmirror.com` | 前端（`frontend/.npmrc` 已指向镜像） |
| 下载 GitHub 文件 | `github.com`/`raw.githubusercontent.com` 不通，**`api.github.com` 通** | `curl -H 'Accept: application/vnd.github.raw' https://api.github.com/repos/{owner}/{repo}/contents/{urlencoded-path}` |
| 前端构建 | Vite 6.4.4 + Rollup 4.64.3 下 **动态 `import()` 一个 `.vue` 文件会 build 失败**（静态 import 正常，dev 正常） | 路由/组件一律用静态 import；升级依赖后先跑 `npm run build` 复验 |
| PowerShell 脚本 | 含中文的 `.ps1` **必须带 UTF-8 BOM**，否则 5.1 按 ANSI 解析报语法错误；含空格的路径传给 `Start-Process -ArgumentList` 要自己加引号 | 参考 `scripts/smoke.ps1` 头部注释 |
| Docker/MySQL/Redis | 无 | 演示用 H2（MySQL 兼容模式）；部署可切 MySQL 8：`--spring.profiles.active=mysql`，步骤见 docs/01-技术方案.md「切换到 MySQL 8」 |

> 中文输出乱码时先执行：`chcp 65001` + `$OutputEncoding=[Text.Encoding]::UTF8`。

