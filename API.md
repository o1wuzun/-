# 目标驱动的算法学习社区平台接口文档

版本 v1.0 ｜ 2026-10-08 ｜ 设计规范，尚未实现

依据《目标驱动的算法学习社区平台设计与实现》开题报告编写。配套流程见 [事务逻辑文档](TRANSACTION_LOGIC.md)。用于前后端开发、联调和测试，接口均为本平台接口。

## 1. 范围与权限

覆盖用户认证、目标路径、每日任务、知识点、检查点、打卡、每日总结与周复盘、小组互评、Agent、个人认知库、OJ 同步、资料推荐、通知和后台管理。第一版至少实现一个可用 OJ 适配器，不包含自建在线判题和模型训练。

| 角色 | 权限 |
| --- | --- |
| 游客 | 公开帖子、小组简介和知识点 |
| 学习者 | 自己的目标、记录、认知、会话、集成，以及已加入小组的内容 |
| 组长 | 学习者权限及本组审批、移除成员、隐藏内容 |
| 管理员 | 模板发布、公开内容审核、举报处理；无默认私密会话读取权 |

客户端不得通过 user_id 指定授权对象。私有资源同时检查归属、可见范围和当前成员身份；跨用户不可见资源返回 404。

## 2. 通用协议

- HTTPS，前缀 `/api/v1`，JSON UTF-8，字段 snake_case。
- 访问令牌放在 `Authorization: Bearer <token>`；刷新令牌采用 HttpOnly、Secure Cookie，刷新入口需 CSRF 保护。
- ID 为字符串，时间戳为 UTC ISO 8601；日期 YYYY-MM-DD，任务日期按目标时区计算。
- 游标分页：cursor、limit，默认 20，最大 100；返回 next_cursor、has_more。
- 查询、更新 200，创建 201，异步受理 202，删除 204 且无响应体。
- 下文示例均为 data 部分，实际套用以下结构。

```json
{"request_id":"req_001","data":{},"meta":{"next_cursor":null,"has_more":false}}
```

```json
{"request_id":"req_001","error":{"code":"VERSION_CONFLICT","message":"记录已更新，请重新读取","details":[{"field":"version","reason":"stale"}]}}
```

| HTTP | 错误码 | 含义 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 参数不合法 |
| 401 | UNAUTHORIZED | 身份失效 |
| 403 | FORBIDDEN | 无操作权限 |
| 404 | RESOURCE_NOT_FOUND | 不存在或不可见 |
| 409 | VERSION_CONFLICT / INVALID_STATE / IDEMPOTENCY_CONFLICT | 版本、状态或幂等内容冲突 |
| 422 | CAPABILITY_UNSUPPORTED / EVIDENCE_NOT_ELIGIBLE | 能力不支持或证据不符合要求 |
| 429 | RATE_LIMITED / BUDGET_EXCEEDED | 按 Retry-After 重试或调整预算 |
| 503 | SERVICE_UNAVAILABLE | 服务暂不可用 |

资料不足、低置信度是业务结果，不作为 HTTP 错误。

### 2.1 幂等和异步

提交检查点、打卡、评估、Agent 消息、同步及认知确认必须带 Idempotency-Key。键按用户、方法、路径隔离并保留至少 24 小时；同键同内容返回原状态码和结果，不同内容返回 409。业务唯一约束负责长期去重。

可修改资源返回整数 version，PATCH 及状态变更携带 version；过期返回 409。异步结果冻结输入版本，不能覆盖新版本。

异步受理统一响应：

```json
{"job_id":"job_001","status":"queued","status_url":"/api/v1/jobs/job_001"}
```

`GET /jobs/{id}` 仅任务所有者可查询，返回 status、result_resource、error_code、attempt_count、updated_at。status 为 queued/running/succeeded/failed/cancelled。任务 succeeded 不等于检查点通过。

### 2.2 初始限制

标题 1—100 字符，正文/总结最多 20,000 字符，代码最多 64 KB，请求体最多 256 KB，引用最多 10 条。写操作每用户每分钟 30 次；Agent 每分钟 5 次且最多 1 个运行任务；总结评估每分钟 3 次；手动同步每账号每 5 分钟 1 次。模型另设全局并发和日预算，OJ 另设 provider 全局限额；参数需压测调整。

## 3. 用户认证

| 方法 | 路径 | 输入或返回 |
| --- | --- | --- |
| POST | /auth/register | email、password、nickname；201 用户资料 |
| POST | /auth/login | email、password；access_token、expires_in，设置刷新 Cookie |
| POST | /auth/refresh | 刷新 Cookie；轮换令牌 |
| POST | /auth/logout | 撤销会话，204 |
| GET | /users/me | 本人资料和 version |
| PATCH | /users/me | nickname、timezone、version |

邮箱唯一；密码强哈希存储，登录按账号及 IP 独立限流；注销或封禁撤销会话。

## 4. 学习目标、路径、任务与进度

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /goals | 创建 draft 目标 |
| GET | /goals | 本人目标列表，status 过滤 |
| GET | /goals/{id} | 目标配置、version、plan_version |
| PATCH | /goals/{id} | 标题、截止日期、时长、version；涉及计划调整返回 202 |
| POST | /goals/{id}/transitions | action=activate/pause/resume/archive、version |
| GET | /learning-paths | 路径模板，goal_type、level 过滤 |
| GET | /goals/{id}/plan | 阶段、检查点、计划版本 |
| GET | /goals/{id}/today | 目标时区当日任务 |
| GET | /goals/{id}/tasks | date、status 过滤 |
| POST | /tasks/{id}/completions | evidence_ids、notes；普通任务完成记录 |
| GET | /goals/{id}/progress | 当前进度快照 |

创建示例：

```json
{
  "title":"90 天算法训练","goal_type":"interview","level":"beginner",
  "path_template_id":"path_001","target_date":"2027-01-06",
  "daily_minutes":60,"timezone":"Asia/Shanghai"
}
```

goal_type 为 interview/foundation/topic/contest；level 为 beginner/intermediate/advanced；daily_minutes 为 10—480；截止日期不早于当前本地日期。activate 返回 202，生成成功才进入 active。pause、archive 返回 200；resume 如需重排则返回 202。

今日任务返回：goal_id、local_date、timezone、plan_version、tasks。task 包含 id、type=study/practice/reflection/checkpoint/remedial、title、knowledge_point_ids、checkpoint_id（可为空）、estimated_minutes、due_at、status=pending/in_progress/completed/cancelled。

普通完成记录返回 evidence_level=self_reported/externally_observed。检查点类任务仅由评估服务完成，客户端不能直接设置通过。

进度示例：

```json
{
  "goal_id":"goal_001","snapshot_version":12,"plan_version":1,
  "task_completion_rate":0.6,"checkpoint_completion_rate":0.4,
  "mastery_score":68,"mastery_coverage":0.5,"streak_days":7,
  "risk":"at_risk","is_stale":false,"next_checkpoint_id":"cp_002",
  "computed_at":"2026-10-08T13:00:00Z"
}
```

比例范围 0—1，评分 0—100，未评估 mastery_score=null。risk 为 on_track/at_risk/overdue/paused/completed。计算公式见事务文档。

## 5. 知识点和检查点

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /knowledge-points | keyword、topic、level 过滤 |
| GET | /knowledge-points/{id} | 定义、前置知识、适用条件、资料、版本 |
| GET | /checkpoints/{id} | 所属目标、检查规则、提交要求和规则版本 |
| POST | /checkpoints/{id}/submissions | 提交尝试，202 |
| GET | /checkpoints/{id}/submissions | 本人历史尝试 |
| GET | /submissions/{id} | 状态、分数、来源、失败项与补救任务 |

提交示例：

```json
{
  "attempt_id":"attempt_001","checkpoint_version":1,
  "answers":[{"question_id":"q_001","answer":"左闭右开"}],
  "code":"...","language":"java",
  "explanation":{"idea":"...","time_complexity":"O(log n)","space_complexity":"O(1)","boundary_cases":"..."},
  "evidence_ids":["evidence_001"]
}
```

code 可选，第一版仅存储，不在服务端执行。证据必须属于本人且匹配题目；仅题目链接不能证明提交通过。

提交状态为 queued/evaluating/passed/failed/needs_review/evaluation_error/superseded。评估返回 rule_results、scores、sources、remedial_task_ids；外部服务故障为 evaluation_error，不能判学习失败。必选项未验证为 needs_review。

## 6. 打卡、每日总结与周复盘

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /goals/{id}/checkins | minutes_spent、work_summary、task_ids、可选 reflection_id |
| GET | /goals/{id}/checkins | start_date、end_date 过滤 |
| POST | /goals/{id}/reflections | 保存 daily 或 weekly 总结 |
| GET | /goals/{id}/reflections | 日期和 period_type 过滤 |
| GET | /reflections/{id} | 当前正文及 version |
| PATCH | /reflections/{id} | 修改正文，携带 version |
| POST | /reflections/{id}/evaluations | reflection_version；202 |
| GET | /reflections/{id}/evaluations | 按版本查询评估历史 |
| GET | /evaluations/{id} | 评分、缺失、错误、证据和建议 |

打卡日期服务端生成，第一版不补签；minutes_spent 为 1—1440。同一目标每日一条，相同内容重复返回既有记录，不同内容返回 409。总结和打卡独立，模型故障仍可保存记录。

总结请求：

```json
{
  "period_type":"daily","period_start":"2026-10-08","period_end":"2026-10-08",
  "knowledge_point_ids":["kp_binary_search"],
  "core_definition":"...","applicable_conditions":"...","extension_methods":"...",
  "complexity_analysis":"...","examples":"...","mistakes":"...","questions":"...","next_step":"..."
}
```

daily 起止日期相同；weekly 为目标时区周一至周日。核心定义必填，其他字段可空，交由评估提示缺失。

评估结果示例：

```json
{
  "id":"eval_001","reflection_version":1,"status":"needs_review",
  "skill_version":"summary-rubric-1","model_profile_id":"platform_default",
  "scores":{"definition":80,"conditions":60,"complexity":70,"transfer":50,"expression":80},
  "overall_score":67,"confidence":"medium","verification_status":"partially_verified",
  "issues":[{"dimension":"conditions","type":"missing","description":"需说明单调条件","source_ids":["source_001"]}],
  "sources":[{"id":"source_001","type":"platform_knowledge","resource_id":"kp_binary_search","version":2}],
  "suggested_task_ids":[],"memory_candidate_id":null
}
```

评估 status 为 queued/running/completed/needs_review/failed/superseded；verification_status 为 verified/partially_verified/unverified/conflicting。模型不可用时语义评分为 null，仅返回确定性完整性检查。Skill 为版本化量表和工具约束，首期不表示已训练模型。

## 7. 小组、发布内容和互评

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /groups | keyword、goal_type、level 过滤 |
| POST | /groups | title、description、goal_type、level、capacity、join_policy、visibility |
| GET | /groups/{id} | 简介，成员信息仅成员可见 |
| POST | /groups/{id}/memberships | 加入或申请；201 pending/active |
| PATCH | /groups/{id}/memberships/{membership_id} | 组长 action=approve/reject/remove、version |
| POST | /groups/{id}/ownership-transfers | 组长指定 new_owner_membership_id、version |
| DELETE | /groups/{id}/memberships/me | 退出；组长须先移交 |
| GET | /groups/{id}/posts | 小组动态 |
| POST | /posts | group_id、type、title、body、knowledge_point_ids、visibility |
| GET | /posts/{id} | 当前帖子及 version |
| PATCH | /posts/{id} | 作者 title、body、version；重新审核 |
| DELETE | /posts/{id} | 作者软删除 |
| POST | /posts/{id}/comments | body、可选 parent_id |
| GET | /posts/{id}/comments | 游标分页 |
| PUT | /posts/{id}/reviews/me | rubric_scores、comment、post_version；创建或更新本人评分 |
| GET | /posts/{id}/reviews | 当前版本评分、均值和人数 |
| POST | /reports | target_type、target_id、reason |

capacity 范围 2—50；join_policy 为 open/approval；visibility 为 public/group，帖子不可比小组权限更宽。帖子 type 为 question/experience/reflection/resource。

rubric_scores 含 correctness、clarity、practical_value，各 1—5；禁止自评，同人同帖一条有效评分。内容改版后旧评分留存但不计当前均值。用户评分不能独立通过能力检查点。

## 8. Agent 设置、检索和资料推荐

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET / PATCH | /users/me/agent-settings | retrieval_mode、allow_public_web、allow_external_model、model_profile_id、version |
| POST | /agent/sessions | goal_id、title；创建本人会话 |
| GET | /agent/sessions | 本人会话列表 |
| DELETE | /agent/sessions/{id} | 删除并取消运行任务，清理索引 |
| POST | /agent/sessions/{id}/messages | question、context、retrieval_mode；202 |
| GET | /agent/sessions/{id}/messages | 消息和引用 |
| POST | /agent/messages/{id}/feedback | rating=helpful/unhelpful、reason |
| GET | /resources/recommendations | goal_id、knowledge_point_id；学习资料推荐 |
| POST | /resources/{id}/feedback | action=save/not_interested/report |

retrieval_mode 默认 platform_only，可选 external_if_needed。单次消息只能收紧账号已授权范围。platform_only 只检索平台内部可见资料，资料不足可提示开启外部检索，不暗中联网。

三项授权独立：OJ 绑定、公开网络检索、模型 API 外发。模型外发授权须说明将发送问题和必要检索片段；禁用后使用已部署本地模型，否则降级为检索摘录和模板提醒。

消息输入示例：

```json
{"question":"为什么我总是写错二分边界？","context":{"checkpoint_id":"cp_001","reflection_id":"reflection_001"},"retrieval_mode":"platform_only"}
```

任务完成后的消息记录：

```json
{
  "id":"message_001","status":"completed","answer":"你的复盘中混用了区间定义……",
  "answer_status":"supported","confidence":"medium",
  "sources":[{"id":"source_001","type":"user_reflection","resource_id":"reflection_001","version":1,"excerpt":"...","fetched_at":"2026-10-08T13:00:00Z"}],
  "external_lookup":{"used":false,"providers":[]},"conflicts":[],"memory_candidate_id":"candidate_001"
}
```

status 为 queued/running/completed/failed/cancelled；answer_status 为 supported/insufficient_evidence/conflicting。外部 sources 还须 url、title、published_at（未知为 null）、fetched_at。置信度仅作提示，不直接决定成绩。

资料推荐返回 title、url、知识点、推荐原因和更新时间；优先官方及审核资料，不因链接存在就认定观点正确。

### 8.1 用户自选模型 API（扩展阶段）

首期使用平台统一配置，预留用户自选能力：

- `GET /model-profiles`：可用平台配置及本人配置，无密钥。
- `POST /model-profiles`：provider_id、model_name、api_key、daily_budget，201。
- `POST /model-profiles/{id}/checks`：异步连接检查，202。
- `DELETE /model-profiles/{id}`：撤销并清除密钥。

api_key 只写、加密存储，不进入日志、上下文和查询响应。仅允许平台登记 provider，首期不接受任意 base_url；接通 API 不代表结果更准确。

## 9. 个人认知库与纠错

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /memories | keyword、knowledge_point_id、status 过滤 |
| GET | /memories/{id} | 当前认知、版本和证据状态 |
| GET | /memories/{id}/versions | 历史版本 |
| GET | /memory-candidates/{id} | 候选正文、来源、version |
| POST | /memory-candidates/{id}/decisions | action=confirm/reject、candidate_version、可选 edited_content |
| POST | /memories/{id}/corrections | reason、proposed_content、source_ids、base_version |
| POST | /memory-corrections/{id}/decisions | action=confirm/reject、version |
| DELETE | /memories/{id} | 立即撤销可见性，异步清理 |

生成候选不等于保存认知；只有用户确认后写入个人认知库。确认不把证据自动升级为 verified。确认时重检来源权限和版本，过期候选返回 409；纠错产生新版本，旧版保留本人历史。

## 10. OJ 适配与开放 API

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /integrations/providers | 已实现能力、状态、认证方式 |
| POST | /integrations | provider、handle、scopes、consent_version |
| GET | /integrations | 本人绑定、last_success_at 和状态 |
| PATCH | /integrations/{id} | enabled、scopes、version |
| POST | /integrations/{id}/sync-jobs | scope；202，合并同账号运行任务 |
| GET | /integrations/{id}/submissions | 标准化提交及数据时间 |
| DELETE | /integrations/{id}?delete_history=true | 立即停止同步，默认异步清除本人明细 |
| GET | /problems | provider、tag、difficulty_min、difficulty_max |
| GET | /problems/{id} | 本地题目元数据与原题链接 |

scopes 按 provider 能力提供 problem_metadata/user_submissions/rating_history。未实现的平台明确不可用，不承诺所有 OJ 有官方 API。Codeforces 公共 handle 查询不等于 OAuth 或账号归属验证。

```json
{
  "id":"evidence_001","provider":"codeforces","integration_id":"integration_001",
  "external_submission_id":"123456789","problem_key":"1234/A","verdict":"accepted",
  "submitted_at":"2026-10-08T12:00:00Z","fetched_at":"2026-10-08T13:00:00Z",
  "source_url":"https://codeforces.com/contest/1234/submission/123456789",
  "identity_assurance":"unverified_handle"
}
```

verdict 为 pending/accepted/wrong_answer/time_limit/memory_limit/compile_error/runtime_error/unknown，原值另存 raw_verdict。未验证 handle 仅供个人跟踪；通过结果不能证明独立完成或总结正确。

### 10.1 第三方开放读取（扩展阶段）

开放的是本人已同步数据，不是无限代理外部 OJ：

- `POST /developer/tokens`：name、scopes、expires_at；仅创建时返回完整 token。
- `GET /developer/tokens`：掩码及元数据。
- `DELETE /developer/tokens/{id}`：立即撤销。
- `GET /open/v1/goals/{id}/progress`：scope=progress:read。
- `GET /open/v1/integrations/{id}/submissions`：scope=oj:read。

open 路径不拼 /api/v1；token 最长 90 天，每分钟初始 60 次，仍受用户和全局限制。首期无第三方写入、同步触发或模型调用权限。

## 11. 通知与 SSE

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | /notifications | unread 过滤 |
| PATCH | /notifications/{id} | read=true |
| GET / PATCH | /users/me/reminder-settings | enabled、timezone、preferred_time、quiet_start、quiet_end、daily_limit、version |
| GET | /events/stream | 本人 SSE 事件流 |

daily_limit 为 0—5，安静时间支持跨午夜。进度提醒由规则调度，不持续调用模型检查。

事件为 progress_updated、evaluation_completed、agent_message_completed、checkpoint_due、goal_at_risk、provider_sync_finished、notification_created。

```text
id: evt_001
event: progress_updated
data: {"goal_id":"goal_001","snapshot_version":12}

```

浏览器使用带 Authorization 的 fetch 流，避免令牌进 URL。20 秒心跳、指数退避重连，Last-Event-ID 重放最近 24 小时；超出窗口发送 resync_required 后重新拉取快照。按 event_id 去重，资源查询为事实来源。无 SSE 时可见页面每 60 秒轮询，页面隐藏暂停。

## 12. 管理接口与验收

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /admin/knowledge-points | 创建知识点 |
| PATCH | /admin/knowledge-points/{id} | 版本化修改 |
| POST | /admin/path-templates | 创建路径草稿 |
| POST | /admin/path-templates/{id}/publish | 校验依赖后发布 |
| POST | /admin/rubrics | 创建 Skill 评分规则草稿 |
| POST | /admin/rubrics/{id}/publish | 冻结版本，历史评估不变 |
| GET | /admin/reports | 举报待办 |
| POST | /admin/reports/{id}/decisions | action=hide/restore/reject、reason、version |
| GET | /admin/metrics | 聚合指标，无私密正文 |

联调必须验证：重复提交和事件不重复累计；外部故障仍可打卡；关闭外部授权后停止调用；旧评估不覆盖新总结；退出小组立即失去检索权限；评分及 OJ 同步不直接冒充能力通过；SSE 重放与数据库状态一致。
