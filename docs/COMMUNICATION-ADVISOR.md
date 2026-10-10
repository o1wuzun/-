# 群内交流与顾问实现记录

群组讨论支持回复：GET /api/v1/group/discussions/{id}/comments 和 POST 同路径。成员权限在每次调用时核验，回复和 comment_created 同步事件原子保存；跨群组查询不会使用共享数据库。前端可读取、刷新和发送回复。

顾问接口为身份服务 POST /api/v1/advisor/messages，参数 group_id、question、consent=share-group-context。它转发身份票据至部署者配置的群组地址，核验成员资格并读取最近最多 10 条讨论（每条正文最多 2000 字符），随后调用模型。回答附带参考讨论 ID。模型无法获取数据库或写入群组；密钥只用于服务器请求头，用户资料不再返回密钥明文。

个人设置中的“顾问模型名称”需填写 API 支持的 model 值。默认模型端点为 https://api.openai.com/v1/chat/completions；部署者可通过 Spring 配置 advisor.endpoint 或环境变量 ADVISOR_ENDPOINT 改为兼容接口。模型提供方收到群组参考讨论与当前问题，前端必须明确确认后才发送。不配置密钥时返回 409，不生成假回答。群组连接映射通过 advisor.default-group-url、advisor.contest-group-url 设置，不能由前端任意指定。

已覆盖自动测试：两名成员发布和回复、非成员拒绝、空回复、不存在讨论、顾问无密钥、真实 HTTP 协议的模拟模型返回、上下文来源、成员资格失效、不回传密钥、未经确认请求拒绝。

多轮会话保存于身份数据库 advisor_turns，按 user_id、group_id 隔离。GET /api/v1/advisor/history?group_id=... 读取最近 20 轮，发送给模型的历史最多 6 轮；DELETE 同路径只删除本人的指定群组会话。读取时核验成员权限，生成后再次核验，失败不保存假回答。顾问会话不会复制给群成员。设置页载入已保存配置，密钥字段留空保留原值。

模型接口地址可由用户在个人设置填写，PUT /api/v1/users/me/integrations 中使用 advisor_endpoint（完整 chat/completions 地址）、advisor_provider（模型名称）、advisor_api_key（密钥）。要求 HTTPS，本机开发接口允许 http://127.0.0.1；服务端不跟随重定向。该配置仅影响当前用户的调用。

补充审查：自动测试已验证用户会话隔离、群组会话隔离、清空本人历史、模型限流错误不保存会话、配置保存不回传密钥、空密钥保留原值。入组请求改为只发送联网密钥、昵称、设备名，避免个人模型配置随表单发送给群组。已展开的评论跟随成员心跳刷新。

尚待验证与补齐：真实供应商密钥下的回答质量、公网 NAT 穿透/中继。当前实现不能作为上述项目已完成的证据。用户选择自行提供模型配置，真实供应商测试由配置后执行。浏览器工具故障通过项目内 Playwright 验收替代；双账号完整交互及桌面、窄屏截图验收通过。模型超时、空响应、请求并发和清空历史竞争已通过 AdvisorConcurrencyTest。密钥目前存于本地 H2，静态加密尚未实现。
