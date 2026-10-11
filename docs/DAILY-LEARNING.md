# 每日学习、Codeforces 验证与次日规划

登录后侧栏“每日学习”：保存 Codeforces 用户名 → 添加学习点（知识目标、题号、理解检查标准）→ 当日学习分钟和复盘 → 读取 OJ 验证 → 结算本日。个人记录位于身份服务的本地 MySQL 数据库，不参与群组节点复制。

日期统一使用 Asia/Shanghai。每天每个账号一条打卡；可稍后验证以前未结算的记录。当前使用用户点击结算，不在午夜强制结算，以便等待 OJ 提交判题结果。打卡得 1 分；同一账号同一道题第一次取得当日 AC 证据额外得 2 分。OJ 验证未找到匹配可结算 1 分；结算后锁定，不能补领该日奖励。积分是学习激励，不是知识掌握分数。

验证从 Codeforces 官方 user.status API 读取最近 1000 条提交，必须同时匹配绑定用户名、题号、上海日期和 OK verdict。查询失败返回错误，不能将失败视为通过或无匹配；记录不足以覆盖日期时拒绝无匹配结论。绑定改变会清除未结算证据，必须重新验证；已结算证据保留当时验证用户名及提交 ID。用户名绑定目前只关联公开账号，不证明账号所有权。

学习点检查标准与复盘供用户自检和顾问参考。AC 证明对应提交通过判题，不证明独立解答或知识掌握。目前没有自动知识测验评分。积分结算在事务中锁定用户行，与奖励唯一约束共同防止并发、重复领取。

右下角“顾问”可快速询问当前群组或规划明日学习。使用个人设置中的模型接口、模型名称和密钥；没有配置时提示填写，不伪造模型回答。规划单独确认后仅发送本人的最近 7 次打卡和 OJ 证据，不包含密钥、其他用户或群组讨论。生成草案后点击“保存为明日目标”，保存到个人数据库；不会自动覆盖学习点或修改打卡。已保存目标在每日学习页刷新后可见。

规划规则位于 `skills/next-day-learning/SKILL.md`，打包进身份服务，作为模型系统指令；修改规则后需要重新构建并重启。也已安装到本机 Codex skills 目录供后续复用。这是提示规则与工作流配置，没有进行模型权重训练。预算默认 45 分钟，结合历史投入调整，区分 OJ 证据与理解，缺少历史时先安排基础诊断。

## 接口

- GET `/identity/api/v1/learning/overview`：绑定、学习点、最近 30 次打卡、最近 7 条已保存目标和累计积分。
- PUT `/identity/api/v1/learning/oj-binding`：`provider: Codeforces, handle`。
- POST `/identity/api/v1/learning/points`：`title, problem_key, criterion`。
- POST `/identity/api/v1/learning/checkins`：`point_id, minutes, reflection`。
- POST `/identity/api/v1/learning/checkins/{id}/verify`：服务端读取 OJ 证据。
- POST `/identity/api/v1/learning/checkins/{id}/settle`：幂等结算。
- POST `/identity/api/v1/learning/next-day-plan`：`consent: true`。
- PUT `/identity/api/v1/learning/plans`：`learning_day, body`，显式保存明日目标。

以上均要求本人登录身份。其他 OJ 平台的设置仍保留，打卡验证当前仅支持 Codeforces，公开提交读取不需要 OJ API 密钥。

本机确定性验证覆盖错账号、错日期、未 AC、平台失败、绑定变更、未验证结算、跨用户读取、并发结算、重复题奖励以及模型上下文隔离。浏览器测试通过实际 MySQL 服务创建绑定、学习点和打卡，使用本地模拟模型验证规划、保存与刷新，不声称模拟输出来自真实模型。

本机还完成了真实 Codeforces API 读取和 MySQL 结算联调：无当日匹配提交返回 NO_MATCH，并结算基础 1 分；AC 奖励由确定性提交样本测试验证。
