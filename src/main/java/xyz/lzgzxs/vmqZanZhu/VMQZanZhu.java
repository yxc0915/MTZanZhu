package xyz.lzgzxs.vmqZanZhu;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.*;
import java.util.logging.Level;

public class VMQZanZhu extends JavaPlugin implements TabExecutor {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private Map<String, ProjectConfig> projects = new HashMap<>();
    private Map<String, OrderInfo> orderMap = new HashMap<>();
    private Map<UUID, String> playerPendingOrders = new HashMap<>();
    private final Map<UUID, Long> lastUpdateTime = new HashMap<>(); // 修复：添加 lastUpdateTime 的声明
    private JsonStorage jsonStorage;

    private String vmqDomain;
    private String vmqKey;
    private String notifyUrl; // 回调地址
    private final Gson gson = new Gson();
    private CallbackServer callbackServer;

    // 内部类，用于存储订单信息
    public static class OrderInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        private final UUID playerUUID;
        private final String projectId;

        // 原有的构造函数
        public OrderInfo(Player player, ProjectConfig project) {
            this.playerUUID = player.getUniqueId();
            this.projectId = project.getName();
        }

        // 新增用于反序列化的构造函数
        public OrderInfo(UUID playerUUID, String projectId) {
            this.playerUUID = playerUUID;
            this.projectId = projectId;
        }

        public UUID getPlayerUUID() {
            return playerUUID;
        }

        public String getProjectId() {
            return projectId;
        }
    }


    @Override
    public void onEnable() {
        // 初始化 ConfigManager 并生成默认配置
        ConfigManager configManager = new ConfigManager(this);
        configManager.setupDefaultConfig();

        saveDefaultConfig();
        loadConfig();
        Objects.requireNonNull(getCommand("openvmq")).setExecutor(this);
        Objects.requireNonNull(getCommand("vmqreload")).setExecutor(this);
        startCallbackServer();

        // 初始化 JsonStorage
        jsonStorage = new JsonStorage(getDataFolder());
        loadStoredData();

        getLogger().info("VMQ赞助插件已启动！");
    }

    @Override
    public void onDisable() {
        // 保存数据到 JSON 文件
        jsonStorage.saveData(playerPendingOrders, orderMap);

        if (callbackServer != null) {
            callbackServer.stop();
        }
    }

    private void loadStoredData() {
        Map<String, Object> data = jsonStorage.loadData();
        if (data.containsKey("playerPendingOrders")) {
            playerPendingOrders = (Map<UUID, String>) data.get("playerPendingOrders");
        }
        if (data.containsKey("orderMap")) {
            orderMap = (Map<String, OrderInfo>) data.get("orderMap");
        }
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        projects.clear();

        vmqDomain = config.getString("vmq.domain");
        vmqKey = config.getString("vmq.key");

        if (vmqDomain == null || vmqKey == null) {
            getLogger().severe("配置文件中缺少必要的VMQ配置！");
            return;
        }

        // 加载回调地址配置
        notifyUrl = config.getString("vmq.callback.notifyUrl");
        if (notifyUrl == null || notifyUrl.isEmpty()) {
            // 如果未配置回调地址，则使用默认地址（本机IP + 端口）
            try {
                String ip = InetAddress.getLocalHost().getHostAddress();
                int port = config.getInt("vmq.callback.port", 8080);
                notifyUrl = "http://" + ip + ":" + port + config.getString("vmq.callback.path", "/vmq-callback");
            } catch (UnknownHostException e) {
                getLogger().severe("无法获取本机IP地址: " + e.getMessage());
                notifyUrl = ""; // 设置为空，创建订单时将不会包含回调地址
            }
        }

        // 加载赞助项目配置
        for (String key : Objects.requireNonNull(config.getConfigurationSection("projects")).getKeys(false)) {
            String name = config.getString("projects." + key + ".name");
            String lore = config.getString("projects." + key + ".lore");
            double amount = config.getDouble("projects." + key + ".amount");
            List<String> rewards = config.getStringList("projects." + key + ".rewards");

            projects.put(key, new ProjectConfig(name, lore, amount, rewards));
        }
    }

    private void startCallbackServer() {
        int port = getConfig().getInt("vmq.callback.port", 8080);
        String path = getConfig().getString("vmq.callback.path", "/vmq-callback");

        try {
            callbackServer = new CallbackServer(this, port, path, vmqKey);
            callbackServer.start();
        } catch (IOException e) {
            getLogger().severe("启动回调服务器失败: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (sender instanceof Player) {
            Player player = (Player) sender;
            UUID uuid = player.getUniqueId();
            if (lastUpdateTime.containsKey(uuid)) {
                if (lastUpdateTime.get(uuid) + 2000 > System.currentTimeMillis()) {
                    player.sendMessage("操作频繁！");
                    return true;
                }
                lastUpdateTime.put(uuid, System.currentTimeMillis());
            } else {
                lastUpdateTime.put(uuid, System.currentTimeMillis());
            }
        }

        if (command.getName().equalsIgnoreCase("vmqreload")) {
            if (!sender.hasPermission("vmqzanzhu.admin")) {
                sender.sendMessage(Component.text("你没有权限执行此命令！").color(TextColor.color(0xFF5555)));
                return true;
            }
            loadConfig();
            sender.sendMessage(Component.text("配置重载成功！").color(TextColor.color(0x55FF55)));
            return true;
        }

        if (command.getName().equalsIgnoreCase("openvmq")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("该命令只能由玩家执行！").color(TextColor.color(0xFF5555)));
                return true;
            }

            if (args.length != 1 && args.length != 2) {
                sender.sendMessage(Component.text("用法: /openvmq <项目名> [支付方式]").color(TextColor.color(0xFF5555)));
                sender.sendMessage(Component.text("支付方式: wx (微信) 或 zfb (支付宝)，默认为支付宝").color(TextColor.color(0xFF5555)));
                return true;
            }

            String projectId = args[0];
            ProjectConfig project = projects.get(projectId);

            if (project == null) {
                player.sendMessage(Component.text("未找到该赞助项目！").color(TextColor.color(0xFF5555)));
                return true;
            }

            int payType = 2; // 默认为支付宝
            if (args.length == 2) {
                if (args[1].equalsIgnoreCase("wx")) {
                    payType = 1; // 微信支付
                } else if (!args[1].equalsIgnoreCase("zfb")) {
                    player.sendMessage(Component.text("无效的支付方式，使用默认的支付宝支付").color(TextColor.color(0xFFAA00)));
                }
            }

            createOrder(player, project, payType);
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("openvmq")) {
            if (args.length == 1) {
                List<String> completions = new ArrayList<>(projects.keySet());
                Collections.sort(completions);
                return completions;
            } else if (args.length == 2) {
                return Arrays.asList("wx", "zfb");
            }
        }
        return Collections.emptyList();
    }

    private void createOrder(Player player, ProjectConfig project, int payType) {
        UUID playerUUID = player.getUniqueId();

        // 如果玩家有未支付的相同金额订单，先关闭它
        if (playerPendingOrders.containsKey(playerUUID)) {
            closeExistingOrder(player);
            player.getScheduler().runDelayed(this,scheduledTask -> create( player, project,  payType),null,20);
        }else{
            create( player, project,  payType);
        }
    }

    private void create(Player player, ProjectConfig project, int payType) {
        UUID playerUUID = player.getUniqueId();
        String payId = System.currentTimeMillis() + "";
        String param = player.getName();
        String price = String.format("%.2f", project.getAmount());

        // 存储订单信息
        orderMap.put(payId, new OrderInfo(player, project));
        jsonStorage.saveData(playerPendingOrders, orderMap);

        // 计算签名
        String sign = md5(payId + param + payType + price + vmqKey);

        // 构建请求URL
        String url = String.format("%s/createOrder?payId=%s&param=%s&type=%d&price=%s&sign=%s&notifyUrl=%s",
                vmqDomain, payId, param, payType, price, sign, notifyUrl);

        String payMethod = payType == 1 ? "微信" : "支付宝";
        Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);

                if (jsonResponse.get("code").getAsInt() == 1) {
                    JsonObject data = jsonResponse.getAsJsonObject("data");
                    String orderID = data.get("orderId").getAsString();
                    playerPendingOrders.put(playerUUID, orderID);
                    jsonStorage.saveData(playerPendingOrders, orderMap);
                    String payUrl = data.get("payUrl").getAsString();
                    double reallyPrice = data.get("reallyPrice").getAsDouble();

                    player.sendMessage(Component.text("订单创建成功！").color(TextColor.color(0x55FF55)));
                    player.sendMessage(Component.text("实际需支付金额: " + reallyPrice + " 元").color(TextColor.color(0xFFAA00)));
                    player.sendMessage(Component.text("请使用" + payMethod + "扫描以下二维码进行支付：").color(TextColor.color(0x55FF55)));
                    player.sendMessage(Component.text("https://qr.lzgzxs.xyz/qrcode.php?link=" + payUrl)
                            .color(TextColor.color(0x5555FF))
                            .decorate(TextDecoration.UNDERLINED)
                            .clickEvent(ClickEvent.openUrl("https://qr.lzgzxs.xyz/qrcode.php?link=" + payUrl)));
                } else {
                    player.sendMessage(Component.text("创建订单失败：" + jsonResponse.get("msg").getAsString()).color(TextColor.color(0xFF5555)));
                    playerPendingOrders.remove(playerUUID);
                    jsonStorage.saveData(playerPendingOrders, orderMap);
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "创建订单时发生错误", e);
                player.sendMessage(Component.text("创建订单时发生错误，请稍后再试。").color(TextColor.color(0xFF5555)));
                playerPendingOrders.remove(playerUUID);
                jsonStorage.saveData(playerPendingOrders, orderMap);
            }
        });
    }

    private void closeExistingOrder(Player player) {
        UUID playerUUID = player.getUniqueId();
        String orderId = playerPendingOrders.get(playerUUID);
        String url = String.format("%s/closeOrder?orderId=%s&sign=%s", vmqDomain,orderId , md5(playerPendingOrders.get(playerUUID)+vmqKey));

        Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                // 直接打印响应内容以调试
                getLogger().info("关闭订单响应: " + response.body());

                // 尝试解析响应
                JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);

                if (jsonResponse != null && jsonResponse.has("code")) {
                    int code = jsonResponse.get("code").getAsInt();
                    if (code == 1) {
                        getLogger().info("成功关闭订单: " + orderId);
                    } else {
                        String msg = jsonResponse.has("msg") ? jsonResponse.get("msg").getAsString() : "未知错误";
                        getLogger().warning("关闭订单失败: " + orderId + ", 原因: " + msg);
                    }
                } else {
                    getLogger().warning("关闭订单响应格式无效: " + response.body());
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "关闭订单时发生错误", e);
            }
        });
    }

    // 提供一个方法，供 CallbackServer 通过订单 ID 获取订单信息
    public OrderInfo getOrderInfo(String payId) {
        return orderMap.get(payId);
    }

    // 修改 removeOrderInfo 方法
    public void removeOrderInfo(String payId) {
        OrderInfo orderInfo = orderMap.remove(payId);
        if (orderInfo != null) {
            playerPendingOrders.remove(orderInfo.getPlayerUUID());
        }
        jsonStorage.saveData(playerPendingOrders, orderMap);
        getLogger().info("已清理订单 ID: " + payId);
    }

    // 将 md5 方法改为 public static
    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());
            BigInteger no = new BigInteger(1, messageDigest);
            String hashtext = no.toString(16);
            while (hashtext.length() < 32) {
                hashtext = "0" + hashtext;
            }
            return hashtext;
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    // 将 giveRewards 方法改为 public
    public void giveRewards(Player player, ProjectConfig project) {
        for (String command : project.getRewards()) {
            String finalCommand = command.replace("%player%", player.getName());
            Bukkit.getGlobalRegionScheduler().run(this, scheduledTask -> getServer().dispatchCommand(getServer().getConsoleSender(), finalCommand));
        }
        player.sendMessage(Component.text("奖励已发放，感谢您的赞助！").color(TextColor.color(0x55FF55)));

        // 清理玩家的未支付订单记录
        playerPendingOrders.remove(player.getUniqueId());
        jsonStorage.saveData(playerPendingOrders, orderMap);
    }

    // 新增方法：通过项目 ID 获取项目配置
    public ProjectConfig getProjectById(String projectId) {
        return projects.get(projectId);
    }
}
