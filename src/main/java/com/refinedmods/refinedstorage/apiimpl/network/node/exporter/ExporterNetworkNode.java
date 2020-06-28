package com.refinedmods.refinedstorage.apiimpl.network.node.exporter;

import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.network.node.NetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.SlottedCraftingRequest;
import com.refinedmods.refinedstorage.inventory.fluid.FluidInventory;
import com.refinedmods.refinedstorage.inventory.item.BaseItemHandler;
import com.refinedmods.refinedstorage.inventory.item.UpgradeItemHandler;
import com.refinedmods.refinedstorage.inventory.listener.NetworkNodeFluidInventoryListener;
import com.refinedmods.refinedstorage.inventory.listener.NetworkNodeInventoryListener;
import com.refinedmods.refinedstorage.item.UpgradeItem;
import com.refinedmods.refinedstorage.tile.ExporterTile;
import com.refinedmods.refinedstorage.tile.config.IComparable;
import com.refinedmods.refinedstorage.tile.config.IType;
import com.refinedmods.refinedstorage.util.StackUtils;
import com.refinedmods.refinedstorage.util.WorldUtils;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fluids.FluidAttributes;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;

public class ExporterNetworkNode extends NetworkNode implements IComparable, IType {
    public static final ResourceLocation ID = new ResourceLocation(RS.ID, "exporter");

    private static final String NBT_COMPARE = "Compare";
    private static final String NBT_TYPE = "Type";
    private static final String NBT_FLUID_FILTERS = "FluidFilters";
    private static final String NBT_CRAFT_ONLY = "CraftOnly";

    private final BaseItemHandler itemFilters = new BaseItemHandler(9).addListener(new NetworkNodeInventoryListener(this));
    private final FluidInventory fluidFilters = new FluidInventory(9).addListener(new NetworkNodeFluidInventoryListener(this));

    private final UpgradeItemHandler upgrades = (UpgradeItemHandler) new UpgradeItemHandler(4, UpgradeItem.Type.SPEED, UpgradeItem.Type.CRAFTING, UpgradeItem.Type.STACK, UpgradeItem.Type.REGULATOR)
        .addListener(new NetworkNodeInventoryListener(this))
        .addListener((handler, slot, reading) -> {
            if (!reading && !getUpgrades().hasUpgrade(UpgradeItem.Type.REGULATOR)) {
                boolean changed = false;

                for (int i = 0; i < itemFilters.getSlots(); ++i) {
                    ItemStack filterSlot = itemFilters.getStackInSlot(i);

                    if (filterSlot.getCount() > 1) {
                        filterSlot.setCount(1);
                        changed = true;
                    }
                }

                for (int i = 0; i < fluidFilters.getSlots(); ++i) {
                    FluidStack filterSlot = fluidFilters.getFluid(i);

                    if (!filterSlot.isEmpty() && filterSlot.getAmount() != FluidAttributes.BUCKET_VOLUME) {
                        filterSlot.setAmount(FluidAttributes.BUCKET_VOLUME);
                        changed = true;
                    }
                }

                if (changed) {
                    markDirty();
                }
            }
        });

    private int compare = IComparer.COMPARE_NBT;
    private int type = IType.ITEMS;
    private boolean craftOnly = false;

    private int filterSlot;

    public ExporterNetworkNode(World world, BlockPos pos) {
        super(world, pos);
    }

    @Override
    public int getEnergyUsage() {
        return RS.SERVER_CONFIG.getExporter().getUsage() + upgrades.getEnergyUsage();
    }

    @Override
    public void update() {
        super.update();

        if (canUpdate() && ticks % upgrades.getSpeed() == 0) {
            if (type == IType.ITEMS) {
                updateItemMode();
            } else if (type == IType.FLUIDS) {
                updateFluidMode();
            }
        }
    }

    public IItemHandler getFacingItemHandler() {
        return WorldUtils.getItemHandler(getFacingTile(), getDirection().getOpposite());
    }

    public IFluidHandler getFacingFluidHandler() {
        return WorldUtils.getFluidHandler(getFacingTile(), getDirection().getOpposite());
    }

    private void updateFluidMode() {
        IFluidHandler handler = getFacingFluidHandler();

        if (handler != null) {
            while (filterSlot + 1 < fluidFilters.getSlots() && fluidFilters.getFluid(filterSlot).isEmpty()) {
                filterSlot++;
            }

            // We jump out of the loop above if we reach the maximum slot. If the maximum slot is empty,
            // we waste a tick with doing nothing because it's empty. Hence this check. If we are at the last slot
            // and it's empty, go back to slot 0.
            // We also handle if we exceeded the maximum slot in general.
            if ((filterSlot == fluidFilters.getSlots() - 1 && fluidFilters.getFluid(filterSlot).isEmpty()) || (filterSlot >= fluidFilters.getSlots())) {
                filterSlot = 0;
            }

            FluidStack slot = fluidFilters.getFluid(filterSlot);

            if (!slot.isEmpty()) {
                int stackSize = upgrades.getStackInteractCount();

                if (upgrades.hasUpgrade(UpgradeItem.Type.REGULATOR)) {
                    stackSize = getStackInteractCountForRegulatorUpgrade(handler, slot, stackSize);
                }

                if (stackSize > 0) {
                    if (upgrades.hasUpgrade(UpgradeItem.Type.CRAFTING) && craftOnly) {
                        ICraftingTask task = network.getCraftingManager().request(new SlottedCraftingRequest(this, filterSlot), slot, stackSize);

                        if (task != null) {
                            task.addOutputInterceptor(ExporterOutputInterceptor.forExporter(ItemStack.EMPTY, slot, this));
                        }
                    } else {
                        FluidStack took = network.extractFluid(slot, stackSize, compare, Action.SIMULATE);

                        if (took.isEmpty()) {
                            if (upgrades.hasUpgrade(UpgradeItem.Type.CRAFTING)) {
                                network.getCraftingManager().request(new SlottedCraftingRequest(this, filterSlot), slot, stackSize);
                            }
                        } else {
                            int filled = handler.fill(took, IFluidHandler.FluidAction.SIMULATE);

                            if (filled > 0) {
                                took = network.extractFluid(slot, filled, compare, Action.PERFORM);

                                handler.fill(took, IFluidHandler.FluidAction.EXECUTE);
                            }
                        }
                    }
                }
            }

            filterSlot++;
        }
    }

    public int getStackInteractCountForRegulatorUpgrade(IFluidHandler handler, FluidStack stack, int toExtract) {
        int found = 0;

        for (int i = 0; i < handler.getTanks(); i++) {
            FluidStack stackInConnectedHandler = handler.getFluidInTank(i);

            if (API.instance().getComparer().isEqual(stack, stackInConnectedHandler, compare)) {
                found += stackInConnectedHandler.getAmount();
            }
        }

        int needed = 0;

        for (int i = 0; i < fluidFilters.getSlots(); ++i) {
            if (API.instance().getComparer().isEqual(stack, fluidFilters.getFluid(i), IComparer.COMPARE_NBT)) {
                needed += fluidFilters.getFluid(i).getAmount();
            }
        }

        toExtract = Math.min(toExtract, needed - found);
        return toExtract;
    }

    private void updateItemMode() {
        IItemHandler handler = getFacingItemHandler();

        if (handler != null) {
            while (filterSlot + 1 < itemFilters.getSlots() && itemFilters.getStackInSlot(filterSlot).isEmpty()) {
                filterSlot++;
            }

            // We jump out of the loop above if we reach the maximum slot. If the maximum slot is empty,
            // we waste a tick with doing nothing because it's empty. Hence this check. If we are at the last slot
            // and it's empty, go back to slot 0.
            // We also handle if we exceeded the maximum slot in general.
            if ((filterSlot == itemFilters.getSlots() - 1 && itemFilters.getStackInSlot(filterSlot).isEmpty()) || (filterSlot >= itemFilters.getSlots())) {
                filterSlot = 0;
            }

            ItemStack slot = itemFilters.getStackInSlot(filterSlot);

            if (!slot.isEmpty()) {
                int stackSize = upgrades.getStackInteractCount();

                if (upgrades.hasUpgrade(UpgradeItem.Type.REGULATOR)) {
                    stackSize = getStackInteractCountForRegulatorUpgrade(handler, slot, stackSize);
                }

                if (stackSize > 0) {
                    if (upgrades.hasUpgrade(UpgradeItem.Type.CRAFTING) && craftOnly) {
                        ICraftingTask task = network.getCraftingManager().request(new SlottedCraftingRequest(this, filterSlot), slot, stackSize);

                        if (task != null) {
                            task.addOutputInterceptor(ExporterOutputInterceptor.forExporter(slot, FluidStack.EMPTY, this));
                        }
                    } else {
                        ItemStack took = network.extractItem(slot, Math.min(slot.getMaxStackSize(), stackSize), compare, Action.SIMULATE);

                        if (took.isEmpty()) {
                            if (upgrades.hasUpgrade(UpgradeItem.Type.CRAFTING)) {
                                network.getCraftingManager().request(new SlottedCraftingRequest(this, filterSlot), slot, stackSize);
                            }
                        } else {
                            ItemStack remainder = ItemHandlerHelper.insertItem(handler, took, true);

                            int correctedStackSize = took.getCount() - remainder.getCount();

                            if (correctedStackSize > 0) {
                                took = network.extractItem(slot, correctedStackSize, compare, Action.PERFORM);

                                ItemHandlerHelper.insertItem(handler, took, false);
                            }
                        }
                    }
                }
            }

            filterSlot++;
        }
    }

    public int getStackInteractCountForRegulatorUpgrade(IItemHandler handler, ItemStack slot, int stackSize) {
        int found = 0;

        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stackInConnectedHandler = handler.getStackInSlot(i);

            if (API.instance().getComparer().isEqual(slot, stackInConnectedHandler, compare)) {
                found += stackInConnectedHandler.getCount();
            }
        }

        int needed = 0;

        for (int i = 0; i < itemFilters.getSlots(); ++i) {
            if (API.instance().getComparer().isEqualNoQuantity(slot, itemFilters.getStackInSlot(i))) {
                needed += itemFilters.getStackInSlot(i).getCount();
            }
        }

        stackSize = Math.min(stackSize, needed - found);
        return stackSize;
    }

    @Override
    public int getCompare() {
        return compare;
    }

    @Override
    public void setCompare(int compare) {
        this.compare = compare;

        markDirty();
    }


    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public CompoundNBT write(CompoundNBT tag) {
        super.write(tag);

        StackUtils.writeItems(upgrades, 1, tag);

        return tag;
    }

    @Override
    public CompoundNBT writeConfiguration(CompoundNBT tag) {
        super.writeConfiguration(tag);

        tag.putInt(NBT_COMPARE, compare);
        tag.putInt(NBT_TYPE, type);
        tag.putBoolean(NBT_CRAFT_ONLY, craftOnly);

        StackUtils.writeItems(itemFilters, 0, tag);

        tag.put(NBT_FLUID_FILTERS, fluidFilters.writeToNbt());

        return tag;
    }

    @Override
    public void read(CompoundNBT tag) {
        super.read(tag);

        StackUtils.readItems(upgrades, 1, tag);
    }

    @Override
    public void readConfiguration(CompoundNBT tag) {
        super.readConfiguration(tag);

        if (tag.contains(NBT_COMPARE)) {
            compare = tag.getInt(NBT_COMPARE);
        }

        if (tag.contains(NBT_TYPE)) {
            type = tag.getInt(NBT_TYPE);
        }

        if (tag.contains(NBT_CRAFT_ONLY)) {
            craftOnly = tag.getBoolean(NBT_CRAFT_ONLY);
        }

        StackUtils.readItems(itemFilters, 0, tag);

        if (tag.contains(NBT_FLUID_FILTERS)) {
            fluidFilters.readFromNbt(tag.getCompound(NBT_FLUID_FILTERS));
        }
    }

    public UpgradeItemHandler getUpgrades() {
        return upgrades;
    }

    @Override
    public IItemHandler getDrops() {
        return upgrades;
    }

    public boolean isCraftOnly() {
        return craftOnly;
    }

    public void setCraftOnly(boolean craftOnly) {
        this.craftOnly = craftOnly;
    }

    @Override
    public int getType() {
        return world.isRemote ? ExporterTile.TYPE.getValue() : type;
    }

    @Override
    public void setType(int type) {
        this.type = type;

        markDirty();
    }

    @Override
    public IItemHandlerModifiable getItemFilters() {
        return itemFilters;
    }

    @Override
    public FluidInventory getFluidFilters() {
        return fluidFilters;
    }
}