package meow.kikir.freesia.worker.mixin;

import com.mojang.authlib.GameProfile;
import meow.kikir.freesia.common.EntryPoint;
import meow.kikir.freesia.worker.ServerLoader;
import meow.kikir.freesia.worker.impl.WorkerMessageHandlerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.network.ServerLoginPacketListenerImpl;
import org.apache.commons.logging.Log;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;

@Mixin(ServerLoginPacketListenerImpl.class)
public abstract class ServerLoginPacketListenerImplMixin {

    @Shadow
    @Nullable
    String requestedUsername;

    @Shadow
    abstract void startClientVerification(GameProfile gameProfile);

    @Shadow
    @Final
    Connection connection;

    @Unique
    private void terminateConnectionForEntityIdSyncFailed(@NotNull GameProfile requestedProfile) {
        EntryPoint.LOGGER_INST.warn("Player entity ID fetch failed for player {}({}).", requestedProfile.getName(), requestedProfile.getId());
        this.connection.disconnect(Component.literal("Failed to fetch player entity ID from master controller service!"));
    }

    /**
     * @author MrHua269
     * @reason Kill UUID checks and preload player data
     */
    @Overwrite
    public void handleHello(@NotNull ServerboundHelloPacket serverboundHelloPacket) {
        this.requestedUsername = serverboundHelloPacket.name();
        final GameProfile requestedProfile = new GameProfile(serverboundHelloPacket.profileId(), this.requestedUsername);

        if (ServerLoader.clientInstance == null || !ServerLoader.clientInstance.isReady()) {
            // however, we are doing this on netty thread, so there no need to force it back to the eventloop again
            this.connection.disconnect(Component.literal("Worker not ready yet!"));
            return;
        }

        // value copy for mt safety
        final WorkerMessageHandlerImpl currConnection = ServerLoader.workerConnection;

        currConnection.sendIdentity(requestedProfile.getId());
        currConnection.ensureLinkedMM(requestedProfile.getId(), () -> {
            //Preload it to prevent load it blocking
            EntryPoint.LOGGER_INST.info("Fetching player data for player {}.", requestedProfile.getName());
            final boolean entityIdRequestQueued = ServerLoader.workerConnection.fetchPlayerEntityId(requestedProfile.getId(), entityId -> {
                // note: Integer.MIN_VALUE -> fetch failed (retired when player removed or m-session disconnected during synchronization)
                if (entityId == Integer.MIN_VALUE) {
                    EntryPoint.LOGGER_INST.warn("Player entity ID fetch failed for player {}({}).", requestedProfile.getName(), requestedProfile.getId());
                    this.terminateConnectionForEntityIdSyncFailed(requestedProfile);
                } else {
                    // won't do anything as we don't use it until the next stage
                    EntryPoint.LOGGER_INST.info("Fetched player entity ID {} for player {}.", entityId, requestedProfile.getName());
                }
            });

            // failed, might be m-session disconnected.
            if (!entityIdRequestQueued) {
                EntryPoint.LOGGER_INST.warn("Player entity ID fetch queue failed for player {}({}).", requestedProfile.getName(), requestedProfile.getId());
                this.terminateConnectionForEntityIdSyncFailed(requestedProfile);
                return;
            }

            // request for the player data for only the part of ysm not all data
            ServerLoader.workerConnection.getPlayerData(requestedProfile.getId(), data -> {
                if (data != null) {
                    ServerLoader.playerDataCache.put(requestedProfile.getId(), data);
                }

                EntryPoint.LOGGER_INST.info("Pre-loaded player data for player {}.", requestedProfile.getName());
                this.startClientVerification(requestedProfile); //Continue login process
            });
        });
    }
}
