# Spring Boot + Vue 3 迁移计划

确认的产品决策：成员节点保有群组副本、每个群组独立进程及数据库、支持公网连接、邀请密钥用于入组而非所有成员共用登录、私人学习数据默认不复制、离线仅阅读群组缓存并记录个人学习、统一账号。知识检查、Codeforces、AI、互评均纳入最终原型。

## 当前已实现

- Maven 多模块，Spring Boot 3.3.5、Java 21，Vue 3 + Vite。
- identity-service：统一注册登录、BCrypt 密码、RSA 签发一小时身份票据，独立 H2 文件数据库。
- group-service：一个进程只管理配置的一个群组，独立 H2 数据库，联网密钥加入、成员心跳、成员撤销、群内讨论及事务事件日志。
- shared-contracts：响应协议和身份验签，不含共享实体或跨服务数据库访问。
- Vue 前端：注册登录、当前群组入口、入组、讨论和成员在线状态；刷新登录后尝试心跳恢复成员状态。

身份服务持有私钥，群组进程仅持有公钥。节点复制不得含账号表、密码或身份私钥。普通成员凭证只能读事件，不允许通过复制接口写入数据。事件读取只完成增量读取边界，尚无节点认证、拉取落库或自动复制。

## 开发运行

```powershell
mvn test package
cd frontend
npm install
npm run build
cd ..
pwsh -File scripts/start-dev.ps1
```

首页 `http://127.0.0.1:5173`；身份服务 8081；默认群组服务 8082。首次运行生成开发密钥到被 Git 忽略的 `data/dev-keys.json`，入组用其中的 join_key。本地原型不预建用户，需在前端注册。

群组实例部署参数：GROUP_ID、GROUP_NAME、GROUP_PORT、GROUP_DB_URL、GROUP_JOIN_KEY、GROUP_OWNER_ID，以及公共 IDENTITY_PUBLIC_KEY。不同群组必须使用不同数据库路径与邀请密钥。GROUP_OWNER_ID 由部署者指定统一账号 ID，未配置时不开放群主管理能力。

前端从身份服务 `/api/v1/community/groups` 读取部署者配置的群组目录，通过固定代理路由切换群组，不接受客户端任意代理地址。默认运行两个独立群组（8082 基础组、8083 竞赛组），数据库和入组密钥分别独立。密钥保存在本机 data/dev-keys.json：join_key 对应基础组，contest_join_key 对应竞赛组，不要上传此文件。公网部署需要 HTTPS 反向代理与可达地址，当前没有 NAT 穿透或中继。当前票据不是标准 JWT，尚无刷新令牌、会话撤销或登录限流；开发版本不能直接当作生产认证服务。

## 节点认证与复制边界

成员可以在“同步节点”页面为自己的设备注册独立凭证，凭证仅创建时返回，数据库仅保存 SHA-256 摘要。注册、列举和撤销接口为 `/api/v1/group/nodes`。本人可以撤销自己的节点，配置的群主也可以撤销节点。每次读取同时校验节点及所属成员是否仍有效。

节点使用 `X-Node-Credential` 请求 `/api/v1/group/replication/events?after=0`，得到 group_id、事件、next_cursor 和 has_more。页大小 100；后续使用 next_cursor 续读。该凭证不拥有发布帖子、修改成员或其他写入权限，且不能跨群组使用。

当前完成的是服务端节点身份和增量读取协议，还没有成员节点的独立副本数据库、事件消费去重、定时拉取及离线读取。此阶段不宣称自动复制已实现。将来节点副本只能接受经过验证的群组事件；个人目标、密码、身份私钥不进入复制事件。

```powershell
pwsh -File scripts/test-group-isolation.ps1
pwsh -File scripts/stop-dev.ps1
```

隔离联调会创建明确标记的本地测试账号与讨论，不清空已有记录。stop-dev 只停止路径核验匹配本项目的服务及 Java 子进程，避免 Windows 启动包装进程退出后残留 JVM 锁住构建文件。

## 后续模块与顺序

1. 身份服务增加注册节点 / 群组目录，群组服务验证身份后发独立成员及节点凭证；群主撤销凭证需同步撤销版本。
2. replication 模块：不可变 event_id、来源节点、因果版本、单写者资源归属、消费去重、断线续传；先双节点验证，再多节点。
3. learning 模块：迁移个人目标、任务、打卡、复盘，不进入群组副本；主动分享才写群组事件。
4. assessment 模块：确定性知识检查及版本化规则；评价和 accepted 不能直接冒充能力通过。
5. oj-adapter 模块：Codeforces，重试、授权版本及来源明确。
6. assistant 模块：平台检索、外网和模型分别授权，无模型时明确降级。
7. peer-review 模块：按帖子版本互评，不影响能力通过。

中心身份暂时离线时，已有未过期票据可用于群组服务；新的登录需要身份服务恢复。各成员节点离线读缓存、恢复后补传个人记录仍未实现。后续的拆分建议优先调整模块边界，不增加跨服务共享数据库。

原 Python 版本保持在 app/，可继续用 8000 端口运行；Git 标签 prototype-python-v1 和 backups/python-prototype-v1.zip 可用于回退。两个版本当前数据不自动迁移。
