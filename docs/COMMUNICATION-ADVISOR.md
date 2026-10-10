# 群内交流与顾问实现记录

群组讨论支持回复：GET /api/v1/group/discussions/{id}/comments 和 POST 同路径。成员权限在每次调用时核验，回复和 comment_created 同步事件原子保存；跨群组查询不会使用共享数据库。前端可读取、刷新和发送回复。

顾问接口为身份服务 POST /api/v1/advisor/messages，参数 group_id、question、consent=share-group-context。它转发身份票据至部署者配置的群组地址，核验成员资格并读取最近最多 10 条讨论（每条正文最多 2000 字符），随后调用模型。回答附带参考讨论 ID。模型无法获取数据库或写入群组；密钥只用于服务器请求头，用户资料不再返回密钥明文。

个人设置中的“顾问模型名称”需填写 API 支持的 model 值。默认模型端点为 https://api.openai.com/v1/chat/completions；部署者可通过 Spring 配置 advisor.endpoint 或环境变量 ADVISOR_ENDPOINT 改为兼容接口。模型提供方收到群组参考讨论与当前问题，前端必须明确确认后才发送。不配置密钥时返回 409，不生成假回答。群组连接映射通过 advisor.default-group-url、advisor.contest-group-url 设置，不能由前端任意指定。

已覆盖自动测试：两名成员发布和回复、非成员拒绝、空回复、不存在讨论、顾问无密钥、真实 HTTP 协议的模拟模型返回、上下文来源、成员资格失效、不回传密钥、未经确认请求拒绝。

尚待验证与补齐：真实供应商密钥下的回答质量、浏览器完整交互、公网 NAT 穿透/中继、多轮会话与历史、顾问超时及模型错误场景。当前实现不能作为上述项目已完成的证据。密钥目前存于本地 H2，静态加密尚未实现。
