package xyz.lzgzxs.vmqZanZhu;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

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

        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(path, new CallbackHandler());
        server.setExecutor(Executors.newCachedThreadPool());
    }

    public void start() {
        server.start();
        logger.info("赞助回调服务已启动在端口 " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        logger.info("回调服务已停止");
    }

    private class CallbackHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1);
                    return;
                }

                Map<String, String> params = queryToMap(exchange.getRequestURI().getQuery());
                String payId = params.get("payId");
                String param = params.get("param");
                String type = params.get("type");
                String price = params.get("price");
                String reallyPrice = params.get("reallyPrice");
                String sign = params.get("sign");

                if (payId == null || param == null || type == null || price == null || reallyPrice == null || sign == null) {
                    sendResponse(exchange, 400, "missing_parameters");
                    return;
                }

                String calculatedSign = VMQZanZhu.md5(payId + param + type + price + reallyPrice + vmqKey);
                if (!calculatedSign.equals(sign)) {
                    sendResponse(exchange, 400, "error_sign");
                    return;
                }

                processOrder(payId, param, type, price, reallyPrice);
                sendResponse(exchange, 200, "success");
            } catch (Exception e) {
                logger.severe("处理回调请求时发生错误: " + e.getMessage());
                sendResponse(exchange, 500, "internal_error");
            } finally {
                exchange.close();
            }
        }

        private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
            exchange.sendResponseHeaders(statusCode, response.getBytes().length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    private void processOrder(String payId, String param, String type, String price, String reallyPrice) {
        VMQZanZhu.OrderInfo orderInfo = plugin.getOrderInfo(payId);
        if (orderInfo == null) {
            logger.warning("未找到与订单 ID " + payId + " 对应的订单信息");
            return;
        }

        OfflinePlayer offlinePlayer = orderInfo.getPlayer();
        VMQZanZhu.ProjectConfig project = orderInfo.getProject();

        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> {
            plugin.giveRewards(offlinePlayer, project);
            logger.info("玩家 " + param + " 的奖励处理完成");
            plugin.removeOrderInfo(payId);
        });
    }


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
