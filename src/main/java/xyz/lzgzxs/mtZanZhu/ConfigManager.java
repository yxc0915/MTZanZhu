package xyz.lzgzxs.mtZanZhu;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.Arrays; // 导入 Arrays 类
import java.util.List;

public class ConfigManager {
    private final JavaPlugin plugin;
    private final FileConfiguration config;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    /**
     * 获取检查间隔时间（秒）
     */
    public int getCheckInterval() {
        return config.getInt("vmq.check_interval", 5);
    }

    /**
     * 获取订单超时时间（秒）
     */
    public int getTimeout() {
        return config.getInt("vmq.timeout", 300);
    }

    /**
     * 获取 VMQ 的 API 地址
     */
    public String getVmqDomain() {
        return config.getString("vmq.domain");
    }

    /**
     * 获取 VMQ 的密钥
     */
    public String getVmqKey() {
        return config.getString("vmq.key");
    }

    /**
     * 获取回调服务器的端口
     */
    public int getCallbackPort() {
        return config.getInt("vmq.callback.port", 8080);
    }

    /**
     * 获取回调服务器的路径
     */
    public String getCallbackPath() {
        return config.getString("vmq.callback.path", "/vmq-callback");
    }

    /**
     * 获取回调地址（notifyUrl）
     */
    public String getNotifyUrl() {
        return config.getString("vmq.callback.notifyUrl", "");
    }

    /**
     * 设置回调地址（notifyUrl）
     */
    public void setNotifyUrl(String notifyUrl) {
        config.set("vmq.callback.notifyUrl", notifyUrl);
        plugin.saveConfig();
    }

    public boolean isCustomPriceCommandEnabled() {
        return config.getBoolean("custom_price_command.enabled", false);
    }

    public String getCustomPriceCommandName() {
        return config.getString("custom_price_command.name", "vmqprice");
    }

    public List<String> getCustomPriceRewards() {
        return config.getStringList("custom_price_command.rewards");
    }

    /**
     * 生成默认配置
     */
    public void setupDefaultConfig() {
        // 设置默认的 VMQ 配置
        config.addDefault("vmq.domain", "https://your-vmq-domain.com");
        config.addDefault("vmq.key", "your-vmq-key");
        config.addDefault("vmq.check_interval", 5);
        config.addDefault("vmq.timeout", 300);

        // 设置默认的回调服务器配置
        config.addDefault("vmq.callback.port", 8080);
        config.addDefault("vmq.callback.path", "/vmq-callback");
        config.addDefault("vmq.callback.notifyUrl", ""); // 默认为空，插件启动时会自动生成

        // 设置默认的赞助项目配置
        config.addDefault("projects.project1.name", "项目1");
        config.addDefault("projects.project1.lore", "项目1的描述");
        config.addDefault("projects.project1.amount", 0.01);
        config.addDefault("projects.project1.rewards", Arrays.asList("give %player% diamond 1"));

        config.addDefault("projects.project2.name", "项目2");
        config.addDefault("projects.project2.lore", "项目2的描述");
        config.addDefault("projects.project2.amount", 2.00);
        config.addDefault("projects.project2.rewards", Arrays.asList("give %player% gold_ingot 5"));

        // 添加自定义价格命令的默认配置
        config.addDefault("custom_price_command.enabled", false);
        config.addDefault("custom_price_command.name", "vmqprice");
        config.addDefault("custom_price_command.enabled", false);
        config.addDefault("custom_price_command.name", "vmqprice");
        config.addDefault("custom_price_command.rewards", Arrays.asList(
                "give %player% diamond %finalprice%",
                "title %player% \"&a感谢赞助\" \"&e赞助金额: %finalprice%元\""
        ));



        // 保存默认配置
        config.options().copyDefaults(true);
        plugin.saveConfig();
    }
}