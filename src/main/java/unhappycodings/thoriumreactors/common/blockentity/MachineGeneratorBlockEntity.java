package unhappycodings.thoriumreactors.common.blockentity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import unhappycodings.thoriumreactors.common.block.MachineElectrolyticSaltSeparatorBlock;
import unhappycodings.thoriumreactors.common.block.MachineGeneratorBlock;
import unhappycodings.thoriumreactors.common.container.MachineGeneratorContainer;
import unhappycodings.thoriumreactors.common.energy.EnergyHandler;
import unhappycodings.thoriumreactors.common.energy.IEnergyCapable;
import unhappycodings.thoriumreactors.common.energy.ModEnergyStorage;
import unhappycodings.thoriumreactors.common.registration.ModBlockEntities;
import unhappycodings.thoriumreactors.common.registration.ModSounds;
import unhappycodings.thoriumreactors.common.util.EnergyUtil;

public class MachineGeneratorBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer, MenuProvider, IEnergyCapable {
    public static final int MAX_POWER = 75000;
    public static final int MAX_TRANSFER = 250;
    public static final int PRODUCTION = 140;

    private final LazyOptional<EnergyHandler>[] lazyEnergyHandler = EnergyHandler.createEnergyHandlers(this, Direction.values());
    private final LazyOptional<? extends IItemHandler>[] itemHandler = SidedInvWrapper.create(this, Direction.values());
    public NonNullList<ItemStack> items;
    int currentProduction = 0;
    int maxFuel = 1;
    int fuel = 0;

    public MachineGeneratorBlockEntity(BlockPos pPos, BlockState pBlockState) {
        super(ModBlockEntities.GENERATOR_BLOCK.get(), pPos, pBlockState);
        items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
    }

    private final ModEnergyStorage ENERGY_STORAGE = new ModEnergyStorage(MAX_POWER, MAX_TRANSFER) {
        @Override
        public void onEnergyChanged() {
            setChanged();
            energy = ENERGY_STORAGE.getEnergyStored();
        }
    };

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        Direction facing = this.getBlockState().getValue(MachineElectrolyticSaltSeparatorBlock.FACING);
        if (cap == ForgeCapabilities.ENERGY && supportsEnergy() && side != null && this.getBlockState().getValue(MachineGeneratorBlock.FACING).getOpposite() == side) {
            return lazyEnergyHandler[side.get3DDataValue()].cast();
        }
        if (cap == ForgeCapabilities.ITEM_HANDLER && !isRemoved() && side != null) {
            if (side == facing.getClockWise())
                return itemHandler[side.get3DDataValue()].cast();
            return LazyOptional.empty();
        }
        return super.getCapability(cap, side);
    }

    @Override
    public boolean canInputEnergy() {
        return false;
    }

    @Override
    public boolean canInputEnergy(Direction direction) {
        return false;
    }

    @Override
    public boolean canOutputEnergy() {
        return true;
    }

    @Override
    public boolean canOutputEnergy(Direction direction) {
        return this.getBlockState().getValue(MachineGeneratorBlock.FACING).getOpposite() == direction;
    }

    public void tick() {
        // Play Sounds
        if (getState() && getFuel() % 20 == 0) {
            this.level.playSound(null, getBlockPos(), ModSounds.MACHINE_GENERATOR.get(), SoundSource.BLOCKS, 0.18f,1f);
        }

        ItemStack input = getItem(getInputSlot());
        if (getFuel() <= 0 && ForgeHooks.getBurnTime(input, null) > 0 && getEnergy() != MAX_POWER) {
            addFuel(input);
            setMaxFuel(getFuel());
            input.shrink(1);
        }
        if (getFuel() > 0) {
            setCurrentProduction(PRODUCTION);
            if (!getState()) setState(true);
            if ((getEnergy() + PRODUCTION <= MAX_POWER))
                setEnergy(getEnergy() + getCurrentProduction());
            else if (getEnergy() > 0)
                setEnergy(MAX_POWER);
            setFuel(getFuel() - 1);
        } else {
            if (getState()) setState(false);
            currentProduction = 0;
        }
        items.get(1).getCapability(ForgeCapabilities.ENERGY).ifPresent(storage -> EnergyUtil.tryChargeItem(storage, ENERGY_STORAGE, getMaxOutput()));
        trySendToNeighbors(level, getBlockPos());
    }

    public void trySendToNeighbors(Level world, BlockPos pos) {
        for (Direction side : Direction.values()) {
            if (getEnergy() == 0)
                return;
            trySendTo(world, pos, side);
        }
    }

    public void trySendTo(Level world, BlockPos pos, Direction side) {
        BlockEntity tileEntity = world.getBlockEntity(pos.relative(side));
        if (tileEntity != null) {
            tileEntity.getCapability(ForgeCapabilities.ENERGY, side.getOpposite()).ifPresent(this::trySendEnergy);
        }
    }

    private void trySendEnergy(IEnergyStorage other) {
        if (other.canReceive()) {
            long toSend = ENERGY_STORAGE.extractEnergy(getMaxOutput(), true);
            int sent = other.receiveEnergy((int) toSend, false);
            if (sent > 0) ENERGY_STORAGE.extractEnergy(sent, false);
        }
    }

    public void addFuel(ItemStack stack) {
        setFuel(getFuel() + ForgeHooks.getBurnTime(stack, null));
    }

    public void setState(boolean state) {
        level.setBlock(getBlockPos(), getBlockState().setValue(MachineGeneratorBlock.POWERED, state), 3);
    }

    public boolean getState() {
        return getBlockState().getValue(MachineGeneratorBlock.POWERED);
    }

    public int getMaxFuel() {
        return maxFuel;
    }

    public void setMaxFuel(int maxFuel) {
        this.maxFuel = maxFuel;
    }

    public int getFuel() {
        return fuel;
    }

    public void setFuel(int fuel) {
        this.fuel = fuel;
    }

    @Override
    public void setEnergy(int energy) {
        ENERGY_STORAGE.setEnergy(energy);
    }

    public int getEnergy() {
        return ENERGY_STORAGE.getEnergyStored();
    }

    public int getCapacity() {
        return ENERGY_STORAGE.getMaxEnergyStored();
    }

    public int getMaxProduction() {
        return PRODUCTION;
    }

    public int getMaxOutput() {
        return MAX_TRANSFER;
    }

    public int getCurrentProduction() {
        return currentProduction;
    }

    public void setCurrentProduction(int currentProduction) {
        this.currentProduction = currentProduction;
    }

    public int getInputSlot() {
        return 0;
    }

    public boolean supportsEnergy() {
        return getEnergyCapacity() > 0;
    }

    @Override
    public void onDataPacket(Connection net, ClientboundBlockEntityDataPacket pkt) {
        if (getLevel().isClientSide && net.getDirection() == PacketFlow.CLIENTBOUND) handleUpdateTag(pkt.getTag());
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @NotNull
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag nbt = new CompoundTag();
        nbt.putInt("Fuel", getFuel());
        nbt.putInt("MaxFuel", getMaxFuel());
        nbt.putInt("Energy", getEnergy());
        nbt.putInt("Production", getCurrentProduction());
        return nbt;
    }

    @Override
    public void handleUpdateTag(final CompoundTag tag) {
        setFuel(tag.getInt("Fuel"));
        setMaxFuel(tag.getInt("MaxFuel"));
        setEnergy(tag.getInt("Energy"));
        setCurrentProduction(tag.getInt("Production"));
    }

    @Override
    public void saveAdditional(@NotNull CompoundTag nbt) {
        nbt.putInt("Fuel", getFuel());
        nbt.putInt("MaxFuel", getMaxFuel());
        nbt.putInt("Energy", getEnergy());
        nbt.putInt("Production", getCurrentProduction());
        ContainerHelper.saveAllItems(nbt, this.items, true);
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        this.items.clear();
        ContainerHelper.loadAllItems(nbt, this.items);
        setFuel(nbt.getInt("Fuel"));
        setMaxFuel(nbt.getInt("MaxFuel"));
        setEnergy(nbt.getInt("Energy"));
        setCurrentProduction(nbt.getInt("Production"));
    }

    @NotNull
    @Override
    protected Component getDefaultName() {
        return Component.translatable("block.thoriumreactors.generator_block");
    }

    @NotNull
    @Override
    protected AbstractContainerMenu createMenu(int pContainerId, @NotNull Inventory pInventory) {
        return new MachineGeneratorContainer(pContainerId, pInventory, getBlockPos(), getLevel(), getContainerSize());
    }

    @Override
    public int getContainerSize() {
        return 2;
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack itemStack : items) {
            if (itemStack.isEmpty()) return true;
        }
        return false;
    }

    @NotNull
    @Override
    public ItemStack getItem(int index) {
        if (index < 0 || index >= items.size()) {
            return ItemStack.EMPTY;
        }
        return items.get(index);
    }

    @NotNull
    @Override
    public ItemStack removeItem(int index, int count) {
        return ContainerHelper.removeItem(items, index, count);
    }

    @NotNull
    @Override
    public ItemStack removeItemNoUpdate(int index) {
        return ContainerHelper.takeItem(items, index);
    }

    @Override
    public void setItem(int index, @NotNull ItemStack stack) {
        items.set(index, stack);
        if (stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (this.level.getBlockEntity(this.worldPosition) != this) {
            return false;
        } else {
            return !(player.distanceToSqr((double) this.worldPosition.getX() + 0.5D, (double) this.worldPosition.getY() + 0.5D, (double) this.worldPosition.getZ() + 0.5D) > 64.0D);
        }
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    @Override
    public long getEnergyStored() {
        return ENERGY_STORAGE.getEnergyStored();
    }

    @Override
    public long getEnergyCapacity() {
        return MAX_POWER;
    }

    @Override
    public long getMaxEnergyTransfer() {
        return PRODUCTION;
    }

    @Override
    public int getEnergyDrain() {
        return 0;
    }

    @Override
    public long removeEnergy(long energy, boolean simulate) {
        return ENERGY_STORAGE.extractEnergy((int) energy, simulate);
    }

    @Override
    public long addEnergy(long energy, boolean simulate) {
        return ENERGY_STORAGE.receiveEnergy((int) energy, simulate);
    }

    @Override
    public void setCapacity(int capacity) {

    }

    @Override
    public int[] getSlotsForFace(Direction pSide) {
        return switch (pSide) {
            case NORTH, EAST, SOUTH, WEST -> new int[]{0};
            default -> new int[]{};
        };
    }

    @Override
    public boolean canPlaceItemThroughFace(int pIndex, ItemStack pItemStack, @Nullable Direction pDirection) {
        Direction facing = this.getBlockState().getValue(MachineElectrolyticSaltSeparatorBlock.FACING);
        return facing.getClockWise() == pDirection;
    }

    @Override
    public boolean canTakeItemThroughFace(int pIndex, ItemStack pStack, Direction pDirection) {
        return false;
    }
}
