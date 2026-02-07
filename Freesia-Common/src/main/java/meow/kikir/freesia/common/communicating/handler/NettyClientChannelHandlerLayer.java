package meow.kikir.freesia.common.communicating.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import meow.kikir.freesia.common.EntryPoint;
import meow.kikir.freesia.common.communicating.NettySocketClient;
import meow.kikir.freesia.common.communicating.file.FileDispatchDesc;
import meow.kikir.freesia.common.communicating.message.IMessage;
import meow.kikir.freesia.common.communicating.message.w2m.W2MFileTransformationAckPacket;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public abstract class NettyClientChannelHandlerLayer extends SimpleChannelInboundHandler<IMessage<NettyClientChannelHandlerLayer>> {
    private final Map<Integer, FileDispatchDesc> fileTransformationChannels = new ConcurrentHashMap<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, IMessage<NettyClientChannelHandlerLayer> msg) {
        try {
            msg.process(this);
        } catch (Exception e) {
            EntryPoint.LOGGER_INST.error("Failed to process packet! ", e);
        }
    }

    @Override
    public void channelActive(@NotNull ChannelHandlerContext ctx) {
        this.getClient().resetReadyFlag();
        this.getClient().onChannelActive(ctx.channel());
    }

    @Override
    public void channelInactive(@NotNull ChannelHandlerContext ctx) {
        this.getClient().resetReadyFlag();
        this.getClient().onChannelInactive();
        this.dropAllTransferringFiles();
    }

    public abstract void handleSetPlayerEntityId(UUID targetPlayer, int entityId);

    public abstract NettySocketClient getClient();

    public abstract void onMasterPlayerDataResponse(int traceId, byte[] content);

    public abstract CompletableFuture<String> dispatchCommand(String command);

    public abstract void handleReadyNotification();

    public abstract void callYsmModelReload();

    protected void cleanModelFolder(Path @NotNull [] folders) {
        for (Path singleModelFolder : folders) {
            try {
                if (Files.exists(singleModelFolder)) {
                    Files.walkFileTree(singleModelFolder, new SimpleFileVisitor<>() {
                        @Override
                        @NotNull
                        public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) throws IOException {
                            Files.delete(file);
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        @NotNull
                        public FileVisitResult postVisitDirectory(@NotNull Path dir, IOException exc) throws IOException {
                            Files.delete(dir);
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            } catch (IOException e) {
                EntryPoint.LOGGER_INST.error("Failed to clean model folder: {}", singleModelFolder, e);
            }
        }
    }

    private void dropAllTransferringFiles() {
        for (Map.Entry<Integer, FileDispatchDesc> entry : this.fileTransformationChannels.entrySet()) {
            try {
                final FileDispatchDesc dispatchDesc = entry.getValue();

                this.dropSingleTransferringFile(dispatchDesc);
            } catch (IOException e) {
                EntryPoint.LOGGER_INST.error("Failed to close file channel for trace id {}", entry.getKey(), e);
            }
        }
        this.fileTransformationChannels.clear();
    }

    private void dropSingleTransferringFile(@NotNull FileDispatchDesc fdd) throws IOException {
        fdd.channel().close();
        Files.deleteIfExists(fdd.path());
    }

    public void handleFileTransformation(int traceId, int ack, int tAck, int beginOffset, @NotNull Path target, byte[] data) {
        final Path tmpPath = target.resolveSibling(target.getFileName() + ".tmp");
        FileDispatchDesc output = this.fileTransformationChannels.get(traceId);

        if (tAck == -1) {
            output = this.fileTransformationChannels.remove(traceId);
            // should not be happened as the first ack is not -1
            if (output == null) {
                EntryPoint.LOGGER_INST.warn("Received file transformation chunk finalization for unknown trace id {}", traceId);
                return;
            }

            // close channel as the transformation is completed
            try {
                output.channel().close();
            } catch (IOException e) {
                EntryPoint.LOGGER_INST.error("Failed to close file channel for trace id {}, file: {}", traceId, output, e);
            }

            // we are done now, move it to the final file
            try {
                Files.move(tmpPath,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            }catch (Throwable ex) {
                try {
                    Files.move(tmpPath,
                            target,
                            StandardCopyOption.REPLACE_EXISTING
                    );
                }catch (Throwable ex1) {
                    ex.addSuppressed(ex1);
                }

                // all move attempts failed, try to delete the tmp file
                try {
                    Files.deleteIfExists(tmpPath);
                }catch (Throwable ex2) {
                    ex.addSuppressed(ex2);
                }

                // throw err and let it pass through to the channel handler
                // then reconnect to resync
                throw new RuntimeException(ex);
            }
            return;
        }

        if (output == null) {
            try {
                tmpPath.getParent().toFile().mkdirs(); // mkdirs for the file

                output = new FileDispatchDesc(
                        tmpPath,
                        FileChannel.open(
                                tmpPath,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                                StandardOpenOption.WRITE
                        )
                );
                this.fileTransformationChannels.put(traceId, output);
            } catch (Throwable ex) {
                EntryPoint.LOGGER_INST.error("Failed to open file channel for trace id {}, path: {}", traceId, target, ex);
                return;
            }
        }

        try {
            long offset = beginOffset;
            final ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) {
                offset += output.channel().write(buffer, offset);
            }

            this.getClient().sendToMaster(new W2MFileTransformationAckPacket(traceId, ack));
            EntryPoint.LOGGER_INST.info("Wrote file transformation chunk for path {} at offset {} (ack {}/{})", target, beginOffset, ack, tAck - 1);
        }catch (Throwable ex) {
            EntryPoint.LOGGER_INST.error("Failed to write file transformation chunk for path {} at offset {}", target, beginOffset, ex);

            try {
                FileDispatchDesc removed = this.fileTransformationChannels.remove(traceId);
                if (removed != null) {
                    this.dropSingleTransferringFile(removed);
                }
            } catch (IOException e) {
                // this shouldn't happen until we got some error(might the disk etc.)
                // we will throw exception, and then it could be passed through the pipeline then we could close the worker's connection
                // and reconnect then resync
                // this is only used for some resource files
                // so it could be acceptable as we are just passing the data on loopback
                throw new RuntimeException(e);
            }
        }
    }

    public abstract void handleIdentifyAck(UUID playerUUID);
}
