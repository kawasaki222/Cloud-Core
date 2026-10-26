package com.cloudcore.bukkit;

import com.cloudcore.protocol.FeatureFlags;
import com.cloudcore.protocol.Frame;
import com.cloudcore.protocol.FrameCodec;
import com.cloudcore.protocol.FrameFlags;
import com.cloudcore.protocol.FragmentAssembler;
import com.cloudcore.protocol.HandshakeCodec;
import com.cloudcore.protocol.Hello;
import com.cloudcore.protocol.HelloAck;
import com.cloudcore.protocol.Limits;
import com.cloudcore.protocol.Opcode;
import com.cloudcore.protocol.Ping;
import com.cloudcore.protocol.Pong;
import com.cloudcore.protocol.ProtocolException;
import com.cloudcore.protocol.ProtocolVersion;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

public final class CloudCorePlugin extends JavaPlugin
        implements PluginMessageListener, CommandExecutor, Listener {

    public static final String CHANNEL = "cloudcore:main";
    private static final int SERVER_FEATURES = FeatureFlags.NONE;
    private static final String PING_PERMISSION = "cloudcore.admin";

    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    @Override
    public void onEnable() {
        getServer().getMessenger().registerOutgoingPluginChannel(this, CHANNEL);
        getServer().getMessenger().registerIncomingPluginChannel(this, CHANNEL, this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("cloudcore") != null) {
            getCommand("cloudcore").setExecutor(this);
        } else {
            getLogger().warning("Command 'cloudcore' is missing from plugin.yml");
        }
        getLogger().info("Cloud Core transport online on " + CHANNEL
                + " (protocol " + ProtocolVersion.MAJOR + "." + ProtocolVersion.MINOR + ")");
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterIncomingPluginChannel(this, CHANNEL, this);
        getServer().getMessenger().unregisterOutgoingPluginChannel(this, CHANNEL);
        sessions.clear();
        getLogger().info("Cloud Core transport offline");
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        try {
            Frame frame = FrameCodec.decode(message, Limits.C2S);
            if ((frame.flags() & FrameFlags.FRAGMENT) != 0) {
                Optional<Frame> complete = session.assembler.accept(frame);
                if (complete.isEmpty()) {
                    return;
                }
                frame = complete.get();
            }
            dispatch(player, session, frame);
        } catch (ProtocolException exception) {
            session.assembler.clear();
            getLogger().warning("Dropped malformed frame from " + player.getUniqueId()
                    + ": " + exception.getMessage());
        } catch (RuntimeException exception) {
            session.assembler.clear();
            getLogger().log(Level.SEVERE,
                    "Unexpected frame failure for " + player.getUniqueId(), exception);
        }
    }

    private void dispatch(Player player, Session session, Frame frame) {
        switch (frame.opcode()) {
            case Opcode.HELLO -> handleHello(player, session, frame);
            case Opcode.PONG -> handlePong(player, session, frame);
            default -> {
                if (getConfig().getBoolean("debug", false)) {
                    getLogger().info("C2S opcode=0x" + Integer.toHexString(frame.opcode())
                            + " bytes=" + frame.bodyLength() + " player=" + player.getUniqueId());
                }
            }
        }
    }

    private void handleHello(Player player, Session session, Frame frame) {
        Hello hello = HandshakeCodec.decodeHello(frame.body(), Limits.C2S);
        session.hello = hello;
        boolean compatible = ProtocolVersion.isCompatible(hello.protoMajor());
        getLogger().info("HELLO from " + player.getName()
                + " protocol=" + hello.protoMajor() + "." + hello.protoMinor()
                + " mod=" + hello.modVersion()
                + " features=0x" + Integer.toHexString(hello.features())
                + " locale=" + hello.locale()
                + " guiScale=" + hello.guiScale()
                + " scaled=" + hello.scaledWidth() + "x" + hello.scaledHeight()
                + (compatible ? "" : " (incompatible major)"));
        sendHelloAck(player);
    }

    private void handlePong(Player player, Session session, Frame frame) {
        Pong pong = HandshakeCodec.decodePong(frame.body());
        if (!session.pingPending || pong.nonce() != session.pingNonce) {
            getLogger().info("Unexpected PONG nonce=0x" + Integer.toHexString(pong.nonce())
                    + " from " + player.getName());
            return;
        }
        session.pingPending = false;
        long rttMillis = Math.max(0L, (System.nanoTime() - session.pingSentNanos) / 1_000_000L);
        player.sendMessage(Component.text("Cloud Core RTT: " + rttMillis + " ms"));
        getLogger().info("PONG from " + player.getName() + " rtt=" + rttMillis + "ms");
    }

    private void sendHelloAck(Player player) {
        HelloAck ack = new HelloAck(
                ProtocolVersion.MAJOR,
                ProtocolVersion.MINOR,
                SERVER_FEATURES,
                getServer().getName(),
                Limits.S2C.maxFrameBytes(),
                HandshakeCodec.COMPRESSION_THRESHOLD,
                "Cloud Core " + getPluginMeta().getVersion());
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.HELLO_ACK,
                HandshakeCodec.encodeHelloAck(ack, Limits.S2C)));
    }

    private void sendPing(Player player, Session session) {
        int nonce;
        do {
            nonce = random.nextInt();
        } while (nonce == 0);
        session.pingPending = true;
        session.pingNonce = nonce;
        session.pingSentNanos = System.nanoTime();
        Ping ping = new Ping(nonce, System.currentTimeMillis());
        sendFrame(player, new Frame(ProtocolVersion.MAJOR, 0, Opcode.PING,
                HandshakeCodec.encodePing(ping)));
    }

    private void sendFrame(Player player, Frame frame) {
        try {
            byte[] data = FrameCodec.encode(frame, Limits.S2C);
            player.sendPluginMessage(this, CHANNEL, data);
        } catch (ProtocolException exception) {
            getLogger().warning("Failed to encode opcode 0x" + Integer.toHexString(frame.opcode())
                    + " for " + player.getName() + ": " + exception.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PING_PERMISSION)) {
            sender.sendMessage(Component.text("No permission."));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can use this command."));
            return true;
        }
        if (args.length != 1 || !args[0].equalsIgnoreCase("ping")) {
            sender.sendMessage(Component.text("Usage: /" + label + " ping"));
            return true;
        }
        Session session = sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session());
        sendPing(player, session);
        sender.sendMessage(Component.text("Cloud Core PING sent."));
        return true;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Session removed = sessions.remove(event.getPlayer().getUniqueId());
        if (removed != null) {
            removed.assembler.clear();
        }
    }

    private static final class Session {
        private final FragmentAssembler assembler = new FragmentAssembler(Limits.C2S);
        private Hello hello;
        private boolean pingPending;
        private int pingNonce;
        private long pingSentNanos;
    }
}
