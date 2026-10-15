# 指尖康复 · 手部精细运动康复训练系统

**Finger Rehabilitation Training** 是面向本科《移动医疗开发》课程的原生 Android 辅助训练应用。用户通过手机触摸屏完成轨迹描绘、反应速度和协调性任务，查看真实触摸数据计算的成绩、历史趋势和每日计划。

本项目不提供疾病诊断，不替代专业康复治疗。0～100 分为软件定义的工程评分，未经临床量表验证。练习应以舒适为度，出现疼痛或不适时停止。

## 项目交付与验证

- Java + XML + 原生 Canvas，SQLite 本地持久化，无服务器、账号注册或运行时联网要求。
- 提供完整 Gradle/Android Studio 工程、三类训练代码、数据库、统计图表、提醒与测试源码。
- 当前版本为 **1.0.2（versionCode 3）**，本轮改动见 [docs/CHANGELOG.md](docs/CHANGELOG.md)。本版构建通过，166 项测试全部通过，Lint 为 0 errors/25 warnings，APK 签名复核通过。本版 API 26 模拟器已验证升级保留、草稿选择、未开始返回、简单连续点击及强停重开持久化；版本对应的证据与验收范围见 [docs/TESTING.md](docs/TESTING.md)。
- 课程设计报告见 [PROJECT_REPORT.md](PROJECT_REPORT.md)，完整算法推导见 [docs/ALGORITHMS.md](docs/ALGORITHMS.md)，数据库与提醒细节见 [docs/DATABASE.md](docs/DATABASE.md)、[docs/REMINDERS.md](docs/REMINDERS.md)。

本轮 1.0.2 离屏预览：[空首页](docs/previews/home-empty.png) · [训练选择](docs/previews/training-selection.png) · [螺旋训练](docs/previews/training-spiral.png) · [结果报告](docs/previews/result-real-trace.png)。结果页的 **98.2 分**来自合成 MotionEvent 完成螺旋后的正式算法计算与 SQLite 读回，没有硬编码成绩；这些是 Robolectric 原生 Skia 离屏预览，非设备截图。

## 运行环境

| 项目 | 配置 |
| --- | --- |
| 开发语言 / 界面 | Java 17 / XML 与原生 View |
| 最低系统 | Android 8.0，API 26 |
| 编译 / 目标 SDK | compileSdk 36 / targetSdk 35 |
| Build Tools | 36.0.0 |
| Android Gradle Plugin | 8.13.2 |
| Gradle Wrapper | 8.14.3 |
| JDK | 17 |
| 数据库 | Android 内置 SQLite，数据库版本 3 |
| 测试 | JUnit 4.13.2、Robolectric 4.14.1，仅用于测试 |
| 应用 ID / 版本 | `com.fingerrehab` / 1.0.2（3） |

使用支持该 Android Gradle Plugin 的 Android Studio 版本。首次同步构建依赖需要联网；安装后的核心功能离线工作。应用没有 `INTERNET`、定位、相机、通讯录或存储权限，仅声明通知权限与开机广播权限。

## 工程目录

```text
Android-Project/
├── app/
│   ├── build.gradle
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/fingerrehab/
│       │   │   ├── ui/             # 导航、档案、历史、结果、计划、设置
│       │   │   ├── training/       # 训练 Activity、Canvas 与 TouchEvent
│       │   │   ├── algorithm/      # 几何、评分、难度推荐纯 Java 逻辑
│       │   │   ├── database/       # SQLiteOpenHelper 与非破坏升级
│       │   │   ├── model/          # 用户、会话、计划实体
│       │   │   ├── repository/     # 当前档案范围内的数据库访问
│       │   │   ├── statistics/     # 自定义 Canvas 图表
│       │   │   ├── notification/   # 非精确提醒、权限和重启恢复
│       │   │   └── utils/          # 标签、时间等公共工具
│       │   └── res/               # XML 布局、颜色、主题、矢量图标
│       └── test/java/             # 算法与 Robolectric 自动测试
├── gradle/wrapper/                # 已提供 Wrapper JAR 和配置
├── gradlew / gradlew.bat
├── build.gradle / settings.gradle
├── README.md
├── PROJECT_REPORT.md
└── docs/
    ├── ALGORITHMS.md              # 公式、输入、输出与边界
    ├── CHANGELOG.md               # 版本改动
    └── TESTING.md                 # 实测记录与真机验收流程
```

## 主要功能与操作

底部导航提供“首页、训练、数据、计划、我的”，各页分别保留滚动位置；训练难度和统计筛选会持久保存。第一次打开后先建立本地个人档案。首页的今日完成、最近成绩和记录全部由 SQLite 查询；没有记录时显示空状态。

| 模块 | 已实现的功能 | 数据或交互要点 |
| --- | --- | --- |
| 个人档案 | 创建、查看、修改昵称、可选年龄、惯用手、训练目标、每日次数 | 有未保存修改时返回可保存/放弃/继续编辑，草稿跨旋转保留；记录关联当前档案 |
| 轨迹描绘 | 直线、圆形、螺旋线；起终点提示；实时路径、进度、计时；重开和结束 | Canvas 绘制，采集坐标与单调时间；覆盖、长度、方向和起终点有效性检查 |
| 反应速度 | 随机圆形目标、命中反馈、错误点击、单轮超时与轮次进度 | 每个目标出现后开始单调计时；成功、错误、超时分别保存 |
| 协调性 | 数字顺序点击与指定区域连续点击 | 单次有效按下计一次；错序不推进；记录间隔与节奏稳定性 |
| 结果与历史 | 得分、指标、原始事件分页、历史筛选与删除确认 | 事件每组 30 条可翻至末页；异常分显示“—”；历史返回/旋转保留位置 |
| 统计 | 历史成绩折线、类型均分对比、近 7 天次数、近 30 天表现 | 数据来自当前档案的真实记录，支持类型、任务、难度筛选；同任务同难度才画成绩趋势 |
| 难度推荐 | 简单、普通、困难；最近 5 次同任务同难度有效成绩规则 | 仅提供建议，由用户自行选择，绝不自动升降难度 |
| 每日计划 | 编辑/删除目标次数、时间、模式与提醒；今日完成进度 | 草稿跨旋转保留，有未保存修改时返回可保存/放弃/继续编辑；完成数使用统一有效口径 |
| 生命周期 | 训练返回确认、中断处理、旋转处理、定时回调清理 | 后台暂停并保留快照，返回提示继续；已开始任务退出/重开保存中断状态，未开始浏览不产生记录 |

已开始的训练按所属档案保存本地快照；未点击开始的浏览不保存记录或恢复入口。未完成进度可选择恢复、保存中断并开始本次、保留数据并返回。已完成但待保存的结果则提供完成保存/保存结果后开始本次，保留原完成状态与成绩；重复点击和旧弹框回调不会覆盖新会话。

训练报告按模式展示轨迹偏差/覆盖率/稳定性、反应时间/超时及协调间隔/节奏。可读明细的全部事件按每组 30 条翻页，单条坏事件不会阻断其余事件；未知时间不显示为 0。轨迹预览遇到坏点时断笔，不跨过未知区间连线。

## 数据库结构

数据库文件名为 `finger_rehab.db`，位于应用私有数据库目录。代码实体 `UserProfile`、`TrainingRecord`、`TrainingPlan` 分别对应 `profiles`、`records`、`plans`。

### `profiles` 用户档案

| 字段 | SQLite 类型 | 含义 |
| --- | --- | --- |
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT | 用户 ID |
| `nickname` | TEXT NOT NULL | 昵称 |
| `age` | INTEGER NULL | 可选年龄 |
| `hand` / `goal` | TEXT NOT NULL | 惯用手 / 训练目标 |
| `daily_goal` | INTEGER NOT NULL | 默认每日目标次数 |
| `created_at` | INTEGER NOT NULL | 创建时间，Unix 毫秒 |

### `records` 训练记录

| 字段 | SQLite 类型 | 含义 |
| --- | --- | --- |
| `id` / `user_id` | INTEGER | 主键 / 档案外键 |
| `session_id` | TEXT NOT NULL UNIQUE | 会话 UUID；同一次训练重复回调不会重复插入 |
| `type` / `subtype` | TEXT NOT NULL | 大类与子类 |
| `difficulty` | INTEGER | 0 简单、1 普通、2 困难 |
| `started_at` / `ended_at` | INTEGER | 展示及日期分组使用的 Unix 毫秒 |
| `duration_ms` | INTEGER | 单调时钟计算的训练时长 |
| `status` / `valid` | TEXT / INTEGER | `completed`、`incomplete`、`aborted` / 是否通过有效性检查 |
| `score` | REAL | 0～100 工程分；无效会话不进入有效成绩均分 |
| `errors` / `successes` / `timeouts` | INTEGER | 错误 / 成功 / 超时次数 |
| `accuracy` | REAL NULL | 准确率比例，0～1 |
| `average_reaction_ms` / `fastest_reaction_ms` | REAL NULL | 成功反应的平均/最快毫秒数 |
| `mean_deviation` / `coverage` / `stability` | REAL NULL | 归一化轨迹偏差 / 覆盖比例 / 稳定性比例 |
| `average_interval_ms` | REAL NULL | 有效点击的平均间隔 |
| `details_json` | TEXT NOT NULL | 模式详情、采样数据或事件明细 JSON |

类型：`trace`、`reaction`、`coordination`。子类型：`line`、`circle`、`spiral`、`random`、`sequence`、`tapping`。只适用于其他模式的字段保持 NULL。

### `plans` 每日计划

| 字段 | SQLite 类型 | 含义 |
| --- | --- | --- |
| `id` / `user_id` | INTEGER | 主键 / 唯一档案外键，每档案一份当前每日计划 |
| `daily_goal` | INTEGER | 每日目标次数 |
| `hour` / `minute` | INTEGER | 本地时间的小时 / 分钟 |
| `type` | TEXT | `all` 或三种训练大类 |
| `reminder_enabled` | INTEGER | 是否开启提醒 |

数据库开启外键，用户关系使用 `ON DELETE CASCADE`。记录具有用户+时间、用户+类型+时间，以及用户+类型+任务+难度+时间索引。仓储查询、记录删除和写入均校验当前档案；新记录拒绝非有限数、越界比例与负计数；`session_id` 的唯一约束是重复保存的最后保护。版本 1 升级到 2 采用 `ALTER TABLE` 增加可空测量列；版本 2 升到 3 仅新增任务/难度筛选索引。支持 1 直接升级到 3，保留旧记录，不删库重建。

统计口径：`TrainingRecord.isEffectiveCompletion()` 要求 `valid=true`、`status=completed`、分数有限且在 0～100。有效均分、最佳分、推荐与每日计划统一使用此口径；旧坏分仍保留历史、次数和时长，不计有效完成。日期按设备本地时区和训练开始时间归组。没有有效成绩时均分为 NULL。成绩折线与 30 天趋势要求选择具体任务、具体难度；类型对比柱图明确按当前难度汇总各类全部任务。图表支持点选、方向键，以及无障碍读值/上一项/下一项动作。

报告先读取摘要，再按每块 64Ki Unicode 字符读取 `details_json`。超出 4Mi 字符或不可读时仍展示摘要并提示明细不可用，不截断或改写库内原文；数据库版本保持 3。

## 核心算法与评分规则

所有算法输入与公式详见 [docs/ALGORITHMS.md](docs/ALGORITHMS.md) 和 `algorithm/TrainingAlgorithms.java`。下面为实现摘要；`clamp(x)` 表示限制在 0～1，最终得分限制在 0～100。

### 轨迹训练

绘图区固定为正方形，以其边长归一化坐标，不按用户实际路径包围盒缩放。目标和实际路径按弧长重采样为 128 点，笔画间断不连接成虚构线段。

- 平均偏差 `D`：实际采样点到目标折线的最短距离均值。
- 覆盖率 `C`：距离实际路径不超过难度容差 `τ` 的目标点数 / 目标总点数。
- 有序偏差 `Do`：实际点与同弧长进度目标点的距离均值，约束反向与乱序描绘。
- 准确度 `A = 0.75×clamp(1−D/(2τ)) + 0.25×clamp(1−Do/(4τ))`。
- 稳定性 `S`：目标相对二阶位置变化的空间稳定性占 65%，速度变异系数对应的时间稳定性占 35%。
- 时间分 `T = min(1, 理想时长/首尾采样间描绘时长)`；会话报告中的训练用时另包含开始后首次触摸前的等待。
- 综合分 `100×(0.40A + 0.30C + 0.20S + 0.10T)`。

有效训练必须同时满足：至少 8 个采样点；描绘时长不少于 500 ms，完整前台活动会话不能达到总时限；长度比 0.65～2.2；覆盖率至少 70%；起点/终点均在 `2τ` 内；无大幅断笔跳跃；平均与有序偏差在阈值内。未完成、空路径、短线或错误顺序不会凭时间得到有效高分。

### 反应速度训练

训练 View 使用 `SystemClock.uptimeMillis()` 与 MotionEvent 的同源单调时间，扣除明确暂停时段，传入反应算法时换算为纳秒；日期调整不改变反应测量。成功轮反应时间求平均和最小值；错误点击不结束本轮，超时结束本轮并单独计数。

`A = 成功次数/(成功次数+错误次数)`，超时不进入点击尝试分母。若没有成功轮，平均和最快时间为 NULL；界面没有点击尝试时准确率也保留缺测语义。

`V = clamp(1−(平均反应毫秒−80)/(单轮时限毫秒−80))`，`C = 成功轮数/总轮数`。

综合分为 `100×(0.45A + 0.35V + 0.20C)`。有效完成要求成功+超时等于规定轮数、至少一次成功、事件时间和顺序合法；早于 80 ms 的异常反应不作为有效成绩。

### 协调性训练

顺序点击必须按当前序号命中；连续点击必须位于有效圆形区域。单指 `ACTION_DOWN` 一次最多记一次；50 ms 内的重复点击被防抖规则限制。

`A = 正确/(正确+错误)`；有效点击间隔均值为 `μ`，节奏稳定性 `S = 1/(1+σ/μ)`。没有足够间隔时不伪造平均间隔和稳定性。

`T = min(1, 目标数量×理想单次耗时/实际耗时)`，综合分为 `100×(0.45A + 0.30T + 0.25S)`。数量不足、超时、时间异常与未完成任务不作为有效完成。

### 难度配置与推荐

| 参数 | 简单 | 普通 | 困难 |
| --- | --- | --- | --- |
| 轨迹容差 / 归一化边长 | 0.045 | 0.032 | 0.023 |
| 反应/顺序点击目标半径 / 归一化边长 | 0.090 | 0.072 | 0.055 |
| 反应单轮时限 | 2500 ms | 1800 ms | 1200 ms |
| 反应轮数 | 8 | 10 | 12 |
| 顺序目标数 | 6 | 8 | 10 |
| 连续点击目标数 | 12 | 18 | 24 |
| 轨迹 / 协调任务时限 | 60 s | 50 s | 40 s |

参数集中在 `TrainingAlgorithms.Config`。连续点击采用较大的有效圆区，半径为 `max(0.14, 基础半径×2.1)`；其余点击使用基础半径。螺旋圈数随难度为 1.25、1.75、2.25 圈；圆半径随难度增加，直线使用固定目标并提高精度与时间要求。

推荐由仓储先限定同一任务子类型，再读取最近 5 次同难度、有效已完成结果：均分 ≥85 建议升一级；均分 <60 建议降一级；其他情况保持。最低/最高难度不越界，不足 5 次只提示依据不足。

## 安装与运行

1. 在 Android Studio 选择 **Open**，打开本目录，等待 Gradle Sync 完成；Gradle JDK 选择 17。
2. 在 SDK Manager 安装 Android SDK Platform 36 与 Build Tools 36.0.0。需要时在项目根目录的本机 `local.properties` 写入 `sdk.dir=/你的/Android/Sdk`，不要提交他人的机器路径。
3. 连接并授权 USB 调试的 Android 8.0 及以上设备，选择 `app`，点击 Run。
4. 或在项目根目录执行：

```bash
./gradlew --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.fingerrehab/.ui.MainActivity
```

Windows 使用 `gradlew.bat`。Debug APK 由本地调试密钥签名，用于课程演示；不是应用商店发布包。覆盖安装保留数据，卸载或“清除存储”会删除本地档案与历史。

## 提醒与权限

应用使用 `AlarmManager.setAndAllowWhileIdle` 安排一次非精确任务，收到提醒后按下一本地日历日重排。计划修改/关闭/删除时取消旧任务，使用调度代次、计划参数校验和已投递日期避免旧广播或重复通知。同一计划在应用重新进入前台时保留现有调度，避免将已到点但待系统投递的提醒推迟到明天；开机、应用更新、时钟和时区变化后强制恢复/重排。

Android 13 及以上支持按需请求 `POST_NOTIFICATIONS`，并显示未获授权的状态与设置入口。拒绝权限或关闭通知渠道时不会阻止本地训练和计划保存，可通过系统设置重新允许。`RECEIVE_BOOT_COMPLETED` 只用于重启后恢复调度，无精确闹钟特殊权限。

**设定时间是期望提醒时间，系统省电、Doze 和厂商后台策略可能造成延迟。** 强行停止应用后系统可能停止投递，重新打开应用可恢复计划调度。真机提醒表现需要按 [测试说明](docs/TESTING.md) 实测。

## 测试方法

```bash
./gradlew --no-daemon testDebugUnitTest assembleDebug lintDebug
```

测试覆盖纯 Java 评分、几何边界、推荐规则，以及 Robolectric 下的 SQLite 持久化、去重、空统计和系统组件行为，具体范围及实际结果见 [docs/TESTING.md](docs/TESTING.md)。测试报告位于 `app/build/reports/`。`VisualRenderingTest` 使用原生 Skia 离屏绘制实际 View，覆盖空首页、训练选择、空统计、螺旋训练及通过自动构造 MotionEvent 完成任务后生成的结果页，预览写入 `app/build/outputs/previews/`；成绩由测试触摸事件经正式算法计算，不注入固定得分。这些图片不是模拟器或真机截图。真机触摸、视觉、旋转/后台、重启提醒不由 JVM 测试完全覆盖。

## 已知限制与后续工作

- 本软件是课程设计与辅助训练原型；分数/难度阈值由工程规则设定，未做临床有效性和人群适用性验证，不根据分数诊断或治疗。
- 1.0.2 已在 API 26 软件模拟器验证升级保留、未开始返回、档案/计划草稿、简单连续点击及强停重开持久化；不代表六种任务、系统通知和重启投递全面验收。软件模拟器与 ADB 开销不代表人类操作或手机性能，尚未完成真机与多机型实测。
- 提醒为非精确定时。训练进入后台会主动暂停，离开时间不计入任务；返回提示继续或结束。旋转保留训练 Activity，系统重建或下次进入训练页时可选择恢复所属档案的本地快照；尚未持久化或快照写入失败的极端进程终止仍可能丢失当前进度。仅已开始训练的退出/重开保存中断记录；未开始浏览不写训练历史。
- 数据只在应用私有 SQLite 保存，未提供云备份、导出、跨设备同步或数据库加密；关闭系统备份，不收集身份证、病历等非必要信息。卸载会删除数据。
- 每个当前档案只维护一份每日重复计划，不支持多个时间段、周计划或医生端。
- 混合任务/难度仅显示汇总，成绩趋势要求同一任务、同一难度。类型均分柱图仅供工程表现参考；跨设备触摸硬件的比较仍有局限。
- 为控制内存和数据库体积，轨迹采样采用至少 2 ms 间隔，并在达到 16,000 点时按真实坐标压缩；结果回放和评分使用保留样本。点击事件达到 4,096 条时保护性结束为未完成，保留已采集事件，不继续无限增长。
- 历史以摘要每页 30 条进行 SQL 分页；尚未提供大量会话的归档与后台分析。
