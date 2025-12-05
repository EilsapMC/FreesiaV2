package meow.kikir.freesia.velocity.network.ysm.protocol;

import meow.kikir.freesia.velocity.network.ysm.MapperSessionProcessor;
import meow.kikir.freesia.velocity.network.ysm.ProxyComputeResult;
import meow.kikir.freesia.velocity.utils.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

public interface YsmPacket {
    void encode(@NotNull FriendlyByteBuf output);

    void decode(@NotNull FriendlyByteBuf input);

    ProxyComputeResult handle(@NotNull MapperSessionProcessor handler);
}
