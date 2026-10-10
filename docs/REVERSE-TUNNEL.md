# 出站中继部署与验收

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

Caddy 的域名 HTTPS 入口（替换域名和前端发布目录）：

```caddyfile
community.example.com {
    handle_path /identity/* {
        reverse_proxy 127.0.0.1:8081
    }
    handle /groups/* {
        reverse_proxy 127.0.0.1:8095
    }
    handle /api/v1/tunnel/* {
        reverse_proxy 127.0.0.1:8095
    }
    handle /api/v1/relay/* {
        reverse_proxy 127.0.0.1:8095
    }
    handle {
        root * /srv/community/frontend/dist
        try_files {path} /index.html
        file_server
    }
}
```

身份服务的顾问连接需改为中继群组地址：设置 `ADVISOR_DEFAULT_GROUP_URL=https://你的中继域名/groups/group-default` 和 `ADVISOR_CONTEST_GROUP_URL=https://你的中继域名/groups/group-contest`。副本服务的 `REPLICA_SOURCE_URL` 同样可配置为对应中继群组地址；继续使用群组节点凭证。

## 已验证与限制

运行 `pwsh -File scripts/test-relay.ps1` 启动独立中继和两个出站连接进程，测试真实群组身份、成员加入、讨论与回复、跨组隔离、节点复制和凭证撤销、主机离线与重新连接。测试只在本机执行，不能作为真实公网部署的证明。

中继队列每组最多 32 个待传递请求，默认请求等待 20 秒；超时返回 504 并删除待处理条目。主机 30 秒没有轮询则显示离线，离线请求返回 503。请求体最多 64 KiB，响应体最多 256 KiB。中继不会自动重试写操作，避免超时后盲目重复发布；如果写请求在源服务成功但响应丢失，需要先刷新确认结果。

该原型的中继队列不持久化，连接端逐个处理请求；服务重启时尚未完成的请求会失败。暂无多连接负载分担、跨中继故障转移或 WebSocket/SSE 透传。已实现的 HTTP 讨论、回复、成员心跳、节点复制可通过中继传输。

真实跨网络验收需要部署者提供可访问的公网服务器、域名和 HTTPS，并从不同网络的两台设备测试。当前会话没有这些部署资源，尚未执行该项验收。
