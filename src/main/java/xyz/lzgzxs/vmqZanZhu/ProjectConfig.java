package xyz.lzgzxs.vmqZanZhu;

import java.util.List;

public class ProjectConfig {
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
