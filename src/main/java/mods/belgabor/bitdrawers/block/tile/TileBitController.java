package mods.belgabor.bitdrawers.block.tile;

import com.jaquadro.minecraft.storagedrawers.StorageDrawers;
import com.jaquadro.minecraft.storagedrawers.api.security.ISecurityProvider;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawer;
import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerGroup;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.IProtectable;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.IVoidable;
import com.jaquadro.minecraft.storagedrawers.block.tile.TileEntityController;
import com.jaquadro.minecraft.storagedrawers.security.SecurityManager;
import com.jaquadro.minecraft.storagedrawers.util.ItemMetaListRegistry;
import com.mojang.authlib.GameProfile;
import mod.chiselsandbits.api.*;
import mod.chiselsandbits.core.api.BitAccess;
import mod.chiselsandbits.core.api.BitBrush;
import mod.chiselsandbits.helpers.ModUtil;
import mods.belgabor.bitdrawers.BitDrawers;
import mods.belgabor.bitdrawers.core.BDLogger;
import mods.belgabor.bitdrawers.core.BitHelper;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.ILockableContainer;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.*;

/**
 * Created by Belgabor on 24.07.2016.
 */
public class TileBitController extends TileEntityController implements IProtectable {
    protected Map<Integer, List<SlotRecord>> drawerBitLookup = new HashMap<>();
    private String securityKey;
    
    @Override
    public int interactPutItemsIntoInventory (EntityPlayer player) {
        int count = 0;

        ItemStack currentStack = player.inventory.getCurrentItem();
        
        if (!BitDrawers.config.allowBagMultiInsertion) {
            if (currentStack != null) {
                count = insertBagItems(currentStack, player.getGameProfile());
            }
            if (count < 0)
                count = 0;
        }
        
        count += super.interactPutItemsIntoInventory(player);

        if (!BitDrawers.config.allowChiseledBlockMultiInsertion) {
            currentStack = player.inventory.getCurrentItem();
            if (currentStack != null) {
                count = insertChiseledBlocks(currentStack, player.getGameProfile());
                if(currentStack.stackSize == 0) {
                    player.inventory.setInventorySlotContents(player.inventory.currentItem, (ItemStack)null);
                }
            }
            if (count < 0)
                count = 0;
        }
        
        return count;
    }

    @Override
    protected int insertItems(ItemStack stack, GameProfile profile) {
        if (stack == null)
            return 0;
        
        int count = -1;
        if (BitDrawers.config.debugTrace)
            BDLogger.info("TileBitController:insertItems %s", stack==null?"null":stack.getDisplayName());
        
        if (BitDrawers.config.allowBagMultiInsertion)
            count = insertBagItems(stack, profile);
        
        if (count < 0) {
            count = super.insertItems(stack, profile);
            
            if (stack.stackSize > 0 && BitDrawers.config.allowChiseledBlockMultiInsertion)
                count = insertChiseledBlocks(stack, profile);
        }
        

        return count>0?count:0;
    }
    
    protected int insertBagItems(ItemStack stack, GameProfile profile) {
        int count = 0;
        if (BitDrawers.config.debugTrace)
            BDLogger.info("TileBitController:insertBagItems %s", stack==null?"null":stack.getDisplayName());
        if (stack != null && stack.hasCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null)) {
            IItemHandler handler = stack.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
            for(int i = 0; i < handler.getSlots(); i++) {
                while (true) {
                    ItemStack extract = handler.extractItem(i, 64, true);
                    if (extract == null)
                        break;
                    int extracted = extract.stackSize;
                    int inserted = insertItems(extract, profile);
                    if (inserted > 0) {
                        count += inserted;
                        ItemStack test = handler.extractItem(i, inserted, false);
                        if (test.stackSize < inserted)
                            BDLogger.error("Could not extract simulated amount from bag. Something went very wrong.");
                    }
                    if (inserted < extracted)
                        break;
                }
            }

        } else {
            count = -1;
        }
        
        return count;
    }
    
    protected int insertChiseledBlocks(ItemStack stack, GameProfile profile) {
        if (BitDrawers.config.debugTrace)
            BDLogger.info("TileBitController:insertChiseledBlocks %s", stack==null?"null":stack.getDisplayName());
        if (stack == null)
            return 0;
        if (BitDrawers.cnb_api.getItemType(stack) == ItemType.CHISLED_BLOCK) {
            IBitAccess access = BitDrawers.cnb_api.createBitItem(stack);
            if (access == null)
                return 0;
            BitHelper.BitCounter counter = new BitHelper.BitCounter();
            access.visitBits(counter);
            List<BitCollectorData> data = null;
            
            try {
                data = BitCollectorData.collect(counter);
            } catch (APIExceptions.InvalidBitItem invalidBitItem) {
                BDLogger.error("Failed to create bit brush for stored bit");
                BDLogger.error(invalidBitItem);
                return 0;
            }
            
            data.stream().forEachOrdered(bitData -> {
                for (Integer slot : this.enumerateDrawersForInsertion(bitData.stack, true)) {
                    IDrawerGroup group = this.getGroupForDrawerSlot(slot);
                    if (!(group instanceof IProtectable) || SecurityManager.hasAccess(profile, (IProtectable) group)) {
                        IDrawer drawer = this.getDrawer(slot);
                        ItemStack itemProto = drawer.getStoredItemPrototype();
                        if (itemProto == null) {
                            break;
                        }

                        bitData.drawers.add(drawer);
                        if (bitData.canStore >= 0) {
                            if (drawer instanceof IVoidable && ((IVoidable) drawer).isVoid()) {
                                bitData.canStore = -1;
                            } else {
                                bitData.canStore += drawer.getRemainingCapacity();
                            }
                        }

                    }
                }
                
                bitData.calc();
            });
            
            OptionalInt test = data.stream().mapToInt(x -> x.canStoreItems).min();
            final int maxItems = Math.min(test.isPresent()?test.getAsInt():0, stack.stackSize);
            
            if (maxItems == 0) {
                if (BitDrawers.config.debugTrace)
                    BDLogger.info("TileBitController:insertChiseledBlocks No Space");
                return 0;
            }
            
            data.stream().forEachOrdered(bitData -> {
                bitData.toStore(maxItems);
                bitData.drawers.stream().forEachOrdered(drawer -> {
                    if (bitData.toStore > 0) {
                        int stored = insertItemsIntoDrawer(drawer, bitData.toStore);
                        if (drawer instanceof IVoidable && ((IVoidable) drawer).isVoid()) {
                            bitData.toStore = 0;
                        } else {
                            bitData.stored(stored);
                        }
                    }
                });
            });
            
            OptionalInt left = data.stream().mapToInt(x -> x.toStore).max();
            if (left.isPresent() && left.getAsInt() > 0) {
                BDLogger.error("Couldn't store bits when inserting chiseled block. This is not supposed to happen at this point.");
            }
            stack.stackSize -= maxItems;
            return maxItems;
        } else {
            return -1;
        }
    }

    @Override
    protected void resetCache() {
        if (BitDrawers.config.debugTrace)
            BDLogger.info("TileBitController:resetCache");
        drawerBitLookup.clear();
        super.resetCache();
    }

    @Override
    public void updateCache() {
        if (BitDrawers.config.debugTrace)
            BDLogger.info("TileBitController:updateCache");
        super.updateCache();
        rebuildBitLookup(drawerBitLookup, drawerSlotList);
    }
    
    protected void rebuildBitLookup (Map<Integer, List<SlotRecord>> lookup, List<SlotRecord> records) {
        lookup.clear();
        boolean invBased = false;

        for (int i = 0; i < records.size(); i++) {
            SlotRecord record = records.get(i);
            IDrawerGroup group = getGroupForSlotRecord(record);
            if (group == null)
                continue;

            int drawerSlot = (invBased) ? group.getDrawerInventory().getDrawerSlot(record.slot) : record.slot;
            if (!group.isDrawerEnabled(drawerSlot))
                continue;

            IDrawer drawer = group.getDrawer(drawerSlot);
            if (drawer.isEmpty())
                continue;

            ItemStack item = drawer.getStoredItemPrototype();
            if (BitDrawers.cnb_api.getItemType(item) == ItemType.CHISLED_BIT) {
                try {
                    IBitBrush brush = BitDrawers.cnb_api.createBrush(item);
                    if (BitDrawers.config.debugTrace)
                        BDLogger.info("Rebuilding: %s %d %d %d", item.getDisplayName(), record.slot, i, brush.getStateID());
                    List<SlotRecord> slotRecords = lookup.get(brush.getStateID());
                    if (slotRecords == null) {
                        slotRecords = new ArrayList<>();
                        lookup.put(brush.getStateID(), slotRecords);
                    }
                    slotRecords.add(record);
                } catch (APIExceptions.InvalidBitItem invalidBitItem) {}
            }
        }
        if (BitDrawers.config.debugTrace)
            BDLogger.info("Rebuilt: %d entries", lookup.size());
    }
    
    protected IDrawer getAccessibleBitDrawer(SlotRecord slotRecord, GameProfile profile) {
        if (slotRecord == null)
            return null;
        IDrawerGroup group = this.getGroupForSlotRecord(slotRecord);
        if (!(group instanceof IProtectable) || SecurityManager.hasAccess(profile, (IProtectable) group)) {
            return group.getDrawer(slotRecord.slot);
        }
        return null;
    }
    
    public int fillBag(IBitBag bag, GameProfile profile) {
        final Integer result[] = new Integer[1];
        result[0] = 0;
        if (BitDrawers.config.debugTrace)
            BDLogger.info("TileBitController.fillBag");
        
        drawerBitLookup.forEach((blockStateID, slotList) -> {
            try {
                ItemStack bit = BitDrawers.cnb_api.getBitItem(new BitBrush(blockStateID).getState());
                int addSlot = -1;
                for (int i = 0; i < bag.getSlots(); i++) {
                    ItemStack test = bag.getStackInSlot(i);
                    if (test == null) {
                        if (addSlot == -1)
                            addSlot = i;
                        continue;
                    }
                    if (test.stackSize < bag.getBitbagStackSize() && BitHelper.areItemsEqual(test, bit)) {
                        addSlot = i;
                        break;
                    }
                }
                final int doAddSlot = addSlot;
                
                if (addSlot > -1) {
                    slotList.stream().forEachOrdered(slotRecord -> {
                        IDrawer drawer = getAccessibleBitDrawer(slotRecord, profile);
                        if (drawer != null)
                            result[0] += fillBagSlot(bag, doAddSlot, drawer);
                    });
                }
            } catch (APIExceptions.InvalidBitItem invalidBitItem) {}
        });
        
        return result[0];
    }
    
    protected int fillBagSlot(IBitBag bag, int slot, IDrawer drawer) {
        if (BitDrawers.config.debugTrace)
            BDLogger.info("TileBitController:fillBagSlot %d %s", slot, drawer.getStoredItemPrototype()==null?"null":drawer.getStoredItemPrototype().getDisplayName());
        if (drawer.getStoredItemCount() == 0)
            return 0;
        
        int toAdd = bag.getBitbagStackSize();
        ItemStack item = bag.getStackInSlot(slot);
        if (item != null)
            toAdd -= item.stackSize;
        item = drawer.getStoredItemPrototype().copy();
        item.stackSize = toAdd;
        ItemStack test = bag.insertItem(slot, item.copy(), true);
        if (test != null) {
            toAdd -= test.stackSize;
        }
        if (toAdd == 0)
            return 0;
        
        toAdd = Math.min(toAdd, drawer.getStoredItemCount());
        drawer.setStoredItemCount(drawer.getStoredItemCount() - toAdd);
        item.stackSize = toAdd;
        test = bag.insertItem(slot, item, false);
        if (test != null) {
            if (test.stackSize != 0) {
                BDLogger.error("Could not insert simulated bit amount into bag. Something went very wrong.");
            }
        }

        return toAdd;
    }
    
    public ItemStack retrieveByPattern(ItemStack pattern, EntityPlayer player, boolean getStack) {
        if (BitDrawers.config.debugTrace)
            BDLogger.info("TileBitController:retrieveByPattern");
        IBitAccess source = BitDrawers.cnb_api.createBitItem(pattern);
        GameProfile profile = player.getGameProfile();
        
        //updateCache();
        
        if (source == null)
            return null;
        
        BitHelper.BitCounter counter = new BitHelper.BitCounter();
        source.visitBits(counter);
        
        if (BitDrawers.config.debugTrace)
            BDLogger.info("Lookup size: %d", drawerBitLookup.size());
        
        Map<Integer, Integer> available = new HashMap<>();
        counter.counts.forEach((blockStateID, count) -> {
            if (BitDrawers.config.debugTrace)
                BDLogger.info("Needed %d %d", blockStateID, count);
            available.put(blockStateID, 0);
            List<SlotRecord> records = drawerBitLookup.get(blockStateID);
            if (records == null)
                return;
            records.stream().forEachOrdered(slotRecord -> {
                if (BitDrawers.config.debugTrace)
                    BDLogger.info("  Trying %d", slotRecord.slot);
                IDrawer drawer = getAccessibleBitDrawer(slotRecord, profile);
                if (drawer != null) {
                    if (BitDrawers.config.debugTrace)
                        BDLogger.info("  Drawer found: %d", slotRecord.slot);
                    available.put(blockStateID, available.get(blockStateID) + drawer.getStoredItemCount());
                }
            });
        });
        
        Integer[] max = new Integer[1];
        max[0] = Integer.MAX_VALUE;
        available.forEach((blockStateID, count) -> {
            if (BitDrawers.config.debugTrace)
                BDLogger.info("Available %d %d", blockStateID, count);
            int m = count / counter.counts.get(blockStateID);
            if (m == 0 && BitDrawers.config.chatty) {
                ItemStack desc = (new BitBrush(blockStateID)).getItemStack(1);
                player.addChatComponentMessage(new TextComponentTranslation("chat.notEnough", desc==null?"Unknown":desc.getDisplayName()));
            }
            max[0] = Math.min(max[0], m);
        });
        
        int toExtract = Math.min(max[0], getStack?64:1);
        if (toExtract == 0)
            return null;

        ItemStack stack = source.getBitsAsItem(ModUtil.getSide(pattern), ItemType.CHISLED_BLOCK, false);
        stack.stackSize = toExtract;
        Integer[] temp = new Integer[1];

        counter.counts.forEach((blockStateID, count) -> {
            temp[0] = count * toExtract;
            List<SlotRecord> records = drawerBitLookup.get(blockStateID);
            if (records == null) {
                stack.stackSize = 0;
                return;
            }
            records.stream().forEachOrdered(slotRecord -> {
                IDrawer drawer = getAccessibleBitDrawer(slotRecord, profile);
                if (drawer != null) {
                    int ex = Math.min(temp[0], drawer.getStoredItemCount());
                    drawer.setStoredItemCount(drawer.getStoredItemCount() - ex);
                    temp[0] -= ex;
                }
            });
            if (temp[0] > 0)
                stack.stackSize = 0;
        });
        
        if (stack.stackSize == 0) {
            BDLogger.error("Could not extract simulated bit for block. Something went very wrong.");
            return null;
        }
        return stack;
    }
    
    @Override
    public void readFromNBT (NBTTagCompound tag) {
        super.readFromNBT(tag);

        securityKey = null;
        if (tag.hasKey("Sec"))
            securityKey = tag.getString("Sec");

    }

    @Override
    public NBTTagCompound writeToNBT (NBTTagCompound tag) {
        super.writeToNBT(tag);

        if (securityKey != null)
            tag.setString("Sec", securityKey);
        
        return tag;
    }

    @Override
    public UUID getOwner() {
        return null;
    }

    @Override
    public boolean setOwner(UUID uuid) {
        return false;
    }

    @Override
    public ISecurityProvider getSecurityProvider() {
        return StorageDrawers.securityRegistry.getProvider(securityKey);
    }

    @Override
    public ILockableContainer getLockableContainer() {
        return null;
    }

    @Override
    public boolean setSecurityProvider(ISecurityProvider provider) {
        if (!StorageDrawers.config.cache.enablePersonalUpgrades)
            return false;

        String newKey = (provider == null) ? null : provider.getProviderID();
        if ((newKey != null && !newKey.equals(securityKey)) || (securityKey != null && !securityKey.equals(newKey))) {
            securityKey = newKey;

            if (worldObj != null && !worldObj.isRemote) {
                markDirty();

                IBlockState state = worldObj.getBlockState(getPos());
                worldObj.notifyBlockUpdate(getPos(), state, state, 3);
            }
        }

        return true;
    }

    protected static class BitCollectorData {
        protected final int count;
        protected final IBitBrush brush;
        protected final ItemStack stack;
        public int canStore = 0;
        public final List<IDrawer> drawers = new ArrayList<>();
        public int canStoreItems = 0;
        public int toStore = 0;

        public BitCollectorData(int blockStateID, int count) throws APIExceptions.InvalidBitItem {
            this.count = count;
            this.brush = new BitBrush(blockStateID);
            this.stack = BitDrawers.cnb_api.getBitItem(brush.getState());
        }
        
        public void calc() {
            if (canStore == -1)
                canStoreItems = Integer.MAX_VALUE;
            else
                canStoreItems = canStore / count;
        }
        
        public int toStore(int items) {
            toStore = items * count;
            return toStore;
        }
        
        public void stored(int stored) {
            toStore -= stored;
        }
        
        public static List<BitCollectorData> collect(BitHelper.BitCounter counter) throws APIExceptions.InvalidBitItem {
            final List<BitCollectorData> list = new ArrayList<BitCollectorData>();
            
            counter.counts.forEach((blockStateID, count) -> {
                try {
                    list.add(new BitCollectorData(blockStateID, count));
                } catch (APIExceptions.InvalidBitItem invalidBitItem) {}
            });
            
            if (list.size() != counter.counts.size())
                throw new APIExceptions.InvalidBitItem();
            
            return list;
        }
    }
    

}
