# 审批通知与异步邮件

群组创建申请仍由普通用户提交。管理员批准或驳回时，同一个数据库事务写入审批结果、申请人的站内通知、邮件 outbox 任务。用户登录后可在“消息与申请”查看自己的申请、通知和邮件状态，导航显示未读数量。管理员在“邮件投递”查询状态并重试失败邮件。

**批准仅代表审批完成，不代表独立群组服务已部署成功。** 本阶段邮件发送审批结果，不包含群组永久密钥、公网凭证、节点密钥或数据库凭证。公开/私密群组的部署完成事件和一次性邀请邮件属于后续功能。

## 本机运行

项目使用本机 MySQL 3307、RabbitMQ 5672、测试 SMTP 1025。当前测试邮箱只捕获邮件，不向互联网转发；捕获记录在忽略提交的 `data/messaging/captured-mails.jsonl`，本次运行的邮件可通过 `http://127.0.0.1:1026/messages` 查看。

首次安装和启用：

```powershell
./scripts/install-messaging.ps1
./scripts/start-messaging.ps1 -TestMailbox
Copy-Item scripts/notifications.example.json data/notifications.json
./scripts/start-dev.ps1
```

如已有开发进程，先停止再启动。安装脚本使用固定版本 Erlang 27.3.4.4 与 RabbitMQ 4.1.4，验证 Erlang 安装包签名和 RabbitMQ 下载校验值，不注册 RabbitMQ Windows 服务。数据保存在本项目 `data/messaging`，`F:\community-mq` 为规避 Unicode 路径问题的目录联接。RabbitMQ 的 AMQP、节点通信和 EPMD 仅监听本机回环地址；未启用管理网站。guest 账号只用于回环开发环境。

停止队列和测试邮箱：`./scripts/stop-messaging.ps1`。仅停止队列保留测试邮箱：`./scripts/stop-messaging.ps1 -BrokerOnly`。停止开发服务不会清空队列、通知或测试邮箱。

## 配置真实邮件

修改 `data/notifications.json`，重启身份服务。该文件被 Git 忽略，部署脚本只把配置传给身份服务。可配置：

| 配置 | 用途 |
|---|---|
| RABBITMQ_HOST / PORT / USER / PASSWORD / VHOST | 队列连接 |
| SMTP_HOST / PORT | 邮件服务器，常用 STARTTLS 587 或 SSL 465 |
| SMTP_FROM | 发件邮箱，留空时任务为“等待邮件配置” |
| SMTP_USER / PASSWORD | 邮箱授权码或服务账号凭据 |
| SMTP_AUTH | 通常真实邮箱设为 true |
| SMTP_STARTTLS | 587 通常设为 true，启用时强制 TLS |
| SMTP_SSL | 465 通常设为 true |

主配置 `enabled=false` 或删除本机配置文件时，仍产生站内通知和数据库邮件任务，但不运行消费者。启用后自动处理积压任务，包括停用期间产生的任务。生产环境使用独立队列账号、SMTP TLS，并限制队列为内部网络；当前单机示例不提供跨机器 AMQP TLS 配置。

不应把该配置文件、测试捕获邮件、Erlang cookie 或数据库备份提交到 Git。测试捕获邮箱是无认证的本机调试服务，真实邮件部署不运行它。

## 可靠性与投递状态

- 审批、通知和 outbox 原子提交。事务失败时一起回滚。
- 每条任务 ID 与通知 ID 一一对应。RabbitMQ durable exchange/queue 和 persistent 消息只携带任务 ID，收件人、内容留在数据库。
- 发布等待 broker confirm，并检查不可路由返回；没有确认的任务不会标为已进入队列。队列故障保留任务，每 30 秒重试。
- 消费者原子获取任务状态，已发送消息再次到达时直接跳过。进程异常或滞留任务 5 分钟后恢复投递。
- SMTP 失败按 60、120、240、480 秒退避；第 5 次失败标为 FAILED，管理员可手动重试并重置发送次数。没有发件地址时每 60 秒检查配置，不消耗发送次数。
- SENT 表示 SMTP 服务已接收；不代表用户已读，也不保证最终到达收件箱。SMTP 接收成功后，如果数据库确认写入失败，恢复时可能重复发送。因此承诺至少一次投递，不能承诺邮件端严格恰好一次。
- 错误状态仅显示经过固定化处理的提示，不保存可能含凭据的异常文本。
- MySQL Flyway V2 添加 `notifications`、`mail_jobs`，原 V1 未更改；H2 同样建表。数据备份包含两表，旧 H2 离线快照缺少新表时按空表迁移，不修改旧快照。

## 验证

```powershell
mvn "-Dmaven.repo.local=F:/social/.m2build/repository" test
# 在进程环境设置本机测试管理员 TEST_ADMIN_EMAIL、TEST_ADMIN_PASSWORD 后：
node scripts/test-notifications.mjs
npm --prefix frontend run test:e2e
```

通知联调脚本只允许本机捕获 SMTP 配置，验证批准/驳回、正确收件人、通知隔离、SMTP 故障自动恢复和真实 RabbitMQ 重复消息。额外设置 `RUN_BROKER_FAULT_TESTS=1` 可停止并重启本项目 RabbitMQ，验证 outbox 故障恢复；该测试期间其他邮件任务也会等待。`PWSH_PATH` 可指定 PowerShell 7 可执行文件路径。

单元/服务测试覆盖事务回滚、权限、已读隔离、确认发布、不可路由返回、重复消息、发布/消费竞态、5 次失败上限和租约恢复。浏览器测试覆盖普通用户申请、管理员审批、通知查看与刷新后的已读状态。

接口：`GET /api/v1/notifications`、`GET /api/v1/notifications/unread-count`、`PATCH /api/v1/notifications/{id}/read`；管理员使用 `GET /api/v1/admin/mail-jobs`、`POST /api/v1/admin/mail-jobs/{id}/retry`。列表目前返回最近 100 条，完整历史保留在数据库中。
