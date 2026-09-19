# ChatIM 移动端产品原型

当前已完成账号流程和登录后的第一版核心聊天体验，并按选定的第三套视觉方案实现统一的移动端页面。

## 当前可体验功能

- 密码登录、验证码登录、注册、会话恢复和退出登录；
- 会话列表、未读数、置顶/免打扰/草稿/失败等列表状态；
- 会话搜索、聊天详情、文本发送与发送状态；
- 联系人列表，以及从联系人发起新会话；
- 发现页和个人中心的第一版信息架构；
- 按账号保存草稿、最近会话和消息，刷新后可以恢复；
- iPhone 和 Pixel 10 两种设备预览，以及键盘顶起和安全区适配。

## 本地运行

```powershell
npm install
npm run dev -- --host 0.0.0.0 --port 4173
```

打开 `http://localhost:4173/` 使用真实接口。前端默认连接 `http://localhost:10010`，也可以复制 `.env.example` 为 `.env.local` 后修改 `VITE_API_BASE_URL`。

Gateway 开发环境需要允许此页面的来源：

```powershell
$env:FRONTEND_ORIGIN="http://localhost:4173"
```

打开 `http://localhost:4173/?demo=1` 可直接体验完整流程。演示邮箱可使用任意格式正确的地址，密码和验证码都是 `123456`。

## 已接入接口

- `GET /api/user/sendCaptcha`
- `POST /api/user/login/password`
- `POST /api/user/login/code`
- `POST /api/user/register`
- `POST /api/user/refresh`
- `GET /api/user/logout`

当前浏览器版本用 `localStorage` 模拟移动端安全存储。正式客户端接入时应将访问令牌和刷新令牌迁移到系统安全存储。

## 当前接口边界

后端目前还没有公开的会话摘要接口和完整的 WebSocket 发送确认协议。真实模式不会填充演示联系人或会话；登录后会明确显示等待会话接口。`?demo=1` 中的聊天发送会模拟从 `sending` 到 `sent`，用于验证界面与交互，不代表服务端已经完成消息持久化确认。
