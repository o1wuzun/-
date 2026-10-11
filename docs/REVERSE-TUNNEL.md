# 出站中继部署与验收

## 用当前电脑作为公网主机

已提供 Windows 启动流程：当前电脑运行身份服务、两个群组服务、反向中继与出站连接端。公网入口通过 Cloudflare 临时隧道到达本机 8098 网关。网关仅提供构建后的前端和明确的社区 API，不公开 Vite 开发服务、项目目录、数据库或本机凭证文件。

首次使用：

```powershell
mvn package
npm --prefix frontend ci
npm --prefix frontend run build
pwsh -File scripts/install-cloudflared.ps1
pwsh -File scripts/start-dev.ps1
pwsh -File scripts/start-public.ps1
```

已有社区服务运行时跳过 start-dev。安装脚本从 Cloudflare 官方 GitHub 下载连接程序并验证 Windows 发布者签名。连接端自动选择 QUIC 或 HTTP/2；启动脚本先检查连接就绪，再显示 HTTPS 地址。该地址保存在被 Git 忽略的 `data/public-processes.json`。不需要路由器端口转发或公网 IP，电脑需保持联网、开机且不休眠。临时域名重启后可能变化，Cloudflare 负责公网转发及 HTTPS；长期使用需要命名隧道和固定域名。临时隧道服务不保证持续可用。

本地预检可用 `pwsh -File scripts/start-public.ps1 -LocalOnly`，不会建立外部隧道。`pwsh -File scripts/stop-public.ps1` 只停止公网连接、网关、中继和群组连接端，保留社区服务；随后执行 stop-dev 可停止社区。

公网验收：管理员启用访问并生成测试邀请后运行 `pwsh -File scripts/test-public.ps1 -InviteToken <一次性测试邀请>` 经真实公网 HTTPS 入口验证注册登录、入组、两个用户发布回复、组间访问限制及私密文件拒绝。此脚本从主机发出请求，仍需手机关闭 Wi-Fi 后通过移动网络打开显示的 URL，并与电脑上的另一个成员测试讨论回复，才能确认独立网络设备体验。

本机公网模式下，顾问仍从本机群组服务读取上下文。外部成员及其他主机的副本可使用 `https://临时域名/groups/group-default` 作为群组源地址；另外的群组主机连接此中继时使用该域名作为 TUNNEL_RELAY_URL，并由主机维护者私下配置独立中继凭证。中继凭证在 `data/relay-keys.json`，它与群组邀请密钥、成员登录和节点复制凭证分别独立，不能公开分享。

## 架构

公网服务器运行 relay-service。每个内网群组主机运行自己的 group-service 和 tunnel-client。主机使用 HTTPS 主动轮询中继，不需要为群组服务开放入站端口。外部成员访问 `/groups/{group_id}/api/v1/group/...`，中继将请求放入对应群组的有界队列，主机执行请求并回传响应。该方案是反向 HTTP 中继，不是直接 P2P 打洞。

身份服务与前端需要部署在成员可访问的地址。群组主机仅持有身份公钥。中继只配置群组出站连接密钥，不持有身份私钥。该密钥不能替代成员登录凭证或节点凭证；成员与复制权限仍由 group-service 校验。中继运营者能看到经过中继的数据，应部署在可信服务器，公网必须使用 HTTPS。

## 配置公网中继

构建项目后，在公网服务器环境配置：

```powershell
# 每组分别生成独立随机密钥，并安全分发给对应群组主机。
$env:RELAY_CREDENTIALS='{"group-default":"<至少24字符的独立随机密钥>","group-contest":"<另一个独立随机密钥>"}'
$env:RELAY_PORT='8095'
$env:SERVER_ADDRESS='127.0.0.1'
java -jar services/relay-service/target/relay-service-0.1.0-SNAPSHOT.jar
```

配置由部署者维护，客户端不能任意添加代理目标。密钥不要提交 Git、写入公共配置或输出日志。停用群组连接时，在中继配置中移除对应群组并重启服务；密钥轮换需同时更新中继和主机。

## 配置内网群组主机

```powershell
$env:TUNNEL_RELAY_URL='https://你的中继域名'
$env:TUNNEL_GROUP_ID='group-default'
$env:TUNNEL_CREDENTIAL='<与该群组对应的中继连接密钥>'
$env:TUNNEL_SOURCE_URL='http://127.0.0.1:8082'
$env:TUNNEL_PORT='8096'
java -jar services/tunnel-client/target/tunnel-client-0.1.0-SNAPSHOT.jar
```

连接端启动时和每次轮询前检查源服务群组 ID，错误配置不会把另一组主机连接到该群组通道。连接端状态接口仅监听本机：`GET http://127.0.0.1:8096/api/v1/tunnel/status`。中继状态为 `GET /api/v1/relay/group-default/status`，包含 online 和 pending，不返回凭证。

群组服务在内网运行时可设置 `SERVER_ADDRESS=127.0.0.1`，仅允许本机连接端访问。统一身份签名公钥需与公网身份服务一致。

## 公网入口示例

Caddy 的域名 HTTPS 入口（本机运行 public-gateway.mjs，配置 PUBLIC_ACCESS_GATEWAY_KEY 与身份服务一致）：

```caddyfile
community.example.com {
    reverse_proxy 127.0.0.1:8098
}
```

身份服务的顾问连接需改为中继群组地址：设置 `ADVISOR_DEFAULT_GROUP_URL=https://你的中继域名/groups/group-default` 和 `ADVISOR_CONTEST_GROUP_URL=https://你的中继域名/groups/group-contest`。副本服务的 `REPLICA_SOURCE_URL` 同样可配置为对应中继群组地址；继续使用群组节点凭证。

## 已验证与限制

运行 `pwsh -File scripts/test-relay.ps1` 启动独立中继和两个出站连接进程，测试真实群组身份、成员加入、讨论与回复、跨组隔离、节点复制和凭证撤销、主机离线与重新连接。测试只在本机执行，不能作为真实公网部署的证明。

中继队列每组最多 32 个待传递请求，默认请求等待 20 秒；超时返回 504 并删除待处理条目。主机 30 秒没有轮询则显示离线，离线请求返回 503。请求体最多 64 KiB，响应体最多 256 KiB。中继不会自动重试写操作，避免超时后盲目重复发布；如果写请求在源服务成功但响应丢失，需要先刷新确认结果。

该原型的中继队列不持久化，连接端逐个处理请求；服务重启时尚未完成的请求会失败。暂无多连接负载分担、跨中继故障转移或 WebSocket/SSE 透传。已实现的 HTTP 讨论、回复、成员心跳、节点复制可通过中继传输。

真实跨网络验收需要部署者提供可访问的公网服务器、域名和 HTTPS，并从不同网络的两台设备测试。当前已使用本机主机加 Cloudflare 临时隧道接通真实公网 HTTPS 入口，并通过主机经公网边缘执行双用户交流测试；独立网络设备验收尚待手机移动网络执行。


## 管理员控制公网访问

默认公网访问关闭。管理员在本机 http://127.0.0.1:5173 的管理后台启用开关，并生成一次性邀请。填写当前公网 HTTPS 地址后可以复制含片段凭证的邀请链接。凭证位于 # 之后，不随初次 HTTP 请求发送；入口页读取后立即清除地址中的凭证。兑换成功设置 Secure、HttpOnly、SameSite=Strict 的访问 Cookie，原始会话凭证不返回给页面脚本。邀请有效期为 1–168 小时，每个邀请只能兑换一次，会话最长 8 小时且不超过邀请到期时间。邀请和会话在数据库中仅存摘要。

公网网关在每次页面、静态资源和接口请求时验证访问会话，无有效邀请、开关关闭、邀请撤销/过期、验证服务不可用时拒绝放行。关闭开关更新会话版本，随后重新开启不会恢复旧会话。最后一个有效邀请撤销后，入口保持关闭。已经在浏览器展示的内容无法远程收回，新的请求会被拒绝。访问兑换全入口每分钟最多 30 次尝试。

公网主机连接端通过 /api/v1/tunnel/{group}/poll 和 replies 使用独立中继凭证；这些机器接口仅在公网策略有效时转发，并由中继核验对应组凭证。其他 API 不能沿用此例外。远程副本在节点凭证之外需要携带 X-Public-Access 访问会话。start-replica.ps1 支持额外的 -PublicInvite 参数，将一次性公网邀请兑换后仅把会话放入副本进程环境；过期后需重新兑换新邀请。也可通过 REPLICA_PUBLIC_ACCESS_SESSION 配置会话。身份服务内部验证接口不通过公网发布。

本机管理员可在公网关闭时继续管理。start-dev 默认将前后端仅绑定 127.0.0.1。切换私有模式：`pwsh -File scripts/start-private.ps1`，停止公网相关进程并以本机绑定重新启动社区。指定局域网接口时传入 `-BindAddress <本机局域网IP>`；此模式仍不启动公网隧道。当前实现为同一部署的访问与运行模式隔离，独立数据库和正式 PC 安装包尚未交付。
