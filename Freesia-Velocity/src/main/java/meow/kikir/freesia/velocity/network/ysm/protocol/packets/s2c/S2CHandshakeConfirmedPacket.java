package meow.kikir.freesia.velocity.network.ysm.protocol.packets.s2c;

import meow.kikir.freesia.velocity.Freesia;
import meow.kikir.freesia.velocity.network.ysm.MapperSessionProcessor;
import meow.kikir.freesia.velocity.network.ysm.ProxyComputeResult;
import meow.kikir.freesia.velocity.network.ysm.protocol.YsmPacket;
import meow.kikir.freesia.velocity.utils.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public class S2CHandshakeConfirmedPacket implements YsmPacket {
    private String serverYsmVersion;

    @Override
    public void encode(@NotNull FriendlyByteBuf output) {
        output.writeUtf(this.serverYsmVersion);
    }

    @Override
    public void decode(@NotNull FriendlyByteBuf input) {
        this.serverYsmVersion = input.readUtf();
    }

    @Override
    public ProxyComputeResult handle(@NotNull MapperSessionProcessor handler) {
        Freesia.LOGGER.info("Replying ysm client with server version {}.", this.serverYsmVersion);

        return ProxyComputeResult.ofPass();
    }
}
