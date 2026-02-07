package meow.kikir.freesia.velocity.network.backend;

import ca.spottedleaf.concurrentutil.collection.MultiThreadedQueue;
import com.google.common.collect.Maps;
import com.velocitypowered.api.proxy.Player;
import io.netty.channel.ChannelHandlerContext;
import meow.kikir.freesia.common.EntryPoint;
import meow.kikir.freesia.common.communicating.handler.NettyServerChannelHandlerLayer;
import meow.kikir.freesia.common.communicating.message.m2w.M2WDispatchCommandMessage;
import meow.kikir.freesia.common.communicating.message.m2w.M2WSetPlayerEntityIdMessage;
import meow.kikir.freesia.common.communicating.message.m2w.M2WWorkerIdentifyAcknowledgedMessage;
import meow.kikir.freesia.velocity.Freesia;
import meow.kikir.freesia.velocity.FreesiaConstants;
import meow.kikir.freesia.velocity.events.PlayerEntityDataLoadEvent;
import meow.kikir.freesia.velocity.events.PlayerEntityDataStoreEvent;
import meow.kikir.freesia.velocity.events.WorkerConnectedEvent;
import meow.kikir.freesia.velocity.network.ysm.MapperSessionProcessor;
import meow.kikir.freesia.velocity.network.ysm.YsmPacketProxyLayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;
import java.util.function.Consumer;

public class MasterServerMessageHandler extends NettyServerChannelHandlerLayer {
    private final Map<Integer, Consumer<String>> pendingCommandDispatches = Maps.newConcurrentMap();
    private final AtomicInteger traceIdGenerator = new AtomicInteger(0);

    private boolean commandDispatcherRetired = false;
    private final StampedLock commandDispatchCallbackLock = new StampedLock();
    private final MultiThreadedQueue<MapperSessionProcessor> linkedMapperSessions = new MultiThreadedQueue<>();

    public void dispatchCommandToWorker(String command, Consumer<Component> onDispatched) {
        final long stamp = this.commandDispatchCallbackLock.readLock();
        try {
            // We were retired during connection
            if (this.commandDispatcherRetired) {
                onDispatched.accept(null);
                return;
            }

            final int traceId = this.traceIdGenerator.getAndIncrement();

            final Consumer<String> wrappedDecoder = json -> {
                try {
                    // We were retired during disconnection
                    if (json == null) {
                        onDispatched.accept(null);
                        return;
                    }

                    final Component decoded = LegacyComponentSerializer.builder().build().deserialize(json);
                    onDispatched.accept(decoded);
                } catch (Exception e) {
                    EntryPoint.LOGGER_INST.error("Failed to decode command result from worker", e);
                    onDispatched.accept(null);
                }
            };

            this.pendingCommandDispatches.put(traceId, wrappedDecoder);
            this.sendMessage(new M2WDispatchCommandMessage(traceId, command));
        }finally {
            this.commandDispatchCallbackLock.unlockRead(stamp);
        }
    }

    @Nullable
    public UUID getWorkerUUID() {
        return this.workerUUID;
    }

    @Nullable

    public String getWorkerName() {
        return this.workerName;
    }

    @Override
    public void channelInactive(@NotNull ChannelHandlerContext ctx) {
        this.destroyAllLinkedMappers();
        this.retireAllCommandDispatchCallbacks();

        if (this.workerUUID == null) {
            return;
        }

        Freesia.registedWorkers.remove(this.workerUUID);
    }

    public void destroyAllLinkedMappers() {
        MapperSessionProcessor toDestroy;
        while ((toDestroy = this.linkedMapperSessions.pollOrBlockAdds()) != null) {
            toDestroy.destroy();
        }
    }

    @Override
    public Map<Path, Path> collectModelFiles() {
        final Path baseDir = FreesiaConstants.FileConstants.PLUGIN_DIR.toPath().resolve("models");

        final Map<Path, Path> collected = new LinkedHashMap<>();
        for (String subFolders : FreesiaConstants.FileConstants.MODEL_FOLDERS_SUB) {
            final Path currBaseDir = baseDir.resolve(subFolders);
            final File currFile = currBaseDir.toFile();
            currFile.mkdirs();

            try {
                Files.walkFileTree(currBaseDir, new FileVisitor<>() {
                    @Override
                    public @NotNull FileVisitResult preVisitDirectory(Path dir, @NotNull BasicFileAttributes attrs) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public @NotNull FileVisitResult visitFile(Path file, @NotNull BasicFileAttributes attrs) {
                        final Path relativePath = FreesiaConstants.FileConstants.YSM_MODELS_BASE_DIR.resolve(baseDir.relativize(file));

                        if (Files.isDirectory(relativePath)) {
                            return FileVisitResult.CONTINUE;
                        }

                        collected.put(file, relativePath);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public @NotNull FileVisitResult visitFileFailed(Path file, @NotNull IOException exc) {
                        EntryPoint.LOGGER_INST.warn("Failed to visit model file: {}", file, exc);
                        MasterServerMessageHandler.this.modelSyncFailed(exc);
                        return FileVisitResult.TERMINATE;
                    }

                    @Override
                    public @NotNull FileVisitResult postVisitDirectory(Path dir, @Nullable IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }catch (Throwable th) {
                this.modelSyncFailed(th);
            }
        }

        return collected;
    }

    @Override
    public void handleWorkerIdentify(UUID playerUUID, UUID workerUUID) {
        final Player target = Freesia.PROXY_SERVER.getPlayer(playerUUID).orElse(null); // do the damn lookup

        if (target == null) {
            // wait what?
            throw new IllegalStateException("Requesting identify for an unexisting player!");
        }

        final MapperSessionProcessor mapperSessionProcessor = Freesia.mapperManager.sessionProcessorByPlayer(target);

        if (mapperSessionProcessor == null) {
            // wait what?
            throw new IllegalStateException("Requesting identify for a player without mapper session processor!");
        }

        if (!workerUUID.equals(this.workerUUID)) {
            throw new IllegalStateException("Worker UUID mismatch during identify process!");
        }

        EntryPoint.LOGGER_INST.info("Worker {} identified for player {}({}) to worker {} pointed in pkt", workerUUID, target.getUsername(), target.getUniqueId(), workerUUID);

        mapperSessionProcessor.linkToMasterHandler(this);

        // resume the connection process
        this.sendMessage(new M2WWorkerIdentifyAcknowledgedMessage(playerUUID));
    }

    private void modelSyncFailed(Throwable throwable) {
        this.getChannel().disconnect();
        EntryPoint.LOGGER_INST.error("Failed to collect model files!", throwable);
        throw new RuntimeException(throwable);
    }

    private void retireAllCommandDispatchCallbacks() {
        final long stamp = this.commandDispatchCallbackLock.writeLock();
        try {
            this.commandDispatcherRetired = true;
            for (Map.Entry<Integer, Consumer<String>> entry : this.pendingCommandDispatches.entrySet()) {
                entry.getValue().accept(null);
            }

            this.pendingCommandDispatches.clear();
        }finally {
            this.commandDispatchCallbackLock.unlockWrite(stamp);
        }
    }

    @Override
    public CompletableFuture<byte[]> readPlayerData(UUID playerUUID) {
        final CompletableFuture<byte[]> callback = new CompletableFuture<>();
        Freesia.realPlayerDataStorageManager
                .loadPlayerData(playerUUID)
                .thenApply(data -> Freesia.PROXY_SERVER
                        .getEventManager()
                        .fire(new PlayerEntityDataLoadEvent(playerUUID, data))
                        .thenApply(PlayerEntityDataLoadEvent::getSerializedNbtData)
                        .whenComplete((result, ex) -> {
                            if (ex != null) {
                                callback.completeExceptionally(ex);
                                return;
                            }

                            callback.complete(result);
                        })
                );
        return callback;
    }

    @Override
    public CompletableFuture<Void> savePlayerData(UUID playerUUID, byte[] content) {
        final CompletableFuture<Void> callback = new CompletableFuture<>();

        Freesia.PROXY_SERVER
                .getEventManager()
                .fire(new PlayerEntityDataStoreEvent(playerUUID, content))
                .thenAccept(event -> {
                    Freesia.realPlayerDataStorageManager.save(playerUUID, event.getSerializedNbtData())
                            .whenComplete((res, ex) -> {
                                if (ex != null) {
                                    callback.completeExceptionally(ex);
                                    return;
                                }

                                callback.complete(res);
                            });
                });

        return callback;
    }

    @Override
    public void onCommandDispatchResult(int traceId, @Nullable String result) {
        final Consumer<String> removedDecoder = this.pendingCommandDispatches.remove(traceId);

        if (removedDecoder != null) {
            removedDecoder.accept(result);
        }
    }

    @Override
    public void handlePlayerEntityIdRequest(UUID playerUUID) {
        final Player target = Freesia.PROXY_SERVER.getPlayer(playerUUID).orElse(null); // do the damn lookup

        // no exist
        if (target == null) {
            // still need to replay as we need to retire the callbacks
            Freesia.LOGGER.warn("Player entity ID request for unexisting player {}", playerUUID);
            this.sendMessage(new M2WSetPlayerEntityIdMessage(playerUUID, Integer.MIN_VALUE));
            return;
        }

        final MapperSessionProcessor mapperSessionProcessor = Freesia.mapperManager.sessionProcessorByPlayer(target);
        final YsmPacketProxyLayer possibleExistingProxy = mapperSessionProcessor == null ? null : (YsmPacketProxyLayer) mapperSessionProcessor.getPacketProxy();

        if (possibleExistingProxy == null) {
            // ???
            Freesia.LOGGER.warn("Player entity ID request for player {}({}) without existing YSM proxy", target.getUsername(), playerUUID);
            this.sendMessage(new M2WSetPlayerEntityIdMessage(playerUUID, Integer.MIN_VALUE));
            return;
        }

        final int tryGet = possibleExistingProxy.getPlayerEntityId();

        if (tryGet == -1) {
            // not ready but also won't care as the proxy ready will send it when it gets ready
            return;
        }

        this.sendMessage(new M2WSetPlayerEntityIdMessage(playerUUID, tryGet));
    }

    public void sendEntityIdToWorker(UUID playerUUID, int entityId) {
        this.sendMessage(new M2WSetPlayerEntityIdMessage(playerUUID, entityId));
    }

    @Override
    public void onWorkerInfoGet(UUID workerUUID, String workerName) {
        Freesia.registedWorkers.put(workerUUID, this);

        Freesia.PROXY_SERVER.getEventManager().fire(new WorkerConnectedEvent(workerUUID, workerName));
    }

    public boolean addLinkedMapperSession(@NotNull MapperSessionProcessor mapperSessionProcessor) {
        return this.linkedMapperSessions.offer(mapperSessionProcessor);
    }

    public void unlinkMapperSession(@NotNull MapperSessionProcessor mapperSessionProcessor) {
        this.linkedMapperSessions.remove(mapperSessionProcessor);
    }
}
