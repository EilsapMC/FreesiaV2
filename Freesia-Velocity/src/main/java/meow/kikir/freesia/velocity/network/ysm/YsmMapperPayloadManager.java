package meow.kikir.freesia.velocity.network.ysm;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import meow.kikir.freesia.velocity.FreesiaConstants;
import meow.kikir.freesia.velocity.Freesia;
import meow.kikir.freesia.velocity.FreesiaConfig;
import meow.kikir.freesia.velocity.YsmProtocolMetaFile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.BuiltinFlags;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

public class YsmMapperPayloadManager {
    // Ysm channel key name
    public static final Key YSM_CHANNEL_KEY_ADVENTURE = Key.key(YsmProtocolMetaFile.getYsmChannelNamespace() + ":" + YsmProtocolMetaFile.getYsmChannelPath());
    public static final MinecraftChannelIdentifier YSM_CHANNEL_KEY_VELOCITY = MinecraftChannelIdentifier.create(YsmProtocolMetaFile.getYsmChannelNamespace(), YsmProtocolMetaFile.getYsmChannelPath());

    // Player to worker mappers connections
    private final Map<Player, MapperSessionProcessor> mapperSessions = Maps.newConcurrentMap();
    private final Map<Integer, MapperSessionProcessor> backendId2Mapper = Maps.newConcurrentMap();
    private final Map<Integer, MapperSessionProcessor> workerId2Mapper = Maps.newConcurrentMap();
    // Real player proxy factory
    private final Function<Player, YsmPacketProxy> packetProxyCreator;

    // Backend connect infos
    private final ReadWriteLock workerMsessionIpsAccessLock = new ReentrantReadWriteLock(false);
    private final Map<InetSocketAddress, Integer> workerIp2Players = Maps.newLinkedHashMap();

    // The players who installed ysm(Used for packet sending reduction)
    private final Set<UUID> ysmInstalledPlayers = Sets.newConcurrentHashSet();

    public YsmMapperPayloadManager(Function<Player, YsmPacketProxy> packetProxyCreator) {
        this.packetProxyCreator = packetProxyCreator;
        for (InetSocketAddress singleWorkerMsessionAddress : FreesiaConfig.workerMessionAddresses) {
            this.workerIp2Players.put(singleWorkerMsessionAddress, 0);
        }
    }

    public void decreaseWorkerSessionCount(InetSocketAddress worker) {
        this.workerMsessionIpsAccessLock.writeLock().lock();
        try {
            final Integer old = this.workerIp2Players.get(worker);
            if (old == null) {
                Freesia.LOGGER.warn("Trying to decrease session count for unregisted worker msession address: {}!", worker);
                return;
            }

            this.workerIp2Players.put(worker, Math.max(0, old - 1));
        }finally {
            this.workerMsessionIpsAccessLock.writeLock().unlock();
        }
    }

    public void increaseWorkerSessionCount(InetSocketAddress worker) {
        this.workerMsessionIpsAccessLock.writeLock().lock();
        try {
            final Integer old = this.workerIp2Players.get(worker);
            if (old == null) {
                Freesia.LOGGER.warn("Trying to increase session count for unregisted worker msession address: {}!", worker);
                return;
            }

            this.workerIp2Players.put(worker, old + 1);
        }finally {
            this.workerMsessionIpsAccessLock.writeLock().unlock();
        }
    }

    public void onClientYsmHandshakePacketReply(@NotNull Player target) {
        this.ysmInstalledPlayers.add(target.getUniqueId());
    }

    public void updateWorkerPlayerEntityId(Player target, int entityId){
        final MapperSessionProcessor mapper = this.mapperSessions.get(target);

        if (mapper == null) {
            throw new IllegalStateException("Mapper not created yet!");
        }

        mapper.getPacketProxy().setPlayerWorkerEntityId(entityId);

        this.workerId2Mapper.put(entityId, mapper);
    }

    public void updateRealPlayerEntityId(Player target, int entityId){
        final MapperSessionProcessor mapper = this.mapperSessions.get(target);

        if (mapper == null) {
            throw new IllegalStateException("Mapper not created yet!");
        }

        mapper.getPacketProxy().setPlayerEntityId(entityId);

        this.backendId2Mapper.put(entityId, mapper);
    }

    private void disconnectMapperWithoutKickingMaster(@NotNull MapperSessionProcessor connection) {
        connection.setKickMasterWhenDisconnect(false);
        connection.destroyAndAwaitDisconnected();
    }

    public MapperSessionProcessor sessionProcessorByEntityId(int entityId) {
        return this.backendId2Mapper.get(entityId);
    }

    public MapperSessionProcessor sessionProcessorByWorkerEntityId(int workerEntityId) {
        return this.workerId2Mapper.get(workerEntityId);
    }

    public void autoCreateMapper(Player player) {
        this.createMapperSession(player, Objects.requireNonNull(this.selectLessPlayer()));
    }

    public boolean isPlayerInstalledYsm(@NotNull Player target) {
        return this.ysmInstalledPlayers.contains(target.getUniqueId());
    }

    public boolean isPlayerInstalledYsm(UUID target) {
        return this.ysmInstalledPlayers.contains(target);
    }

    public void onPlayerDisconnect(@NotNull Player player) {
        this.ysmInstalledPlayers.remove(player.getUniqueId());

        final MapperSessionProcessor mapperSession = this.mapperSessions.remove(player);

        if (mapperSession != null) {
            this.disconnectMapperWithoutKickingMaster(mapperSession);
        }
    }

    protected void onWorkerSessionDisconnect(@NotNull MapperSessionProcessor mapperSession, boolean kickMaster, @Nullable Component reason) {
        // Kick the master it binds
        if (kickMaster)
            mapperSession.getBindPlayer().disconnect(Freesia.languageManager.i18n(
                    FreesiaConstants.LanguageConstants.WORKER_TERMINATED_CONNECTION,
                    List.of("reason"),
                    List.of(reason == null ? Component.text("DISCONNECTED MANUAL") : reason)
            ));

        // Remove from list
        this.mapperSessions.remove(mapperSession.getBindPlayer());

        // remove entity id mappings (backend)
        final int backendEntityId = mapperSession.getPacketProxy().getPlayerEntityId();
        if (backendEntityId != -1) {
            this.backendId2Mapper.remove(backendEntityId);
        }

        // remove entity id mappings (worker)
        final int workerEntityId = mapperSession.getPacketProxy().getPlayerWorkerEntityId();
        if (workerEntityId != -1) {
            this.workerId2Mapper.remove(workerEntityId);
        }

        final InetSocketAddress workerAddress = mapperSession.getWorkerAddress();
        if (workerAddress != null) {
            this.decreaseWorkerSessionCount(workerAddress);
        }
    }

    public void onPluginMessageIn(@NotNull Player player, @NotNull MinecraftChannelIdentifier channel, byte[] packetData) {
        // Check if it is the message of ysm
        if (!channel.equals(YSM_CHANNEL_KEY_VELOCITY)) {
            return;
        }

        final MapperSessionProcessor mapperSession = this.mapperSessions.get(player);

        if (mapperSession == null) {
            // Actually it shouldn't be and never be happened
            throw new IllegalStateException("Mapper session not found or ready for player " + player.getUsername());
        }

        mapperSession.processPlayerPluginMessage(packetData);
    }

    public void onBackendReady(Player player) {
        final MapperSessionProcessor mapperSession = this.mapperSessions.get(player);

        if (mapperSession == null) {
            //race condition: already disconnected
            return;
        }

        mapperSession.onBackendReady();
    }

    public boolean disconnectAlreadyConnected(Player player) {
        final MapperSessionProcessor current = this.mapperSessions.get(player);

        // Not exists or created
        if (current == null) {
            return false;
        }

        // Will do remove in the callback
        this.disconnectMapperWithoutKickingMaster(current);
        return true;
    }

    public void initMapperPacketProcessor(@NotNull Player player) {
        final MapperSessionProcessor possiblyExisting = this.mapperSessions.get(player);

        if (possiblyExisting != null) {
            throw new IllegalStateException("Mapper session already exists for player " + player.getUsername());
        }

        final YsmPacketProxy packetProxy = this.packetProxyCreator.apply(player);
        final MapperSessionProcessor processor = new MapperSessionProcessor(player, packetProxy, this);

        packetProxy.setParentHandler(processor);

        this.mapperSessions.put(player, processor);
    }

    public void createMapperSession(@NotNull Player player, @NotNull InetSocketAddress backend) {
        // Instance new session
        final TcpClientSession mapperSession = new TcpClientSession(
                backend.getHostName(),
                backend.getPort(),
                new MinecraftProtocol(
                        new GameProfile(
                                player.getUniqueId(),
                                player.getUsername()),
                        null
                )
        );

        // Our packet processor for packet forwarding
        final MapperSessionProcessor packetProcessor = this.mapperSessions.get(player);

        if (packetProcessor == null) {
            // Should be created in ServerPreConnectEvent
            throw new IllegalStateException("Mapper session not found or ready for player " + player.getUsername());
        }

        packetProcessor.setSession(mapperSession);
        mapperSession.addListener(packetProcessor);

        // Default as Minecraft client
        mapperSession.setFlag(BuiltinFlags.READ_TIMEOUT,30_000);
        mapperSession.setFlag(BuiltinFlags.WRITE_TIMEOUT,30_000);

        packetProcessor.setWorkerAddress(backend);

        // Do connect
        mapperSession.connect(true,false);

        this.increaseWorkerSessionCount(backend);
    }

    public void onRealPlayerTrackerUpdate(Player beingWatched, Player watcher) {
        final MapperSessionProcessor mapperSession = this.mapperSessions.get(beingWatched);

        // The mapper was created earlier than the player's connection turned in-game state
        // so as the result, we could simply pass it down directly
        if (mapperSession == null) {
            // We use random player as the payload of custom payload of freesia tracker, so there is a possibility
            // that race condition would happen between the disconnect logic and tracker update logic
            return;
        }

        // Skip players who don't install ysm
        if (this.isPlayerInstalledYsm(watcher)) {
            // Check if ready
            if (!mapperSession.queueTrackerUpdate(watcher.getUniqueId())) {
                mapperSession.getPacketProxy().sendFullEntityDataTo(watcher);
            }
        }
    }

    @Nullable
    private InetSocketAddress selectLessPlayer() {
        this.workerMsessionIpsAccessLock.readLock().lock();
        try {
            InetSocketAddress result = null;

            int idx = 0;
            int lastCount = 0;
            for (Map.Entry<InetSocketAddress, Integer> entry : this.workerIp2Players.entrySet()) {
                final InetSocketAddress currAddress = entry.getKey();
                final int currPlayerCount = entry.getValue();

                if (idx == 0) {
                    lastCount = currPlayerCount;
                    result = currAddress;
                }

                if (currPlayerCount < lastCount) {
                    lastCount = currPlayerCount;
                    result = currAddress;
                }

                idx++;
            }

            return result;
        } finally {
            this.workerMsessionIpsAccessLock.readLock().unlock();
        }
    }
}
