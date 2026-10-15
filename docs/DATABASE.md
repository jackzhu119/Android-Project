# 本地数据库与持久化

应用只使用 Android 自带 SQLite 和 SharedPreferences，没有服务端、账号、网络数据或运行时数据库依赖。数据库文件为 `finger_rehab.db`。已保存的档案、训练记录和计划存于 SQLite；当前档案、界面选择、提醒调度元数据及快照入口使用 SharedPreferences，未完成训练快照使用应用私有文件。

## 关系与表

| 表 | 关键内容 | 关联与约束 |
|---|---|---|
| profiles | 昵称、可空年龄、训练手、目标、每日次数、创建时间 | 自增 id |
| records | 开始/结束时间、单调时钟计算的时长、类型/子类型/难度、状态、有效性、分数、错误/成功/超时数、各类可空指标、原始 details_json | user_id 外键到 profiles，删除档案时级联删除；session_id 全局唯一 |
| plans | 每日次数、训练类型、提醒时分、是否提醒 | 每个 user_id 至多一条；删除档案时级联删除 |

`records_user_time` 和 `records_user_type_time` 索引支持当前档案的时间排序及分类查询。版本 3 新增 `records_user_task_time(user_id,type,subtype,difficulty,started_at DESC,id DESC)`，支持同子任务、同难度的推荐和筛选。`onConfigure()` 开启外键。

应用目前数据库版本为 3：版本 1 升至版本 2 时仅增加可空指标列，历史缺失指标保持 null；版本 2 升至版本 3 时仅新增索引。两个升级过程均不清空或重写用户记录，版本 1 也可直接升级至版本 3。

## 仓储行为

`RehabRepository.get(context)` 为应用级单例。无档案时 `getProfile()` 返回 null；`saveProfile()` 创建或更新档案并选择该档案。`setCurrentProfile()` 验证档案存在后切换。用户记录、单条详情、删除、计划、有效训练及统计全部自动限定当前档案；其他档案的 ID 无法被读取或删除。

记录采用只插入策略。训练开始时生成一次 UUID，重建界面继续复用同一个 sessionId。`saveRecord()` 再次收到相同 sessionId 时返回原 ID，不会改变原始分数或写入第二行；SQLite UNIQUE 约束提供最终防重保障。不同档案不能复用相同 sessionId。训练页面必须传入完整的 `detailsJson`，仓储原样保存 JSON 文本，不裁剪、不重采样。某训练不适用的指标使用 null，避免展示为虚假的 0。

`saveRecord()` / `savePlan()` 的对象若已携带非 0 的 userId，该 ID 必须等于当前档案，否则明确拒绝保存。这样从旧档案页面保留下来的对象不会静默归入另一个人。新对象 userId=0 时才在写入成功后绑定当前档案。

保存失败直接抛出异常，由界面保留数据并提示重试；仓储仅在 SQLite 写入成功后回填 id、userId 或创建时间，不以伪造的成功 ID 掩盖失败。更新档案时若未携带创建时间，保留原创建时间。记录不把负时长默默改成 0；档案与计划每日目标限定 1～20、可选年龄限定 1～120，和表单一致。分数须为有限的 0～100，比例指标须为有限的 0～1，时间、偏差和计数须非负；不适用的可空指标仍可保持 null。墙上时钟可能被用户调回，因此不要求 endedAt 大于 startedAt，实际训练时长独立使用单调时钟。

`getRecords(null)` 和 `getRecords("all")` 均返回全部类型，以开始时间、记录 ID 降序排列。已中断、未完成记录可查询，不会因无效而被删除。统一有效完成谓词为 `TrainingRecord.isEffectiveCompletion()`：有效标记为 true、状态为 completed，并且分数为有限的 0～100。今日完成数、近期有效记录、均分、最佳分和每日均分使用同一口径。旧库的超范围/无穷分数以及误存成文本的坏分数不计入有效完成，但其次数、时长和历史内容仍保留；文本坏分数在读取模型中表示为 NaN，不写回原库。自适应难度的 `recentValid()` 还限定类型和当前难度。

首页、历史列表与推荐只需摘要：`getRecords()`、`recentValid()` 的列投影不读取 `details_json`，返回模型的 detailsJson 保持默认 `"{}"`。`getStats()` 只查询开始时间、时长、分数和 SQL 派生的有效完成标记 4 列，避免长年训练后每次打开统计页面都加载大量轨迹 JSON；它不会改变数据库保存的原始内容。

进入单条训练报告后使用 `getRecord(id)` 按需读取原始采样。它先读取摘要，再将 details_json 按每块 64Ki Unicode 字符分批读取，避免旧版 Android 的约 2MiB CursorWindow 因单行明细过大而崩溃。上限内的明细按块拼接，避免要求整条 JSON 同时进入一个 CursorWindow。单条明细超过 4Mi Unicode 字符，或明细不可读时，返回的记录保留分数、时长和其他聚合指标，detailsJson 为 `"{}"`，临时字段 detailsUnavailable=true，供界面说明明细不可用；原始明细仍保留于数据库，既不截断也不改写。该限制不需要数据库迁移，schema 仍为版本 3。

新增筛选接口保留旧接口兼容性：

| API | 用途 |
|---|---|
| `getRecords(type, subtype, Integer difficulty, limit, offset)` | SQL 分页读取摘要；limit>0，offset>=0 |
| `recordCount(type, subtype, Integer difficulty)` | SQL COUNT 查询条数，不构造历史记录对象 |
| `recentValid(type, subtype, Integer difficulty, limit)` | 只取需要数量的已完成有效会话，用于同任务推荐与趋势 |
| `getStats(type, subtype, Integer difficulty, days)` | 在 SQL 中先按任务和难度过滤，再生成本地日期统计 |

类型与子类型为 null 或 `all` 表示不限，难度 null 表示不限，0/1/2 分别表示简单/普通/困难。筛选参数均绑定为 SQL 参数，不拼入查询文本。分页排序为 started_at DESC、id DESC，同一毫秒开始的记录也有稳定顺序。分页期间新增或删除记录后，调用方应重置 offset 并重新读取，避免将不同时刻的分页快照混用。

## 统计口径

`getStats(type, days)` 使用设备当前时区的本地日，从今天向前包含 days 天。7 日、30 日均返回对应数量的按日期升序排列的桶，即使从未训练。时间区间为首日本地零时（含）到明日本地零时（不含），按训练开始时间归属日期。

- count 包含全部状态、全部有效性的会话；durationMs 累加该范围真实保存的时长。
- completed 只计满足统一有效完成谓词的会话。
- averageScore / bestScore 只计满足统一有效完成谓词的会话；没有有效分数时为 null，不将未完成训练当作 0 分参与平均。对于旧版本遗留的状态、有效标记或分数不一致的异常记录，仍在历史和总会话数中显示，但排除其分数和推荐影响。
- 每天的 count 和 averageScore 同样遵循上述口径；没有会话的日期返回 count=0、averageScore=null。
- 每一个日期的零时独立由 `LocalDate.atStartOfDay(ZoneId)` 计算，不使用固定 24 小时偏移，正确处理 23 小时和 25 小时的夏令时日。

## 自动化验证

`RehabRepositoryTest` 使用 Robolectric / SDK 28，覆盖档案增改查删、数据库重新打开后的记录和计划持久化、原始 JSON 及 null 指标、重复保存、跨档案隔离、档案删除级联、记录筛选/排序/删除、7/30 天空统计、边界日期、有效分数平均、今日计划计数、夏令时切换、版本 1/2 到版本 3 的数据保留迁移。新增边界验证还包括筛选/分页/推荐/统计口径一致、陈旧对象无法跨用户保存、SQL 参数绑定、非有限指标拒绝、模拟 SQLite 写失败后的对象状态与重试。

`TrainingRecordTest` 覆盖统一有效完成谓词的状态、有限值和边界。`LegacyMetricsTest` 直接用 SQL 插入旧异常分数，验证统计/今日完成/推荐/模型判定一致，且原值与原始 JSON 不被改写；还覆盖 3MiB 明细分块读取和超限明细只返回聚合数据的兼容行为。

运行：`./gradlew testDebugUnitTest --tests com.fingerrehab.repository.RehabRepositoryTest`。
