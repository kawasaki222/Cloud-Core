package com.cloudcore.bukkit;

import com.cloudcore.protocol.Frame;
import com.cloudcore.protocol.FrameCodec;
import com.cloudcore.protocol.Limits;
import com.cloudcore.protocol.ProtocolException;
import java.util.logging.Level;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class CloudCorePlugin extends JavaPlugin implements PluginMessageListener {

    public static final String CHANNEL = "cloudcore:main";

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getLogger().info("Cloud Core transport online on " + CHANNEL);
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        getLogger().info("Cloud Core transport offline");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        try {
            Frame frame = FrameCodec.decode(message, Limits.C2S);
            if (getConfig().getBoolean("debug", false)) {
                getLogger().info("C2S opcode=0x" + Integer.toHexString(frame.opcode())
                        + " bytes=" + frame.bodyLength() + " player=" + player.getUniqueId());
            }
        } catch (ProtocolException exception) {
            getLogger().log(Level.WARNING, "Rejected malformed frame from " + player.getUniqueId(), exception);
        }
    }
}
