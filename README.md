# VMQZanZhu
一款基于VMQ系统的我的世界赞助插件


# 工作流程

### 1. **插件启动**
- 插件启动时，会加载配置文件（`config.yml`），读取以下关键配置：
  - `vmq.domain`：VMQ 的 API 地址。
  - `vmq.key`：VMQ 的密钥，用于签名验证。
  - `vmq.callback.port`：回调服务器的端口（默认 8080）。
  - `vmq.callback.path`：回调服务器的路径（默认 `/vmq-callback`）。
  - `vmq.callback.notifyUrl`：回调地址（可选，如果未配置则使用默认地址）。
- 如果未配置 `notifyUrl`，插件会尝试获取本机 IP 地址，并结合端口和路径生成默认回调地址（如 `http://192.168.1.100:8080/vmq-callback`）。
- 启动回调服务器，监听指定端口和路径，等待 VMQ 的回调请求。

---

### 2. **玩家创建订单**
- 玩家使用 `/openvmq <项目名> [支付方式]` 命令创建订单。
  - `<项目名>`：赞助项目的唯一标识（如 `vip1`）。
  - `[支付方式]`：可选参数，支持 `wx`（微信）或 `zfb`（支付宝），默认为支付宝。
- 插件会根据项目名查找对应的赞助项目配置（包括项目名称、描述、金额和奖励）。
- 插件生成一个唯一的订单 ID（`payId`），并将订单信息（包括玩家、赞助项目和订单 ID）存储在一个 `Map` 中。
- 插件向 VMQ 发送创建订单的请求，请求中包含以下参数：
  - `payId`：订单 ID。
  - `param`：玩家名称。
  - `type`：支付类型（1 为微信，2 为支付宝）。
  - `price`：订单金额。
  - `sign`：签名（通过 `payId + param + type + price + vmqKey` 计算 MD5）。
  - `notifyUrl`：回调地址。
- 如果请求成功，VMQ 会返回一个支付链接（`payUrl`），插件将该链接发送给玩家，玩家可以通过微信或支付宝扫描二维码完成支付。

---

### 3. **VMQ 回调通知**
- 当玩家完成支付后，VMQ 会向插件配置的回调地址（`notifyUrl`）发送一个 `GET` 请求，通知插件订单已完成支付。
- 回调请求中包含以下参数：
  - `payId`：订单 ID。
  - `param`：玩家名称。
  - `type`：支付类型。
  - `price`：订单金额。
  - `reallyPrice`：实际支付金额。
  - `sign`：签名（通过 `payId + param + type + price + reallyPrice + vmqKey` 计算 MD5）。
- 插件接收到回调请求后，会进行以下操作：
  1. **验证签名**：
     - 使用相同的算法计算签名，并与回调请求中的签名进行比对，确保请求的合法性。
  2. **查找订单信息**：
     - 通过订单 ID（`payId`）从 `Map` 中查找对应的订单信息（包括玩家和赞助项目）。
  3. **发放奖励**：
     - 如果找到订单信息且玩家在线，插件会异步执行赞助项目配置的奖励命令（如给予玩家 VIP 权限、游戏币等）。
  4. **返回响应**：
     - 如果处理成功，插件会返回 `success`；如果签名验证失败或订单信息不存在，则返回 `error_sign` 或 `missing_parameters`。

---

### 4. **奖励发放**
- 插件根据赞助项目配置的奖励命令，逐个执行以下操作：
  - 将命令中的占位符（如 `%player%`）替换为玩家的名称。
  - 通过服务器控制台执行命令（如 `give %player% diamond 64`）。
- 奖励发放完成后，插件会向玩家发送一条消息，提示奖励已发放。

---

### 5. **插件重载**
- 管理员可以使用 `/vmqreload` 命令重载插件配置。
- 插件会重新加载 `config.yml` 文件，更新赞助项目配置和回调服务器设置。

---

### 6. **插件关闭**
- 当插件关闭时，回调服务器会停止运行，释放占用的端口资源。

---

### 流程图

```plaintext
+-------------------+       +-------------------+       +-------------------+
| 玩家创建订单        |       | VMQ 处理支付       |       | 插件处理回调        |
| - 输入命令         | ----> | - 生成支付链接     | ----> | - 验证签名         |
| - 生成订单 ID      |       | - 等待玩家支付     |       | - 查找订单信息     |
| - 发送创建订单请求 |       | - 支付完成后回调   |       | - 发放奖励         |
+-------------------+       +-------------------+       +-------------------+
```

---

### 示例场景

1. **玩家创建订单**：
   - 玩家 A 输入命令 `/openvmq vip1 zfb`。
   - 插件生成订单 ID `123456789`，并向 VMQ 发送创建订单请求。
   - VMQ 返回支付链接，玩家 A 使用支付宝扫描二维码完成支付。

2. **VMQ 回调通知**：
   - 玩家 A 支付完成后，VMQ 向插件的回调地址发送请求：
     ```
     GET /vmq-callback?payId=123456789&param=玩家A&type=2&price=100.00&reallyPrice=100.00&sign=xxxxxx
     ```
   - 插件验证签名并通过订单 ID 找到玩家 A 和赞助项目 `vip1`。
   - 插件执行奖励命令（如 `give 玩家A diamond 64`），并向玩家 A 发送奖励已发放的消息。

3. **奖励发放**：
   - 玩家 A 收到 64 个钻石，并看到提示消息：“奖励已发放，感谢您的赞助！”
