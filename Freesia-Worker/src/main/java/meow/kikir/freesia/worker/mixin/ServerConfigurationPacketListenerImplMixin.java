package meow.kikir.freesia.worker.mixin;

import meow.kikir.freesia.worker.ServerLoader;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerConfigurationPacketListenerImpl.class)
public class ServerConfigurationPacketListenerImplMixin {
    @Redirect(method = "handleConfigurationFinished", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V"))
    private void handlePlaceNewPlayerInject(PlayerList instance, Connection connection, @NotNull ServerPlayer player, CommonListenerCookie cookie) {
        final int entityIdTryGet = ServerLoader.workerConnection.getPlayerEntityId(player.getUUID());

        // failed (removed during disconnection of m-session or fetch failed)
        // as we cannot do blocking here, fail-safe when the data is invalid could be the best way
        // m-session disconnect is really rare so this should be fine
        if (entityIdTryGet == Integer.MIN_VALUE) {
            connection.disconnect(Component.literal("Failed to fetch player entity ID from master controller service!"));
            // won't place the player
            return;
        }

        // synchronize the entity ID
        player.setId(entityIdTryGet);

        // original place player logic
        instance.placeNewPlayer(connection, player, cookie);
    }
}
