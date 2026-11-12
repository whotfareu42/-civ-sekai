package com.github.maxopoly.essenceglue;

import com.github.maxopoly.essenceglue.reward.Rewarder;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

public class RewardManager {

    /** Set by CivNova events (x1 = normal). */
    public static volatile int loginMultiplier = 1;
    public static volatile int voteMultiplier = 1;

    private final Rewarder rewarder;
    private final int dailyReward;
    private final int voteReward;

    public RewardManager(Rewarder rewarder, int dailyReward, int voteReward) {
        this.rewarder = rewarder;
        this.dailyReward = dailyReward;
        this.voteReward = voteReward;
    }

    public void giveLoginReward(Player p, int streak) {
        int mult = Math.max(1, loginMultiplier);
        p.sendMessage(ChatColor.GREEN + "You've received your daily login reward" + (mult > 1 ? ChatColor.GOLD + " (event x" + mult + ")" : ""));
        rewarder.reward(p, dailyReward * streak * mult);
    }

    public void giveVoteReward(Player p, String page) {
        int mult = Math.max(1, voteMultiplier);
        p.sendMessage(ChatColor.GREEN + "You received a reward for voting on " + page + (mult > 1 ? ChatColor.GOLD + " (event x" + mult + ")" : ""));
        rewarder.reward(p, voteReward * mult);
    }

}
