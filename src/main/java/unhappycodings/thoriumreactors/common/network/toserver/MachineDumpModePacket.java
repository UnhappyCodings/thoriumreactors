package unhappycodings.thoriumreactors.common.network.toserver;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import unhappycodings.thoriumreactors.common.blockentity.MachineElectrolyticSaltSeparatorBlockEntity;
import unhappycodings.thoriumreactors.common.network.PacketHandler;
import unhappycodings.thoriumreactors.common.network.base.IPacket;
import unhappycodings.thoriumreactors.common.network.toclient.MachineClientDumpModePacket;

public class MachineDumpModePacket implements IPacket {
    private final BlockPos pos;
    private final String tag;

    public MachineDumpModePacket(BlockPos pos, String tag) {
        this.pos = pos;
        this.tag = tag;
    }

    public static MachineDumpModePacket decode(FriendlyByteBuf buffer) {
        return new MachineDumpModePacket(buffer.readBlockPos(), buffer.readUtf());
    }

    @SuppressWarnings("ConstantConditions")
    public void handle(NetworkEvent.Context context) {
        ServerPlayer player = context.getSender();
        BlockEntity machine = player.getCommandSenderWorld().getBlockEntity(pos);
        if (!(machine instanceof MachineElectrolyticSaltSeparatorBlockEntity blockEntity)) return;
        boolean targetBool = false;
        if (tag.equals("input")) {
            blockEntity.setInputDump(!blockEntity.isInputDump());
            targetBool = blockEntity.isInputDump();
        }
        if (tag.equals("output")) {
            blockEntity.setOutputDump(!blockEntity.isOutputDump());
            targetBool = blockEntity.isOutputDump();
        }
        if (tag.equals("dumpInput")) blockEntity.setWaterIn(0);
        if (tag.equals("dumpOutput")) blockEntity.setWaterOut(0);

        PacketHandler.sendToClient(new MachineClientDumpModePacket(pos, tag, targetBool), player);
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeUtf(tag);
    }
}
