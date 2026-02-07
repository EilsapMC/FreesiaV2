package meow.kikir.freesia.common.communicating.message.w2m;

import io.netty.buffer.ByteBuf;
import meow.kikir.freesia.common.communicating.handler.NettyServerChannelHandlerLayer;
import meow.kikir.freesia.common.communicating.message.IMessage;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class W2MRequestPlayerEntityIdMessage implements IMessage<NettyServerChannelHandlerLayer> {
    private UUID targetPlayer;

    public W2MRequestPlayerEntityIdMessage() {}

    public W2MRequestPlayerEntityIdMessage(UUID targetPlayer) {
        this.targetPlayer = targetPlayer;
    }

    @Override
    public void writeMessageData(@NotNull ByteBuf buffer) {
        buffer.writeLong(this.targetPlayer.getMostSignificantBits());
        buffer.writeLong(this.targetPlayer.getLeastSignificantBits());
    }

    @Override
    public void readMessageData(@NotNull ByteBuf buffer) {
        long mostSigBits = buffer.readLong();
        long leastSigBits = buffer.readLong();
        this.targetPlayer = new UUID(mostSigBits, leastSigBits);
    }

    @Override
    public void process(@NotNull NettyServerChannelHandlerLayer handler) {
        handler.handlePlayerEntityIdRequest(this.targetPlayer);
    }
}
