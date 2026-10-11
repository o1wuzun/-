# 目标驱动的算法学习社区

**当前 Spring Boot + Vue 3 版本：** 先看 [使用手册与局域网步骤](docs/USER-MANUAL.md)。本机入口为 `http://127.0.0.1:5173/`；已有配置的服务器可用 `pwsh -File scripts/start-private.ps1 -BindAddress 0.0.0.0 -Database mysql` 切换到局域网模式，成员使用服务器 IPv4 地址访问 5173 端口。下面的 Python 启动章节仅用于旧原型。

正在迁移到 Spring Boot + Vue 3。新增代码在 `services/`、`frontend/`；启动步骤、已实现范围和后续拆分计划见 [迁移说明](docs/MIGRATION.md)。Python 原型保留在 `app/`，以下步骤用于旧原型。

Spring/Vue 版本已支持审批站内反馈、RabbitMQ 异步邮件、失败重试和投递状态查询，运行与 SMTP 配置见 [通知说明](docs/NOTIFICATIONS.md)。本机测试邮箱仅捕获邮件。

基于项目内 `API.md` 与 `TRANSACTION_LOGIC.md` 的模块化单体基线。首期使用 FastAPI + SQLite，提供可直接运行的 REST API；数据库、缓存和异步 worker 均通过边界保留后续替换空间。

## 启动

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
uvicorn app.main:app --reload
```

打开 `http://127.0.0.1:8000/` 使用前端，`http://127.0.0.1:8000/docs` 查看接口。默认数据库为项目目录 `community.db`，可通过 `COMMUNITY_DB` 指定路径。

当前原型聚焦“选择学习群组 → 在群组内学习 → 同步成员状态”。已实现注册登录、目标及计划、每日任务、打卡/总结、知识点和检查点提交、帖子/评论、通知、统一响应、Bearer 鉴权、版本冲突和幂等键。

## 局部联网群组原型

登录后先调用 `GET /api/v1/community/groups` 展示群组入口。创建群组：`POST /api/v1/community/groups`，服务端只在创建响应中返回一次 `join_key`；群主把密钥通过线下安全方式交给成员。成员登录后调用 `POST /api/v1/community/groups/{id}/join`，提交密钥、主机名和局域网地址。

群组的 `service_url` 是独立微服务边界的预留地址；当前原型仍由同一进程承载数据。客户端进入群组后每 30 秒调用 heartbeat，服务端将超过 90 秒没有心跳的成员标记为 offline，`GET /api/v1/community/groups/{id}/members` 返回同群组成员和主机状态。登录接口支持可选 `group_id`，成功登录时会立即把该成员标记为 online 并返回所选群组。

## 前端演示流程

1. 打开首页，点击登录 / 注册，建立自己的账号。
2. 创建学习群组，保存一次性显示的联网密钥；其他成员选择群组并输入密钥加入。
3. 进入群组后可以在群组讨论中发布问题、经验和复盘，在联网成员中查看在线状态。
4. 我的学习中创建个人目标，开始学习、完成任务、打卡和保存总结。个人目标不向其他成员公开。

群内讨论通过 `/community/groups/{id}/discussions` 提供，所有读写都会实时检查成员身份；退出群组后立即失去访问权。当前群组列表公开展示名称和简介，主机地址仅成员可读。

## 局域网运行

```powershell
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

同一网络内的其他设备访问 `http://群主主机的局域网IP:8000/`。操作系统防火墙需允许所选端口，项目不会自动改防火墙配置。当前为中心服务模式，不提供跨公网 NAT 穿透、主机互连、分布式数据复制或真正独立群组微服务。`service_url` 不会自动建立到远端服务的连接。

## 验证

```powershell
pip install -r requirements-dev.txt
python -m unittest discover -s tests -v
```

测试使用单独的临时数据库，不清空运行中的学习记录。覆盖群组密钥及隔离、成员退出后的权限、目标版本冲突、任务完成、打卡去重与前端静态文件。

当前原型尚无可执行的模型/OJ worker，检查点提交只保存 queued 记录；未把排队状态当作已评估或通过。
