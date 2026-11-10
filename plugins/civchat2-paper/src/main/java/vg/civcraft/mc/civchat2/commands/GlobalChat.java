package vg.civcraft.mc.civchat2.commands;

import co.aikar.commands.BaseCommand;
import co.aikar.commands.annotation.CommandAlias;
import co.aikar.commands.annotation.Description;
import co.aikar.commands.annotation.Optional;
import co.aikar.commands.annotation.Syntax;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import vg.civcraft.mc.civchat2.CivChat2;
import vg.civcraft.mc.civchat2.CivChat2Manager;

/**
 * Sekai: /global switches to server-wide chat, /global <message> sends a single server-wide message.
 */
public class GlobalChat extends BaseCommand {

    @CommandAlias("global|globalchat")
    @Syntax("[message]")
    @Description("Enters server-wide chat or sends one message to everyone")
    public void execute(Player player, @Optional String chatMessage) {
        CivChat2Manager chatMan = CivChat2.getInstance().getCivChat2Manager();
        if (chatMessage == null) {
            if (chatMan.getChannel(player) != null) {
                chatMan.removeChannel(player);
            }
            if (chatMan.getGroupChatting(player) != null) {
                chatMan.removeGroupChat(player);
            }
            chatMan.setServerWide(player, true);
            player.sendMessage(Component.text("You are now in global chat. Use /local to go back.", NamedTextColor.GOLD));
            return;
        }
        chatMan.sendServerWideMsg(player, Component.text(chatMessage));
    }
}
