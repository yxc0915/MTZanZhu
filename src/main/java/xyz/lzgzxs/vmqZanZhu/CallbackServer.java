package xyz.lzgzxs.vmqZanZhu;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class CallbackServer {
    private final VMQZanZhu plugin;
    private final HttpServer server;
    private final String vmqKey;
    private final Logger logger;

    public CallbackServer(VMQZanZhu plugin, int port, String path, String vmqKey) throws IOException {
        this.plugin = plugin;
        this.vmqKey = vmqKey;
        this.logger = plugin.getLogger();

        // 创建 HTTP 服务器并绑定到指定端口
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(path, new CallbackHandler());
        server.setExecutor(Executors.newCachedThreadPool()); // 使用线程池处理请求
    }

    /**
     * 启动回调服务器
     */
    public void start() {
        server.start();
        logger.info("赞助回调服务已启动在端口 " + server.getAddress().getPort());
    }

    /**
     * 停止回调服务器
     */
    public void stop() {
        server.stop(0);
        logger.info("回调服务已停止");
    }

    /**
     * 处理回调请求的处理器
     */
    private class CallbackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1); // 方法不允许
                    return;
                }

                // 解析请求参数
                Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
                String payId = params.get("payId");
                String param = params.get("param");
                String type = params.get("type");
                String price = params.get("price");
                String reallyPrice = params.get("reallyPrice");
                String sign = params.get("sign");

                // 验证参数是否完整
                if (payId == null || param == null || type == null || price == null || reallyPrice == null || sign == null) {
                    sendResponse(exchange, 400, "missing_parameters");
                    return;
                }

                // 验证签名
                String calculatedSign = VMQZanZhu.md5(payId + param + type + price + reallyPrice + vmqKey);
                if (!calculatedSign.equals(sign)) {
                    sendResponse(exchange, 400, "error_sign");
                    return;
                }

                // 处理订单
                processOrder(payId, param, type, price, reallyPrice);
                sendResponse(exchange, 200, "success");
            } catch (Exception e) {
                logger.severe("处理回调请求时发生错误: " + e.getMessage());
                sendResponse(exchange, 500, "internal_error");
            } finally {
                exchange.close();
            }
        }

        /**
         * 发送 HTTP 响应
         *
         * @param exchange   HTTP 交换对象
         * @param statusCode 状态码
         * @param response   响应内容
         * @throws IOException 如果发送响应失败
         */
        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.sendResponseHeaders(statusCode, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    /**
     * 处理订单逻辑
     *
     * @param payId       订单 ID
     * @param param       玩家名称
     * @param type        支付类型
     * @param price       订单金额
     * @param reallyPrice 实际支付金额
     */
    private void processOrder(String payId, String param, String type, String price, String reallyPrice) {
        // 通过订单 ID 获取订单信息
        VMQZanZhu.OrderInfo orderInfo = plugin.getOrderInfo(payId);
        if (orderInfo == null) {
            logger.warning("未找到与订单 ID " + payId + " 对应的订单信息");
            return;
        }

        // 通过玩家名称查找玩家（确保玩家在线）
        Player player = Bukkit.getPlayer(param);
        if (player == null || !player.isOnline()) {
            logger.warning("玩家 " + param + " 不在线，无法发放奖励");
            return;
        }

        // 通过 projectId 获取项目配置
        String projectId = orderInfo.getProjectId();
        ProjectConfig project = plugin.getProjectById(projectId); // 使用主类的方法获取项目配置

        if (project == null) {
            logger.warning("未找到与项目 ID " + projectId + " 对应的赞助项目");
            return;
        }

        // 异步发放奖励
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> {
            plugin.giveRewards(player, project);
            logger.info("玩家 " + param + " 的奖励已发放");

            // 关键修复：发放奖励后，从订单 Map 中移除该订单
            plugin.removeOrderInfo(payId);
        });
    }

    /**
     * 将查询字符串解析为 Map
     *
     * @param query 查询字符串
     * @return 参数键值对
     */
    private Map<String, String> queryToMap(String query) {
        Map<String, String> result = new HashMap<>();
        if (query != null) {
            for (String param : query.split("&")) {
                String[] entry = param.split("=");
                if (entry.length > 1) {
                    result.put(entry[0], entry[1]);
                } else {
                    result.put(entry[0], "");
                }
            }
        }
        return result;
    }
}