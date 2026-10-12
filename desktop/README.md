# 算法同行 PC Demo 0.1.0

Windows 10/11 64 位便携版。解压整个压缩包后，双击 AlgoTogetherDemo.exe；不要单独复制 EXE。无需另外安装 Java、Node、PowerShell 或 MySQL。第一版是原生桌面启动面板，社区业务界面在默认浏览器中显示。

1. 点击“本机启动”，填写自己的管理员邮箱，等待数据库和服务启动。
2. 在打开的社区中注册该邮箱，密码自行设置。这个账号具有管理员权限；其他邮箱正常注册为普通用户。
3. 用户申请创建公开或私密群组，管理员审批后由申请用户执行本地部署。群组讨论、学习任务、LaTeX、顾问等功能沿用网页版本。
4. “局域网启动”会显示可分享的主机地址。同一 Wi-Fi / 网段访问该地址；必要时在 Windows 私有网络防火墙允许 5173 端口。
5. “公网启动”建立临时 HTTPS 隧道。还需本机管理员在管理后台开启公网访问。地址在启动日志中显示，重启可能变化。需要联网且电脑保持运行。
6. 使用“停止服务”或关闭启动器，会停止本 Demo 的服务和数据库，保留数据。异常断电后可重启；不要手动删除数据库。

数据保存在 `%LOCALAPPDATA%\AlgoTogetherDemo\v1`，与源码项目原有用户、数据库、密钥完全独立。管理员邮箱保存在该目录 desktop.properties 中。备份前先停止服务，再备份整个目录。API 密钥不随软件提供，需在个人设置中自行配置。内置 MySQL 为独立 3317 端口实例，仅监听本机；消息中心沿用数据库站内通知，本包未启用 RabbitMQ 外部队列。

端口：社区 5173；身份 8081；默认群组 8082/8083；创建群组 8100–8199；公网组件 8095–8098、19090。不要与源码开发版同时启动；端口被占用时会提示，不会停止其他项目。首次启动初始化数据库可能需要数分钟。

本包未签名，Windows 可能提示未知发布者。这是原型演示版，尚未做生产发布加固，也未测试所有 Windows 版本。公网隧道依赖 Cloudflare 服务可用性。

第三方组件：Eclipse Temurin 21 (GPLv2 + Classpath Exception，许可证见 runtime/legal)、MySQL Community 8.0 (GPLv2，见 app/tools/mysql/LICENSE)、PowerShell 7 (MIT，许可随工具目录)、Node.js (MIT 与附带第三方许可，见 desktop/THIRD-PARTY.md)、cloudflared (Apache 2.0)。应用源码：https://github.com/o1wuzun/- 。构建脚本 desktop/build.ps1 不包含用户数据或私钥。
