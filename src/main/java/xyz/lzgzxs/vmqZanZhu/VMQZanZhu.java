package xyz.lzgzxs.vmqZanZhu;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class VMQZanZhu extends JavaPlugin implements TabExecutor {
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Map<String, ProjectConfig> projects = new HashMap<>();
    private final Map<String, OrderInfo> orderMap = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUpdateTime = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerPendingOrders = new ConcurrentHashMap<>();
    private final Map<String, Double> orderPrice = new ConcurrentHashMap<>();
    private final Map<UUID, List<String>> pendingRewards = new ConcurrentHashMap<>();

    private String vmqDomain;
    private String vmqKey;
    private String notifyUrl;
    private final Gson gson = new Gson();
    private CallbackServer callbackServer;
    private final Object fileLock = new Object();

    private class PlayerJoinListener implements Listener {
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            UUID playerUUID = player.getUniqueId();

            if (pendingRewards.containsKey(playerUUID)) {
                List<String> commands = new ArrayList<>(pendingRewards.get(playerUUID));
                final String[] orderIdHolder = new String[1];

                // 使用 Folia 的全局调度器
                Bukkit.getServer().getGlobalRegionScheduler().execute(VMQZanZhu.this, () -> {
                    for (String cmd : commands) {
                        if (cmd.startsWith("__ORDER_ID__:")) {
                            orderIdHolder[0] = cmd.substring("__ORDER_ID__:".length());
                            continue;
                        }
                        String finalCmd = cmd.replace("%player%", player.getName());
                        getServer().dispatchCommand(getServer().getConsoleSender(), finalCmd);
                    }

                    // 清理数据
                    pendingRewards.remove(playerUUID);
                    savePendingRewards(); // 更新存储文件

                    // 清理订单记录
                    if (orderIdHolder[0] != null) {
                        orderMap.remove(orderIdHolder[0]);
                        playerPendingOrders.remove(playerUUID);
                        orderPrice.remove(orderIdHolder[0]);

                        // 清理 players.yml 中的缓存
                        cleanupPlayersYml(playerUUID);
                    }

                    player.sendMessage(Component.text("检测到您有未领取的赞助奖励，已自动补发！"));
                });
            }
        }
    }

    private void cleanupPlayersYml(UUID playerUUID) {
        File file = new File(getDataFolder(), "players.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        if (config.contains(playerUUID.toString())) {
            config.set(playerUUID.toString(), null);
            try {
                config.save(file);
                getLogger().info("已清理 players.yml 中玩家 " + playerUUID + " 的缓存");
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "清理 players.yml 中玩家 " + playerUUID + " 的缓存失败", e);
            }
        }
    }




    // 内部类，用于存储订单信息
    public static class OrderInfo {
        private final OfflinePlayer player;
        private final ProjectConfig project;

        public OrderInfo(OfflinePlayer player, ProjectConfig project) {
            this.player = player;
            this.project = project;
        }

        public OfflinePlayer getPlayer() {
            return player;
        }

        public ProjectConfig getProject() {
            return project;
        }
    }


    @Override
    public void onEnable() {
        ConfigManager configManager = new ConfigManager(this);
        configManager.setupDefaultConfig();

        saveDefaultConfig();
        loadConfig();
        Objects.requireNonNull(getCommand("openvmq")).setExecutor(this);
        Objects.requireNonNull(getCommand("vmqreload")).setExecutor(this);
        startCallbackServer();
        File historyFile = new File(getDataFolder(), "history.yml");
        if (!historyFile.exists()) {
            saveResource("history.yml", false);
        }
        getLogger().info("VMQ赞助插件已启动！");
        loadPlayerData();
        loadPendingRewards();

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);
    }

    private void loadPendingRewards() {
        File file = new File(getDataFolder(), "pending-rewards.yml");
        if (!file.exists()) return;

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection pendingSection = config.getConfigurationSection("pending");
        if (pendingSection == null) return;

        for (String uuidStr : pendingSection.getKeys(false)) {
            UUID uuid = UUID.fromString(uuidStr);
            List<Map<?, ?>> rewardData = config.getMapList("pending." + uuidStr + ".rewards");
            String orderId = config.getString("pending." + uuidStr + ".orderId");

            List<String> commands = new ArrayList<>();
            for (Map<?, ?> entry : rewardData) {
                String command = (String) entry.get("command");
                commands.add(command);
            }

            if (orderId != null) {
                commands.add("__ORDER_ID__:" + orderId);
                orderPrice.put(orderId, config.getDouble("pending." + uuidStr + ".price", 0.0));
            }

            pendingRewards.put(uuid, commands);
        }
        getLogger().info("已加载 " + pendingRewards.size() + " 个待处理的离线奖励");
    }

    @Override
    public void onDisable() {
        if (callbackServer != null) {
            callbackServer.stop();
        }
        savePendingRewards();
    }

    private void savePendingRewards() {
        synchronized(fileLock) {
            File file = new File(getDataFolder(), "pending-rewards.yml");
            YamlConfiguration config = new YamlConfiguration();

            for (Map.Entry<UUID, List<String>> entry : pendingRewards.entrySet()) {
                UUID uuid = entry.getKey();
                List<Map<String, Object>> rewardData = new ArrayList<>();
                String orderId = null;

                for (String cmd : entry.getValue()) {
                    if (cmd.startsWith("__ORDER_ID__:")) {
                        orderId = cmd.substring("__ORDER_ID__:".length());
                        continue;
                    }
                    Map<String, Object> cmdData = new HashMap<>();
                    cmdData.put("command", cmd);
                    rewardData.add(cmdData);
                }

                if (orderId != null) {
                    config.set("pending." + uuid.toString() + ".orderId", orderId);
                }
                config.set("pending." + uuid.toString() + ".rewards", rewardData);
            }

            try {
                config.save(file);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "保存 pending-rewards.yml 失败", e);
            }
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

        notifyUrl = config.getString("vmq.callback.notifyUrl");
        if (notifyUrl == null || notifyUrl.isEmpty()) {
            try {
                String ip = InetAddress.getLocalHost().getHostAddress();
                int port = config.getInt("vmq.callback.port", 8080);
                notifyUrl = "http://" + ip + ":" + port + config.getString("vmq.callback.path", "/vmq-callback");
            } catch (UnknownHostException e) {
                getLogger().severe("无法获取本机IP地址: " + e.getMessage());
                notifyUrl = "";
            }
        }

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

            int payType = 2;
            if (args.length == 2) {
                if (args[1].equalsIgnoreCase("wx")) {
                    payType = 1;
                } else if (!args[1].equalsIgnoreCase("zfb")) {
                    player.sendMessage(Component.text("无效的支付方式，使用默认的支付宝支付").color(TextColor.color(0xFFAA00)));
                }
            }

            createOrder(player, projectId, project, payType);
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

    private void createOrder(Player player, String projectId, ProjectConfig project, int payType) {
        UUID playerUUID = player.getUniqueId();

        if (playerPendingOrders.containsKey(playerUUID)) {
            closeExistingOrder(player);
            // 修复：传递 projectId 参数
            player.getScheduler().runDelayed(this, scheduledTask -> create(player, projectId, project, payType), null, 20);
        } else {
            // 修复：传递 projectId 参数
            create(player, projectId, project, payType);
        }
    }

    private void create(Player player, String projectId, ProjectConfig project, int payType) {
        UUID playerUUID = player.getUniqueId();
        String payId = System.currentTimeMillis() + "";
        String param = player.getName();
        String price = String.format("%.2f", project.getAmount());

        SimpleDateFormat sdf = new SimpleDateFormat("yy/MM/dd HH:mm:ss");
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, 5);
        String expireTime = sdf.format(calendar.getTime());

        orderMap.put(payId, new OrderInfo(player, project));

        String sign = md5(payId + param + payType + price + vmqKey);

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
                    if (data != null && !data.isJsonNull()) {
                        String orderID = data.get("orderId").getAsString();
                        playerPendingOrders.put(playerUUID, orderID);
                        String payUrl = data.get("payUrl").getAsString();
                        double reallyPrice = data.get("reallyPrice").getAsDouble();
                        orderPrice.put(orderID, reallyPrice);

                        // 保存订单信息到 players.yml
                        FileConfiguration playersConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "players.yml"));
                        playersConfig.set(player.getUniqueId().toString() + ".orderId", orderID);
                        playersConfig.set(player.getUniqueId().toString() + ".project", projectId); // 保存项目 ID
                        playersConfig.set(player.getUniqueId().toString() + ".payId", payId);
                        playersConfig.save(new File(getDataFolder(), "players.yml"));

                        player.sendMessage(Component.text("订单创建成功！").color(TextColor.color(0x55FF55)));
                        player.sendMessage(Component.text("实际需支付金额: " + reallyPrice + " 元").color(TextColor.color(0xFFAA00)));
                        player.sendMessage(Component.text("请使用" + payMethod + "扫描以下二维码进行支付：").color(TextColor.color(0x55FF55)));
                        player.sendMessage(Component.text("【点击这里打开支付页面】")
                                .color(TextColor.color(0x5555FF))
                                .decorate(TextDecoration.UNDERLINED)
                                .clickEvent(ClickEvent.openUrl("https://qr.lzgzxs.xyz/qrcode.php?link=" + payUrl +
                                        "&time=" + URLEncoder.encode(expireTime, StandardCharsets.UTF_8) +
                                        "&account=" + reallyPrice)));
                    } else {
                        player.sendMessage(Component.text("创建订单成功，但返回数据为空，请稍后再试。").color(TextColor.color(0xFF5555)));
                        playerPendingOrders.remove(playerUUID);
                    }
                } else {
                    player.sendMessage(Component.text("创建订单失败：" + jsonResponse.get("msg").getAsString()).color(TextColor.color(0xFF5555)));
                    playerPendingOrders.remove(playerUUID);
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "创建订单时发生错误", e);
                player.sendMessage(Component.text("创建订单时发生错误，请稍后再试。").color(TextColor.color(0xFF5555)));
                playerPendingOrders.remove(playerUUID);
            }
        });
    }

    private void closeExistingOrder(Player player) {
        UUID playerUUID = player.getUniqueId();
        String orderId = playerPendingOrders.get(playerUUID);
        String url = String.format("%s/closeOrder?orderId=%s&sign=%s", vmqDomain, orderId, md5(playerPendingOrders.get(playerUUID) + vmqKey));

        Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                getLogger().info("关闭订单响应: " + response.body());

                JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);

                if (jsonResponse != null && jsonResponse.has("code")) {
                    int code = jsonResponse.get("code").getAsInt();
                    if (code == 1) {
                        getLogger().info("成功关闭订单: " + orderId);
                        FileConfiguration playersConfig = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "players.yml"));
                        playersConfig.set(player.getUniqueId().toString(), null);
                        playersConfig.save(new File(getDataFolder(), "players.yml"));
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

    public OrderInfo getOrderInfo(String payId) {
        return orderMap.get(payId);
    }

    public void removeOrderInfo(String payId) {
        OrderInfo orderInfo = orderMap.remove(payId);
        if (orderInfo != null) {
            playerPendingOrders.remove(orderInfo.getPlayer().getUniqueId());
        }
        getLogger().info("已清理订单 ID: " + payId);
    }

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

    public void giveRewards(OfflinePlayer offlinePlayer, ProjectConfig project) {
        if (offlinePlayer == null) {
            getLogger().warning("发放奖励时玩家对象为 null");
            return;
        }

        if (project == null) {
            getLogger().warning("发放奖励时项目为空");
            return;
        }

        UUID playerUUID = offlinePlayer.getUniqueId();
        String playerName = offlinePlayer.getName();

        if (playerUUID == null || playerName == null) {
            getLogger().warning("发放奖励时玩家信息不完整");
            return;
        }

        String orderId = playerPendingOrders.get(playerUUID);
        double reallyPrice = orderPrice.getOrDefault(orderId, project.getAmount());

        if (!offlinePlayer.isOnline()) {
            cacheOfflineRewards(playerUUID, project, reallyPrice);
            getLogger().info("玩家 " + playerName + " 离线，奖励已暂存");
            return;
        }

        Player player = offlinePlayer.getPlayer();
        if (player == null) {
            getLogger().warning("无法获取在线玩家实例: " + playerName);
            return;
        }

        for (String command : project.getRewards()) {
            try {
                String finalCommand = command
                        .replace("%player%", playerName)
                        .replace("%finalprice%", String.valueOf(reallyPrice));
                Bukkit.getGlobalRegionScheduler().run(this, scheduledTask -> {
                    getServer().dispatchCommand(getServer().getConsoleSender(), finalCommand);
                });
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "执行奖励命令失败: " + command, e);
            }
        }
        player.sendMessage(Component.text("奖励已发放，感谢您的赞助！").color(TextColor.color(0x55FF55)));
        playerPendingOrders.remove(playerUUID);
    }



    private void cacheOfflineRewards(UUID playerUUID, ProjectConfig project, double reallyPrice) {
        pendingRewards.putIfAbsent(playerUUID, new ArrayList<>());
        String orderId = playerPendingOrders.get(playerUUID);
        for (String command : project.getRewards()) {
            String cachedCommand = command
                    .replace("%player%", Bukkit.getOfflinePlayer(playerUUID).getName())
                    .replace("%finalprice%", String.valueOf(reallyPrice));
            pendingRewards.get(playerUUID).add(cachedCommand);
        }
        // 单独存储订单ID，而不是添加到命令中
        pendingRewards.get(playerUUID).add("__ORDER_ID__:" + orderId);
        savePendingRewards();
    }

    private void loadPlayerData() {
        File file = new File(getDataFolder(), "players.yml");
        if (!file.exists()) {
            saveResource("players.yml", false);
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Set<String> invalidEntries = new HashSet<>();

        for (String uuid : config.getKeys(false)) {
            String playerOrder = config.getString(uuid + ".orderId");
            String payId = config.getString(uuid + ".payId");
            String projectId = config.getString(uuid + ".project"); // 读取项目 ID

            if (playerOrder == null || playerOrder.isEmpty() || payId == null || payId.isEmpty() || projectId == null) {
                invalidEntries.add(uuid);
                continue;
            }

            // 使用项目 ID 获取项目配置
            ProjectConfig project = projects.get(projectId);
            if (project == null) {
                getLogger().warning("无法找到项目 ID 对应的配置: " + projectId);
                invalidEntries.add(uuid);
                continue;
            }

            playerPendingOrders.put(UUID.fromString(uuid), playerOrder);
            orderMap.put(payId, new OrderInfo(Bukkit.getOfflinePlayer(UUID.fromString(uuid)), project));
        }

        // 清理无效的玩家订单记录
        for (String invalidUUID : invalidEntries) {
            config.set(invalidUUID, null);
            getLogger().warning("移除无效的玩家订单记录: " + invalidUUID);
        }

        try {
            config.save(file);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "保存清理后的 players.yml 失败", e);
        }

        // 检查所有加载的订单状态
        for (Map.Entry<UUID, String> entry : playerPendingOrders.entrySet()) {
            checkOrderStatus(entry.getValue());
            getLogger().info("已加载玩家 UUID: " + entry.getKey() + ", 订单号: " + entry.getValue());
        }
    }


    private void checkOrderStatus(String orderId) {
        String url = String.format("%s/checkOrder?orderId=%s", vmqDomain, orderId);
        getLogger().info("查询订单链接: " + url);
        Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                JsonObject jsonResponse = gson.fromJson(response.body(), JsonObject.class);

                if (jsonResponse.has("code")) {
                    int code = jsonResponse.get("code").getAsInt();
                    String msg = jsonResponse.has("msg") ? jsonResponse.get("msg").getAsString() : "未知错误";

                    if (code == -1 && !msg.contains("订单未支付")) {
                        // 对于 code=-1 且不是"订单未支付"的情况，从 players.yml 中移除
                        removeOrderFromPlayersYml(orderId);
                        getLogger().info("已从 players.yml 中移除订单: " + orderId + ", 原因: " + msg);
                        return;
                    }

                    if (msg.contains("订单未支付")) {
                        getLogger().info("订单未支付，已加载: " + orderId);
                        return; // 如果订单未支付，直接返回，不做其他处理
                    }

                    // 如果到达这里，说明订单不是未支付状态
                    removePlayerPendingOrder(orderId);

                    if (code == 1) {
                        handleSuccessfulOrder(jsonResponse, orderId);
                    } else {
                        handleFailedOrder(orderId, msg);
                    }
                } else {
                    getLogger().warning("查询订单状态返回的数据格式无效: " + response.body());
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "查询订单状态时发生错误", e);
            }
        });
    }

    private void removeOrderFromPlayersYml(String orderId) {
        File file = new File(getDataFolder(), "players.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        boolean changed = false;

        for (String key : config.getKeys(false)) {
            if (orderId.equals(config.getString(key + ".orderId"))) {
                config.set(key, null);
                changed = true;
                break;
            }
        }

        if (changed) {
            try {
                config.save(file);
                getLogger().info("已从 players.yml 中移除订单: " + orderId);
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "从 players.yml 移除订单 " + orderId + " 时发生错误", e);
            }
        }
    }


    private void handleSuccessfulOrder(JsonObject jsonResponse, String orderId) {
        if (jsonResponse.has("data") && !jsonResponse.get("data").isJsonNull()) {
            String data = jsonResponse.get("data").getAsString();
            Map<String, String> params = parseUrlParams(data);
            String reallyPrice = params.get("reallyPrice");
            String payId = params.get("payId");
            orderPrice.put(orderId, Double.valueOf(reallyPrice));
            getLogger().info(orderId + ": 实际支付金额 " + reallyPrice);

            File file = new File(getDataFolder(), "players.yml");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            String playerUUID = null;
            String projectId = null;

            for (String uuidStr : config.getKeys(false)) {
                if (orderId.equals(config.getString(uuidStr + ".orderId"))) {
                    playerUUID = uuidStr;
                    projectId = config.getString(uuidStr + ".project");
                    break;
                }
            }

            if (playerUUID != null && projectId != null) {
                UUID uuid = UUID.fromString(playerUUID);
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
                ProjectConfig project = projects.get(projectId);

                if (project != null) {
                    OrderInfo orderInfo = new OrderInfo(offlinePlayer, project);
                    orderMap.put(payId, orderInfo);

                    giveRewards(offlinePlayer, project);
                    saveOrderHistory(orderId, offlinePlayer.getName(), projectId, true);
                } else {
                    getLogger().warning("无法找到订单 " + orderId + " 对应的项目信息");
                }
            } else {
                getLogger().warning("无法找到订单 " + orderId + " 对应的玩家或项目信息");
            }
        } else {
            getLogger().warning("订单状态为成功，但 data 为空: " + orderId);
        }
    }

    private void handleFailedOrder(String orderId, String msg) {
        getLogger().info("订单状态异常: " + orderId + ", 原因: " + msg);
        OrderInfo orderInfo = orderMap.get(orderId);
        if (orderInfo != null) {
            saveOrderHistory(orderId, orderInfo.getPlayer().getName(), orderInfo.getProject().getName(), false);
        }
        orderMap.remove(orderId);
    }



    private void handlePaidOrderAfterRestart(String orderId, String reallyPrice) {
        File file = new File(getDataFolder(), "players.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String playerUUID = null;

        // 修复开始：遍历所有UUID条目，检查各自的orderId字段
        for (String uuidStr : config.getKeys(false)) {
            String storedOrderId = config.getString(uuidStr + ".orderId");
            if (orderId.equals(storedOrderId)) {
                playerUUID = uuidStr;
                break;
            }
        }
        // 修复结束

        if (playerUUID != null) {
            UUID uuid = UUID.fromString(playerUUID);
            Player player = Bukkit.getPlayer(uuid);

            String projectName = config.getString(playerUUID + ".project");
            ProjectConfig project = projects.get(projectName);

            if (project != null) {
                // 直接调用 giveRewards，它会处理玩家在线和离线的情况
                giveRewards(player, project);
                saveOrderHistory(orderId, Bukkit.getOfflinePlayer(uuid).getName(), projectName, true);
            } else {
                getLogger().warning("无法找到订单 " + orderId + " 对应的项目信息");
            }
        } else {
            getLogger().warning("无法找到订单 " + orderId + " 对应的玩家信息");
        }

        // 清理订单信息
        removePlayerPendingOrder(orderId);
    }

    private void removePlayerPendingOrder(String orderId) {
        File file = new File(getDataFolder(), "players.yml");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        for (String uuid : config.getKeys(false)) {
            if (orderId.equals(config.getString(uuid))) {
                config.set(uuid, null);
                getLogger().info("已清理 players.yml 中的订单: " + orderId);
                break;
            }
        }

        try {
            config.save(file);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "保存 players.yml 失败", e);
        }
    }

    private void saveOrderHistory(String orderId, String playerName, String projectName, boolean success) {
        File file = new File(getDataFolder(), "history.yml");
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "创建 history.yml 文件失败", e);
                return;
            }
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        Map<String, Object> historyEntry = new HashMap<>();
        historyEntry.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        historyEntry.put("orderId", orderId);
        historyEntry.put("player", playerName);
        historyEntry.put("project", projectName);
        historyEntry.put("price", orderPrice.getOrDefault(orderId, 0.0));
        historyEntry.put("status", success ? "成功" : "失败");

        config.set("history." + orderId, historyEntry);

        try {
            config.save(file);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "保存历史记录失败", e);
        }
    }


    private Map<String, String> parseUrlParams(String url) {
        Map<String, String> params = new HashMap<>();
        try {
            String query = new URI(url).getQuery();
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] pair = param.split("=");
                    if (pair.length > 1) {
                        params.put(pair[0], URLDecoder.decode(pair[1], StandardCharsets.UTF_8.name()));
                    } else {
                        params.put(pair[0], "");
                    }
                }
            }
        } catch (Exception e) {
            getLogger().warning("解析 URL 参数时发生错误: " + e.getMessage());
        }
        return params;
    }


    public static class ProjectConfig {
        private final String name;
        private final String lore;
        private final double amount;
        private final List<String> rewards;

        public ProjectConfig(String name, String lore, double amount, List<String> rewards) {
            this.name = name;
            this.lore = lore;
            this.amount = amount;
            this.rewards = rewards;
        }

        public String getName() {
            return name;
        }

        public String getLore() {
            return lore;
        }

        public double getAmount() {
            return amount;
        }

        public List<String> getRewards() {
            return rewards;
        }
    }
}
