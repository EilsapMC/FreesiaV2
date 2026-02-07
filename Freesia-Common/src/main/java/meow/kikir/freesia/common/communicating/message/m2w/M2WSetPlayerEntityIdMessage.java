package meow.kikir.freesia.common.communicating.message.m2w;

import io.netty.buffer.ByteBuf;
import meow.kikir.freesia.common.communicating.handler.NettyClientChannelHandlerLayer;
import meow.kikir.freesia.common.communicating.message.IMessage;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class M2WSetPlayerEntityIdMessage implements IMessage<NettyClientChannelHandlerLayer> {
    private UUID targetPlayer;
    private int entityId;

    public M2WSetPlayerEntityIdMessage() {}

    public M2WSetPlayerEntityIdMessage(UUID targetPlayer, int entityId) {
        this.targetPlayer = targetPlayer;
        this.entityId = entityId;
    }

    @Override
    public void writeMessageData(@NotNull ByteBuf buffer) {
        buffer.writeLong(this.targetPlayer.getMostSignificantBits());
        buffer.writeLong(this.targetPlayer.getLeastSignificantBits());
        buffer.writeInt(this.entityId);
    }

    @Override
    public void readMessageData(@NotNull ByteBuf buffer) {
        long mostSigBits = buffer.readLong();
        long leastSigBits = buffer.readLong();
        this.targetPlayer = new UUID(mostSigBits, leastSigBits);

        this.entityId = buffer.readInt();
    }

    @Override
    public void process(@NotNull NettyClientChannelHandlerLayer handler) {
        handler.handleSetPlayerEntityId(this.targetPlayer, this.entityId);
    }
}
