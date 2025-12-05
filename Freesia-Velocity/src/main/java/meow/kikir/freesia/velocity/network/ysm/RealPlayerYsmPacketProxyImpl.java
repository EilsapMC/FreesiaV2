package meow.kikir.freesia.velocity.network.ysm;

import com.velocitypowered.api.proxy.Player;
import meow.kikir.freesia.velocity.Freesia;
import meow.kikir.freesia.velocity.network.ysm.protocol.EnumPacketDirection;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacket;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacketCodec;
import meow.kikir.freesia.velocity.utils.FriendlyByteBuf;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.key.Key;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class RealPlayerYsmPacketProxyImpl extends YsmPacketProxyLayer{

    public RealPlayerYsmPacketProxyImpl(Player player) {
        super(player);
    }

    @Override
    public CompletableFuture<Set<UUID>> fetchTrackerList(UUID observer) {
        return Freesia.tracker.getCanSee(observer);
    }

    @Override
    public ProxyComputeResult processS2C(Key key, ByteBuf copiedPacketData) {
        final FriendlyByteBuf mcBuffer = new FriendlyByteBuf(copiedPacketData);
        final YsmPacket decoded = YsmPacketCodec.tryDecode(mcBuffer, EnumPacketDirection.S2C);

        if (decoded != null) {
            return decoded.handle(this.handler);
        }

        return ProxyComputeResult.ofPass();
    }

    @Override
    public ProxyComputeResult processC2S(Key key, ByteBuf copiedPacketData) {
        final FriendlyByteBuf mcBuffer = new FriendlyByteBuf(copiedPacketData);
        final YsmPacket decoded = YsmPacketCodec.tryDecode(mcBuffer, EnumPacketDirection.C2S);

        if (decoded != null) {
            return decoded.handle(this.handler);
        }

        return ProxyComputeResult.ofPass();
    }
}
