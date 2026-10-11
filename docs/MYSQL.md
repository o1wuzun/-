# 本地 MySQL 与迁移

本项目的身份服务、基础群组、竞赛群组已切换到独立 MySQL 8.0 实例。使用现有 MySQL 安装的程序文件，在项目 data/mysql 内保存独立数据，监听 **127.0.0.1:3307**。原来的 MySQL80 服务和 3306 实例未修改。公网网关不发布数据库端口。

## 运行与账号

执行 `pwsh -File scripts/start-dev.ps1` 或 start-private 会根据被 Git 忽略的 data/database-mode.json 选择数据库；已完成迁移时自动启动项目 MySQL。手动启动数据库用 start-mysql.ps1，关闭社区后再执行 stop-mysql.ps1，正常关闭数据库。

| 服务 | 数据库 | 应用账号权限 |
| --- | --- | --- |
| 身份与个人资料 | community_identity | 仅自身库的查询与数据写入 |
| 基础群组 | community_default | 仅自身库的查询与数据写入 |
| 竞赛群组 | community_contest | 仅自身库的查询与数据写入 |

每个库有独立应用账号和迁移账号。Flyway 使用该库的迁移账号维护版本；业务查询使用应用账号。应用账号不能建表或访问其他服务数据库，已用真实 MySQL 验证。迁移密码仍作为本机服务配置存在，进一步隔离可以把 Flyway 移到独立发布流程。

随机密码保存在 data/mysql/project-config.json，不在命令行传递、不提交 Git。该文件、数据库与备份应保持私有；个人模型密钥目前仍以明文保存在数据库，静态加密尚未完成。

Windows MySQL 无法稳定重新打开含中文路径的数据目录，因此本机使用 F:\community-mysql 目录联接指向 F:\社区\data\mysql。实际文件仍在项目 data/mysql 下。如果此联接已属于其他项目，启动脚本会拒绝使用。

## 首次切换

```powershell
mvn test package
pwsh -File scripts/start-mysql.ps1
pwsh -File scripts/migrate-mysql.ps1
pwsh -File scripts/start-dev.ps1
```

迁移脚本先关闭公网和社区服务，离线备份三个 H2 文件，再初始化 MySQL 表结构。它按外键顺序复制记录，保留 ID、密码摘要、成员、讨论、回复、事件游标、节点凭证摘要及申请记录。每个数据库内的复制使用事务，发生错误时回滚该库；拒绝覆盖已存在的业务数据。多个数据库之间不是单一事务，失败时不会启动应用或标记切换成功，原始 H2 与备份都保留，需核对各库状态后处理，不能盲目重复导入。

每表按主键排序后计算记录数与内容摘要，核对成功才标记 MySQL 为默认数据库。迁移最后强制关闭公网权限；切换不会自动恢复公开访问。后续迁移输出保存在 data/mysql/import-verification.log。

本次迁移核验身份库的 33 个账号、基础组的 28 个成员／20 条讨论／10 条回复，以及竞赛组的 12 个成员；其他设置、申请、节点、邀请与事件表也通过内容核验。随后测试产生的新记录不属于此初始计数。

## 备份与回退

```powershell
java -jar services/database-tools/target/database-tools-0.1.0-SNAPSHOT.jar backup data/mysql/project-config.json F:/社区
```

这会在 data/mysql-backups 中生成应用数据 JSON 快照。每个库采用一致性事务读取；跨库一致性备份应先停止社区写入。它不是完整 mysqldump，也没有自动恢复功能。表结构由 Flyway 版本文件维护；正式长期运维仍需要 SQL 备份、恢复演练和备份加密。

紧急切回保留的 H2：停止公网和社区，再使用 `pwsh -File scripts/start-dev.ps1 -Database h2`，或 `start-private.ps1 -Database h2`。该模式使用迁移前保留的 H2，**不会包含切换后新增的 MySQL 数据**。操作不覆盖 MySQL；恢复 MySQL 运行时使用 `-Database mysql`。不能把这种回退当成双向同步。

## 验证

全套 Java 测试在 H2 下通过；ImportSafetyTest 验证 Unicode／空值保留、拒绝覆盖与失败回滚。真实 MySQL 已验证群组讨论回复、顾问配置及多轮会话、账号与群组隔离、节点凭证撤销和浏览器完整操作。数据库正常重启后，三个库的数据快照逐项相同。真实公网浏览器的管理员邀请兑换及撤销测试也在 MySQL 下通过，结束后关闭权限并停止公网隧道。

```powershell
java -jar services/database-tools/target/database-tools-0.1.0-SNAPSHOT.jar check data/mysql/project-config.json F:/社区
pwsh -File scripts/test-communication-advisor.ps1
pwsh -File scripts/test-group-isolation.ps1
```

成员设备的只读副本仍可使用本地 H2 缓存；中心身份和群组数据库使用 MySQL。本轮未实现邮件通知、RabbitMQ 或独立 PC 安装包。
