package xyz.lzgzxs.mtZanZhu;

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

public class MTZanZhu extends JavaPlugin implements TabExecutor {
    // 定义常量
    private static final String ORDER_ID_PREFIX = "__ORDER_ID__:";
    private static final String PLAYERS_FILE = "players.yml";
    private static final String PENDING_REWARDS_FILE = "pending-rewards.yml";
    private static final String HISTORY_FILE = "history.yml";
    private static final String CUSTOM_HISTORY_FILE = "custom-history.yml";
    private static final String CUSTOM_PLAYERS_FILE = "custom-players.yml";

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

    private boolean customPriceCommandEnabled;
    private String customPriceCommandName;
    private List<String> customPriceRewards;

    private class PlayerJoinListener implements Listener {
        @EventHandler
        public void onPlayerJoin(PlayerJoinEvent event) {
            Player player = event.getPlayer();
            UUID playerUUID = player.getUniqueId();

            if (pendingRewards.containsKey(playerUUID)) {
                List<String> commands = new ArrayList<>(pendingRewards.get(playerUUID));
                final String[] orderIdHolder = new String[1];
                final Double[] priceHolder = new Double[1];

                Bukkit.getGlobalRegionScheduler().execute(MTZanZhu.this, () -> {
                    for (String cmd : commands) {
                        if (cmd.startsWith(ORDER_ID_PREFIX)) {
                            orderIdHolder[0] = cmd.substring(ORDER_ID_PREFIX.length());
                            priceHolder[0] = orderPrice.get(orderIdHolder[0]);
                            continue;
                        }
                        String finalCmd = cmd;
                        if (orderIdHolder[0] != null && priceHolder[0] != null) {
                            finalCmd = replacePlaceholders(cmd, player.getName(), priceHolder[0]);
                        }
                        getServer().dispatchCommand(getServer().getConsoleSender(), finalCmd);
                    }

                    pendingRewards.remove(playerUUID);
                    saveYamlConfig(PENDING_REWARDS_FILE, pendingRewardsToYaml());

                    if (orderIdHolder[0] != null) {
                        orderMap.remove(orderIdHolder[0]);
                        orderPrice.remove(orderIdHolder[0]);
                        cleanupPlayerData(playerUUID);
                    }

                    player.sendMessage(Component.text("检测到您有未领取的赞助奖励，已自动补发！"));
                });
            }
        }
    }

    private YamlConfiguration loadYamlConfig(String fileName) {
        synchronized(fileLock) {
            File file = new File(getDataFolder(), fileName);
            if (!file.exists()) saveResource(fileName, false);
            return YamlConfiguration.loadConfiguration(file);
        }
    }

    private void saveYamlConfig(String fileName, YamlConfiguration config) {
        synchronized(fileLock) {
            try {
                config.save(new File(getDataFolder(), fileName));
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "保存文件失败: " + fileName, e);
            }
        }
    }

    private JsonObject sendHttpRequest(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return gson.fromJson(response.body(), JsonObject.class);
    }

    private String replacePlaceholders(String command, String playerName, double price) {
        return command.replace("%player%", playerName)
                .replace("%finalprice%", String.valueOf((int)price))
                .replace("%finalprice_exact%", String.format("%.2f", price));
    }

    private String buildUrlWithParams(String baseUrl, Map<String, String> params) {
        StringBuilder urlBuilder = new StringBuilder(baseUrl);
        boolean isFirstParam = true;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (isFirstParam) {
                urlBuilder.append("?");
                isFirstParam = false;
            } else {
                urlBuilder.append("&");
            }
            urlBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                    .append("=")
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
        }
        return urlBuilder.toString();
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
        File historyFile = new File(getDataFolder(), HISTORY_FILE);
        if (!historyFile.exists()) {
            saveResource(HISTORY_FILE, false);
        }
        if (configManager.isCustomPriceCommandEnabled()) {
            Objects.requireNonNull(getCommand(configManager.getCustomPriceCommandName())).setExecutor(this);
        }

        getLogger().info("枫迹云赞助插件已启动！");
        loadPlayerData();
        loadPendingRewards();

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(), this);
    }

    @Override
    public void onDisable() {
        if (callbackServer != null) {
            callbackServer.stop();
        }
        savePendingRewards();
    }

    private void loadConfig() {
        reloadConfig();
        FileConfiguration config = getConfig();
        projects.clear();

        vmqDomain = config.getString("vmq.domain");
        vmqKey = config.getString("vmq.key");
        customPriceCommandEnabled = config.getBoolean("custom_price_command.enabled", false);
        customPriceCommandName = config.getString("custom_price_command.name", "vmqprice");
        customPriceRewards = config.getStringList("custom_price_command.rewards");

        if (customPriceCommandEnabled) {
            Objects.requireNonNull(getCommand(customPriceCommandName)).setExecutor(this);
        }

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

        ConfigurationSection projectsSection = config.getConfigurationSection("projects");
        if (projectsSection != null) {
            for (String key : projectsSection.getKeys(false)) {
                String name = config.getString("projects." + key + ".name");
                String lore = config.getString("projects." + key + ".lore");
                double amount = config.getDouble("projects." + key + ".amount");
                List<String> rewards = config.getStringList("projects." + key + ".rewards");

                projects.put(key, new ProjectConfig(name, lore, amount, rewards));
            }
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
        if (sender instanceof Player player) {
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
                sender.sendMessage(Component.text("支付方式: wx (微信) 或 zfb (支付宝)，默认为支付宝").color(TextColor.color(0xFFAA00)));
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

        if (command.getName().equalsIgnoreCase(customPriceCommandName)) {
            if (!customPriceCommandEnabled) {
                sender.sendMessage(Component.text("自定义价格命令未启用。").color(TextColor.color(0xFF5555)));
                return true;
            }

            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("该命令只能由玩家执行！").color(TextColor.color(0xFF5555)));
                return true;
            }

            if (args.length < 1 || args.length > 2) {
                player.sendMessage(Component.text("用法: /" + this.customPriceCommandName + " <金额> [支付方式]").color(TextColor.color(0xFF5555)));
                player.sendMessage(Component.text("支付方式: wx (微信) 或 zfb (支付宝)，默认为支付宝").color(TextColor.color(0xFFAA00)));
                return true;
            }

            int amount;
            try {
                amount = Integer.parseInt(args[0]);
                if (amount <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("金额必须是大于0的整数！").color(TextColor.color(0xFF5555)));
                return true;
            }

            int payType = 2; // 默认支付宝
            if (args.length == 2) {
                if (args[1].equalsIgnoreCase("wx")) {
                    payType = 1;
                } else if (!args[1].equalsIgnoreCase("zfb")) {
                    player.sendMessage(Component.text("无效的支付方式，使用默认的支付宝支付").color(TextColor.color(0xFFAA00)));
                }
            }

            createCustomPriceOrder(player, amount, payType);
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
            closeExistingOrder(player, () -> createOrderInternal(player, projectId, project, payType));
        } else {
            createOrderInternal(player, projectId, project, payType);
        }
    }

    private void createOrderInternal(Player player, String projectId, ProjectConfig project, int payType) {
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

        Map<String, String> params = new HashMap<>();
        params.put("payId", payId);
        params.put("param", param);
        params.put("type", String.valueOf(payType));
        params.put("price", price);
        params.put("sign", sign);
        params.put("notifyUrl", notifyUrl);

        String url = buildUrlWithParams(vmqDomain + "/createOrder", params);

        String payMethod = payType == 1 ? "微信" : "支付宝";
        Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> {
            try {
                JsonObject jsonResponse = sendHttpRequest(url);

                if (jsonResponse.get("code").getAsInt() == 1) {
                    JsonObject data = jsonResponse.getAsJsonObject("data");
                    if (data != null && !data.isJsonNull()) {
                        String orderID = data.get("orderId").getAsString();
                        playerPendingOrders.put(playerUUID, orderID);
                        String payUrl = data.get("payUrl").getAsString();
                        double reallyPrice = data.get("reallyPrice").getAsDouble();
                        orderPrice.put(orderID, reallyPrice);

                        saveOrderToPlayersYml(playerUUID, orderID, projectId, payId);

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
                handleHttpError(player, "创建订单", e);
                playerPendingOrders.remove(playerUUID);
            }
        });
    }

    private void createCustomPriceOrder(Player player, int amount, int payType) {
        UUID playerUUID = player.getUniqueId();

        if (playerPendingOrders.containsKey(playerUUID)) {
            closeExistingOrder(player, () -> createCustomPriceOrderInternal(player, amount, payType));
        } else {
            createCustomPriceOrderInternal(player, amount, payType);
        }
    }

    private void createCustomPriceOrderInternal(Player player, int amount, int payType) {
        UUID playerUUID = player.getUniqueId();
        String payId = System.currentTimeMillis() + "";
        String param = player.getName();
        String price = String.valueOf(amount);

        List<String> customRewards = this.customPriceRewards;

        SimpleDateFormat sdf = new SimpleDateFormat("yy/MM/dd HH:mm:ss");
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.MINUTE, 5);
        String expireTime = sdf.format(calendar.getTime());

        ProjectConfig customProject = new ProjectConfig(
                "自定义金额",
                "自定义金额赞助",
                amount,
                customRewards
        );
        orderMap.put(payId, new OrderInfo(player, customProject));

        String sign = md5(payId + param + payType + price + vmqKey);

        Map<String, String> params = new HashMap<>();
        params.put("payId", payId);
        params.put("param", param);
        params.put("type", String.valueOf(payType));
        params.put("price", price);
        params.put("sign", sign);
        params.put("notifyUrl", notifyUrl);

        String url = buildUrlWithParams(vmqDomain + "/createOrder", params);

        String payMethod = payType == 1 ? "微信" : "支付宝";
        Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> {
            try {
                JsonObject jsonResponse = sendHttpRequest(url);

                if (jsonResponse.get("code").getAsInt() == 1) {
                    JsonObject data = jsonResponse.getAsJsonObject("data");
                    if (data != null && !data.isJsonNull()) {
                        String orderID = data.get("orderId").getAsString();
                        playerPendingOrders.put(playerUUID, orderID);
                        String payUrl = data.get("payUrl").getAsString();
                        double reallyPrice = data.get("reallyPrice").getAsDouble();
                        orderPrice.put(orderID, reallyPrice);

                        saveCustomOrderToPlayersYml(playerUUID, orderID, payId);

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
                handleHttpError(player, "创建订单", e);
                playerPendingOrders.remove(playerUUID);
            }
        });
    }

    private void closeExistingOrder(Player player, Runnable callback) {
        UUID playerUUID = player.getUniqueId();
        String orderId = playerPendingOrders.get(playerUUID);
        String url = String.format("%s/closeOrder?orderId=%s&sign=%s", vmqDomain, orderId, md5(playerPendingOrders.get(playerUUID) + vmqKey));

        Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> {
            try {
                JsonObject jsonResponse = sendHttpRequest(url);

                getLogger().info("关闭订单响应: " + jsonResponse);

                if (jsonResponse != null && jsonResponse.has("code")) {
                    int code = jsonResponse.get("code").getAsInt();
                    if (code == 1) {
                        getLogger().info("成功关闭订单: " + orderId);
                        removeOrderFromPlayersYml(PLAYERS_FILE, playerUUID);
                        removeOrderFromPlayersYml(CUSTOM_PLAYERS_FILE, playerUUID);
                        Bukkit.getScheduler().runTask(this, callback);
                    } else {
                        String msg = jsonResponse.has("msg") ? jsonResponse.get("msg").getAsString() : "未知错误";
                        getLogger().warning("关闭订单失败: " + orderId + ", 原因: " + msg);
                    }
                } else {
                    getLogger().warning("关闭订单响应格式无效: " + jsonResponse);
                }
            } catch (Exception e) {
                handleHttpError(player, "关闭订单", e);
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

    private void cleanupPlayerData(UUID playerUUID) {
        cleanupPlayersYml(playerUUID, PLAYERS_FILE);
        cleanupPlayersYml(playerUUID, CUSTOM_PLAYERS_FILE);
        playerPendingOrders.remove(playerUUID);
    }

    public void giveRewards(OfflinePlayer offlinePlayer, ProjectConfig project) {
        if (offlinePlayer == null || project == null) {
            getLogger().warning("发放奖励时玩家对象或项目为空");
            return;
        }

        UUID playerUUID = offlinePlayer.getUniqueId();
        String playerName = offlinePlayer.getName();

        if (playerName == null) {
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
                String finalCommand = replacePlaceholders(command, playerName, reallyPrice);
                Bukkit.getGlobalRegionScheduler().run(this, scheduledTask -> {
                    getServer().dispatchCommand(getServer().getConsoleSender(), finalCommand);
                });
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "执行奖励命令失败: " + command, e);
            }
        }
        player.sendMessage(Component.text("奖励已发放，感谢您的赞助！").color(TextColor.color(0x55FF55)));

        cleanupPlayerData(playerUUID);

        orderMap.remove(orderId);
        orderPrice.remove(orderId);
    }

    private void cacheOfflineRewards(UUID playerUUID, ProjectConfig project, double reallyPrice) {
        pendingRewards.putIfAbsent(playerUUID, new ArrayList<>());
        String orderId = playerPendingOrders.get(playerUUID);
        for (String command : project.getRewards()) {
            pendingRewards.get(playerUUID).add(command);
        }
        pendingRewards.get(playerUUID).add(ORDER_ID_PREFIX + orderId);
        orderPrice.put(orderId, reallyPrice);
        savePendingRewards();
    }

    private void loadPlayerData() {
        loadProjectPlayerData();
        loadCustomPlayerData();
    }

    private void loadProjectPlayerData() {
        YamlConfiguration config = loadYamlConfig(PLAYERS_FILE);

        Set<String> invalidEntries = new HashSet<>();

        for (String uuid : config.getKeys(false)) {
            String playerOrder = config.getString(uuid + ".orderId");
            String payId = config.getString(uuid + ".payId");
            String projectId = config.getString(uuid + ".project");

            if (playerOrder == null || playerOrder.isEmpty() || payId == null || payId.isEmpty() || projectId == null) {
                invalidEntries.add(uuid);
                continue;
            }

            ProjectConfig project = projects.get(projectId);
            if (project == null) {
                getLogger().warning("无法找到项目 ID 对应的配置: " + projectId);
                invalidEntries.add(uuid);
                continue;
            }

            UUID playerUUID = UUID.fromString(uuid);
            playerPendingOrders.put(playerUUID, playerOrder);
            orderMap.put(payId, new OrderInfo(Bukkit.getOfflinePlayer(playerUUID), project));

            checkOrderStatus(playerOrder);
            getLogger().info("已加载玩家 UUID: " + uuid + ", 订单号: " + playerOrder);
        }

        // 清理无效的玩家订单记录
        for (String invalidUUID : invalidEntries) {
            config.set(invalidUUID, null);
            getLogger().warning("移除无效的玩家订单记录: " + invalidUUID);
        }

        saveYamlConfig(PLAYERS_FILE, config);
    }

    private void loadCustomPlayerData() {
        YamlConfiguration config = loadYamlConfig(CUSTOM_PLAYERS_FILE);

        Set<String> invalidEntries = new HashSet<>();

        for (String uuid : config.getKeys(false)) {
            String orderId = config.getString(uuid + ".orderId");
            String payId = config.getString(uuid + ".payId");

            if (orderId == null || orderId.isEmpty() || payId == null || payId.isEmpty()) {
                invalidEntries.add(uuid);
                continue;
            }

            ProjectConfig project = new ProjectConfig("自定义金额", "自定义金额赞助", 0, customPriceRewards);
            UUID playerUUID = UUID.fromString(uuid);
            playerPendingOrders.put(playerUUID, orderId);
            orderMap.put(payId, new OrderInfo(Bukkit.getOfflinePlayer(playerUUID), project));

            checkOrderStatus(orderId);
            getLogger().info("已加载自定义赞助玩家 UUID: " + uuid + ", 订单号: " + orderId);
        }

        // 清理无效的玩家订单记录
        for (String invalidUUID : invalidEntries) {
            config.set(invalidUUID, null);
            getLogger().warning("移除无效的玩家订单记录: " + invalidUUID);
        }

        saveYamlConfig(CUSTOM_PLAYERS_FILE, config);
    }

    private void checkOrderStatus(String orderId) {
        String url = String.format("%s/checkOrder?orderId=%s", vmqDomain, orderId);
        getLogger().info("查询订单链接: " + url);
        Bukkit.getAsyncScheduler().runNow(this, scheduledTask -> {
            try {
                JsonObject jsonResponse = sendHttpRequest(url);

                if (jsonResponse.has("code")) {
                    int code = jsonResponse.get("code").getAsInt();
                    String msg = jsonResponse.has("msg") ? jsonResponse.get("msg").getAsString() : "未知错误";

                    if (code == -1 && !msg.contains("订单未支付")) {
                        removeAbnormalOrder(orderId, msg);
                        return;
                    }

                    if (msg.contains("订单未支付")) {
                        getLogger().info("订单未支付，已加载: " + orderId);
                        return;
                    }

                    removePlayerPendingOrder(orderId);

                    if (code == 1) {
                        handleSuccessfulOrder(jsonResponse, orderId);
                    } else {
                        handleFailedOrder(orderId, msg);
                    }
                } else {
                    getLogger().warning("查询订单状态返回的数据格式无效: " + jsonResponse);
                }
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "查询订单状态时发生错误", e);
            }
        });
    }

    private void removeAbnormalOrder(String orderId, String reason) {
        for (Map.Entry<UUID, String> entry : playerPendingOrders.entrySet()) {
            if (entry.getValue().equals(orderId)) {
                UUID playerUUID = entry.getKey();
                removeOrderFromPlayersYml(PLAYERS_FILE, playerUUID);
                removeOrderFromPlayersYml(CUSTOM_PLAYERS_FILE, playerUUID);
                playerPendingOrders.remove(playerUUID);
                orderMap.remove(orderId);
                orderPrice.remove(orderId);
                getLogger().info("已从内存和 players.yml 中移除异常订单: " + orderId + ", 玩家UUID: " + playerUUID);
                break;
            }
        }

        getLogger().info("已移除异常订单: " + orderId + ", 原因: " + reason);
    }

    private void removeOrderFromPlayersYml(String fileName, UUID playerUUID) {
        YamlConfiguration config = loadYamlConfig(fileName);
        String uuidString = playerUUID.toString();

        if (config.contains(uuidString)) {
            config.set(uuidString, null);
            saveYamlConfig(fileName, config);
            getLogger().info("已从 " + fileName + " 中移除玩家 UUID: " + uuidString);
        } else {
            getLogger().warning("在 " + fileName + " 中未找到匹配的记录: " + uuidString);
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

            OrderInfo orderInfo = orderMap.get(payId);
            if (orderInfo != null) {
                OfflinePlayer offlinePlayer = orderInfo.getPlayer();
                ProjectConfig project = orderInfo.getProject();

                if (project != null) {
                    giveRewards(offlinePlayer, project);
                    saveOrderHistory(orderId, offlinePlayer.getName(), project.getName(), true);
                    removePlayerPendingOrder(orderId);
                } else {
                    getLogger().warning("无法找到订单 " + orderId + " 对应的项目信息");
                }
            } else {
                getLogger().warning("无法找到订单 " + orderId + " 对应的订单信息");
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

    private void saveOrderHistory(String orderId, String playerName, String projectName, boolean success) {
        YamlConfiguration config;
        String fileToUse;

        if (projectName.equals("自定义金额")) {
            config = loadYamlConfig(CUSTOM_HISTORY_FILE);
            fileToUse = CUSTOM_HISTORY_FILE;
        } else {
            config = loadYamlConfig(HISTORY_FILE);
            fileToUse = HISTORY_FILE;
        }

        Map<String, Object> historyEntry = new HashMap<>();
        historyEntry.put("time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        historyEntry.put("orderId", orderId);
        historyEntry.put("player", playerName);
        historyEntry.put("project", projectName);
        historyEntry.put("price", orderPrice.getOrDefault(orderId, 0.0));
        historyEntry.put("status", success ? "成功" : "失败");

        config.set("history." + orderId, historyEntry);

        saveYamlConfig(fileToUse, config);
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

    private void handleHttpError(Player player, String operation, Exception e) {
        getLogger().log(Level.SEVERE, operation + "时发生错误", e);
        player.sendMessage(Component.text(operation + "失败，请稍后再试。").color(TextColor.color(0xFF5555)));
    }

    private void saveOrderToPlayersYml(UUID playerUUID, String orderId, String projectId, String payId) {
        YamlConfiguration config = loadYamlConfig(PLAYERS_FILE);
        config.set(playerUUID.toString() + ".orderId", orderId);
        config.set(playerUUID.toString() + ".project", projectId);
        config.set(playerUUID.toString() + ".payId", payId);
        saveYamlConfig(PLAYERS_FILE, config);
    }

    private void saveCustomOrderToPlayersYml(UUID playerUUID, String orderId, String payId) {
        YamlConfiguration config = loadYamlConfig(CUSTOM_PLAYERS_FILE);
        config.set(playerUUID.toString() + ".orderId", orderId);
        config.set(playerUUID.toString() + ".payId", payId);
        saveYamlConfig(CUSTOM_PLAYERS_FILE, config);
    }

    private void removePlayerPendingOrder(String orderId) {
        removeOrderFromFile(PLAYERS_FILE, orderId);
        removeOrderFromFile(CUSTOM_PLAYERS_FILE, orderId);
    }

    private void removeOrderFromFile(String fileName, String orderId) {
        YamlConfiguration config = loadYamlConfig(fileName);

        for (String uuid : config.getKeys(false)) {
            if (orderId.equals(config.getString(uuid + ".orderId"))) {
                config.set(uuid, null);
                getLogger().info("已清理 " + fileName + " 中的订单: " + orderId);
                break;
            }
        }

        saveYamlConfig(fileName, config);
    }

    private void loadPendingRewards() {
        YamlConfiguration config = loadYamlConfig(PENDING_REWARDS_FILE);
        ConfigurationSection pendingSection = config.getConfigurationSection("pending");
        if (pendingSection == null) return;

        for (String uuidStr : pendingSection.getKeys(false)) {
            UUID uuid = UUID.fromString(uuidStr);
            List<Map<?, ?>> rewardData = config.getMapList("pending." + uuidStr + ".rewards");
            String orderId = config.getString("pending." + uuidStr + ".orderId");
            Double price = config.getDouble("pending." + uuidStr + ".price");

            List<String> commands = new ArrayList<>();
            for (Map<?, ?> entry : rewardData) {
                String command = (String) entry.get("command");
                commands.add(command);
            }

            if (orderId != null) {
                commands.add(ORDER_ID_PREFIX + orderId);
                orderPrice.put(orderId, price);
            }

            pendingRewards.put(uuid, commands);
        }
        getLogger().info("已加载 " + pendingRewards.size() + " 个待处理的离线奖励");
    }

    private void savePendingRewards() {
        synchronized(fileLock) {
            YamlConfiguration config = new YamlConfiguration();

            for (Map.Entry<UUID, List<String>> entry : pendingRewards.entrySet()) {
                UUID uuid = entry.getKey();
                List<Map<String, Object>> rewardData = new ArrayList<>();
                String orderId = null;
                Double price = null;

                for (String cmd : entry.getValue()) {
                    if (cmd.startsWith(ORDER_ID_PREFIX)) {
                        orderId = cmd.substring(ORDER_ID_PREFIX.length());
                        price = orderPrice.get(orderId);
                        continue;
                    }
                    Map<String, Object> cmdData = new HashMap<>();
                    cmdData.put("command", cmd);
                    rewardData.add(cmdData);
                }

                if (orderId != null && price != null) {
                    config.set("pending." + uuid.toString() + ".orderId", orderId);
                    config.set("pending." + uuid.toString() + ".price", price);
                }
                config.set("pending." + uuid.toString() + ".rewards", rewardData);
            }

            saveYamlConfig(PENDING_REWARDS_FILE, config);
        }
    }

    private YamlConfiguration pendingRewardsToYaml() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, List<String>> entry : pendingRewards.entrySet()) {
            UUID uuid = entry.getKey();
            List<Map<String, Object>> rewardData = new ArrayList<>();
            String orderId = null;
            Double price = null;

            for (String cmd : entry.getValue()) {
                if (cmd.startsWith(ORDER_ID_PREFIX)) {
                    orderId = cmd.substring(ORDER_ID_PREFIX.length());
                    price = orderPrice.get(orderId);
                    continue;
                }
                Map<String, Object> cmdData = new HashMap<>();
                cmdData.put("command", cmd);
                rewardData.add(cmdData);
            }

            if (orderId != null && price != null) {
                config.set("pending." + uuid.toString() + ".orderId", orderId);
                config.set("pending." + uuid.toString() + ".price", price);
            }
            config.set("pending." + uuid.toString() + ".rewards", rewardData);
        }
        return config;
    }

    private void cleanupPlayersYml(UUID playerUUID, String fileName) {
        YamlConfiguration config = loadYamlConfig(fileName);
        String uuidString = playerUUID.toString();

        if (config.contains(uuidString)) {
            config.set(uuidString, null);
            saveYamlConfig(fileName, config);
            getLogger().info("已清理 " + fileName + " 中玩家 " + uuidString + " 的缓存");
        }
    }

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
