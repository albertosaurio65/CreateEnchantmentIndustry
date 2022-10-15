package plus.dragons.createenchantmentindustry.content.contraptions.enchantments;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.contraptions.processing.burner.BlazeBurnerBlock;
import com.simibubi.create.content.contraptions.relays.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.tileEntity.SmartTileEntity;
import com.simibubi.create.foundation.tileEntity.TileEntityBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.belt.DirectBeltInputBehaviour;
import com.simibubi.create.foundation.tileEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.utility.*;
import com.simibubi.create.foundation.utility.animation.LerpedFloat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import plus.dragons.createenchantmentindustry.entry.ModFluids;
import plus.dragons.createenchantmentindustry.foundation.data.advancement.ModAdvancements;
import plus.dragons.createenchantmentindustry.foundation.utility.ModLang;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class BlazeEnchanterBlockEntity extends SmartTileEntity implements IHaveGoggleInformation {

    public static final int ENCHANTING_TIME = 200;
    SmartFluidTankBehaviour internalTank;
    TransportedItemStack heldItem;
    ItemStack targetItem = ItemStack.EMPTY;
    int processingTicks;
    Map<Direction, LazyOptional<EnchantingItemHandler>> itemHandlers;
    boolean sendParticles;
    LerpedFloat headAnimation;
    LerpedFloat headAngle;
    Random random = new Random();
    float flip;
    float oFlip;
    float flipT;
    float flipA;
    public boolean goggles;

    public BlazeEnchanterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        itemHandlers = new IdentityHashMap<>();
        for (Direction d : Iterate.horizontalDirections) {
            EnchantingItemHandler enchantingItemHandler = new EnchantingItemHandler(this, d);
            itemHandlers.put(d, LazyOptional.of(() -> enchantingItemHandler));
        }
        headAnimation = LerpedFloat.linear();
        headAngle = LerpedFloat.angular();
        headAngle.startWithValue((AngleHelper
            .horizontalAngle(state.getOptionalValue(BlazeEnchanterBlock.FACING)
            .orElse(Direction.SOUTH)) + 180) % 360
        );
        goggles = false;
    }

    @Override
    public void addBehaviours(List<TileEntityBehaviour> behaviours) {
        behaviours.add(new DirectBeltInputBehaviour(this).allowingBeltFunnels()
                .setInsertionHandler(this::tryInsertingFromSide));
        behaviours.add(internalTank = SmartFluidTankBehaviour.single(this, 3000).whenFluidUpdates(() -> {
            var fluid = internalTank.getPrimaryHandler().getFluid().getFluid();
            if(ModFluids.EXPERIENCE.is(fluid))
                updateHeatLevel(BlazeEnchanterBlock.HeatLevel.KINDLED);
            else if(ModFluids.HYPER_EXPERIENCE.is(fluid))
                updateHeatLevel(BlazeEnchanterBlock.HeatLevel.SEETHING);
            else
                updateHeatLevel(BlazeEnchanterBlock.HeatLevel.SMOULDERING);
        }));
        registerAwardables(behaviours,
                ModAdvancements.FIRST_ORDER.asCreateAdvancement(),
                ModAdvancements.ADDITIONAL_ORDER.asCreateAdvancement());
    }

    @Override
    public void tick() {
        super.tick();

        boolean onClient = level.isClientSide && !isVirtual();
        
        if(onClient) {
            bookTick();
            blazeTick();
        }

        if (EnchantingGuideItem.getEnchantment(targetItem) == null) {
            if (!onClient) {
                level.setBlockAndUpdate(getBlockPos(), AllBlocks.BLAZE_BURNER.getDefaultState().setValue(BlazeBurnerBlock.HEAT_LEVEL, BlazeBurnerBlock.HeatLevel.SMOULDERING));
                return;
            }
        }

        if (heldItem == null) {
            processingTicks = 0;
            return;
        }


        if (processingTicks > 0) {
            heldItem.prevBeltPosition = .5f;
            boolean wasAtBeginning = processingTicks == ENCHANTING_TIME;
            if (!onClient || processingTicks < ENCHANTING_TIME)
                processingTicks--;
            if (!continueProcessing()) {
                processingTicks = 0;
                notifyUpdate();
                return;
            }
            // Interesting Trigger Sync Design
            if (wasAtBeginning != (processingTicks == ENCHANTING_TIME))
                sendData();
            // A return here
            return;
        }

        heldItem.prevBeltPosition = heldItem.beltPosition;
        heldItem.prevSideOffset = heldItem.sideOffset;

        heldItem.beltPosition += itemMovementPerTick();
        if (heldItem.beltPosition > 1) {
            heldItem.beltPosition = 1;

            if (onClient)
                return;

            Direction side = heldItem.insertedFrom;

            /* DirectBeltInputBehaviour#tryExportingToBeltFunnel(ItemStack, Direction, boolean) return null to
             * represent insertion is invalid due to invalidity
             * of funnel (excludes funnel being powered) or something go wrong. */
            ItemStack tryExportingToBeltFunnel = getBehaviour(DirectBeltInputBehaviour.TYPE)
                    .tryExportingToBeltFunnel(heldItem.stack, side.getOpposite(), false);
            if (tryExportingToBeltFunnel != null) {
                if (tryExportingToBeltFunnel.getCount() != heldItem.stack.getCount()) {
                    if (tryExportingToBeltFunnel.isEmpty())
                        heldItem = null;
                    else
                        heldItem.stack = tryExportingToBeltFunnel;
                    notifyUpdate();
                    return;
                }
                if (!tryExportingToBeltFunnel.isEmpty())
                    return;
            }

            BlockPos nextPosition = worldPosition.relative(side);
            DirectBeltInputBehaviour directBeltInputBehaviour =
                    TileEntityBehaviour.get(level, nextPosition, DirectBeltInputBehaviour.TYPE);
            if (directBeltInputBehaviour == null) {
                if (!BlockHelper.hasBlockSolidSide(level.getBlockState(nextPosition), level, nextPosition,
                        side.getOpposite())) {
                    ItemStack ejected = heldItem.stack;
                    // Following "Launching out" process can be used as standard.
                    Vec3 outPos = VecHelper.getCenterOf(worldPosition)
                            .add(Vec3.atLowerCornerOf(side.getNormal())
                                    .scale(.75));
                    float movementSpeed = itemMovementPerTick();
                    Vec3 outMotion = Vec3.atLowerCornerOf(side.getNormal())
                            .scale(movementSpeed)
                            .add(0, 1 / 8f, 0);
                    outPos.add(outMotion.normalize());
                    ItemEntity entity = new ItemEntity(level, outPos.x, outPos.y + 6 / 16f, outPos.z, ejected);
                    entity.setDeltaMovement(outMotion);
                    entity.setDefaultPickUpDelay();
                    entity.hurtMarked = true;
                    level.addFreshEntity(entity);

                    heldItem = null;
                    notifyUpdate();
                }
                return;
            }

            if (!directBeltInputBehaviour.canInsertFromSide(side))
                return;

            ItemStack returned = directBeltInputBehaviour.handleInsertion(heldItem.copy(), side, false);

            if (returned.isEmpty()) {
                heldItem = null;
                notifyUpdate();
                return;
            }

            if (returned.getCount() != heldItem.stack.getCount()) {
                heldItem.stack = returned;
                notifyUpdate();
                return;
            }

            return;
        }

        if (heldItem.prevBeltPosition < .5f && heldItem.beltPosition >= .5f) {
            if (!Enchanting.valid(heldItem.stack, targetItem, hyper()))
                return;
            heldItem.beltPosition = .5f;
            if (onClient)
                return;
            processingTicks = ENCHANTING_TIME;
            sendData();
        }

    }
    
    protected void blazeTick() {
        boolean active = processingTicks > 0;
    
        if (!active) {
            float target = 0;
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null && !player.isInvisible()) {
                double x;
                double z;
                if (isVirtual()) {
                    x = -4;
                    z = -10;
                } else {
                    x = player.getX();
                    z = player.getZ();
                }
                double dx = x - (getBlockPos().getX() + 0.5);
                double dz = z - (getBlockPos().getZ() + 0.5);
                target = AngleHelper.deg(-Mth.atan2(dz, dx)) - 90;
            }
            target = headAngle.getValue() + AngleHelper.getShortestAngleDiff(headAngle.getValue(), target);
            headAngle.chase(target, .25f, LerpedFloat.Chaser.exp(5));
            headAngle.tickChaser();
        } else {
            headAngle.chase((AngleHelper.horizontalAngle(getBlockState().getOptionalValue(BlazeEnchanterBlock.FACING)
                .orElse(Direction.SOUTH)) + 180) % 360, .125f, LerpedFloat.Chaser.EXP);
            headAngle.tickChaser();
        }
        headAnimation.chase(1, .25f, LerpedFloat.Chaser.exp(.25f));
        headAnimation.tickChaser();
        
        spawnBlazeParticles();
    }
    
    protected void bookTick() {
        if(random.nextInt(40) == 0) {
            float oFlipT = flipT;
            while(oFlipT == flipT) {
                flipT += (random.nextInt(4) - random.nextInt(4));
            }
        }
        oFlip = flip;
        float flipDiff = (flipT - flip) * 0.4F;
        flipDiff = Mth.clamp(flipDiff, -0.2F, 0.2F);
        flipA += (flipDiff - flipA) * 0.9F;
        flip += flipA;
    }
    
    protected void spawnBlazeParticles() {
        if (level == null)
            return;
        BlazeEnchanterBlock.HeatLevel heatLevel = getBlockState().getValue(BlazeEnchanterBlock.HEAT_LEVEL);
    
        Random r = level.getRandom();
    
        Vec3 c = VecHelper.getCenterOf(worldPosition);
        Vec3 v = c.add(VecHelper.offsetRandomly(Vec3.ZERO, r, .125f)
            .multiply(1, 0, 1));
    
        if (r.nextInt(3) == 0)
            level.addParticle(ParticleTypes.LARGE_SMOKE, v.x, v.y, v.z, 0, 0, 0);
        if (r.nextInt(2) != 0)
            return;
    
        boolean empty = level.getBlockState(worldPosition.above())
            .getCollisionShape(level, worldPosition.above())
            .isEmpty();
    
        double yMotion = empty ? .0625f : r.nextDouble() * .0125f;
        Vec3 v2 = c.add(VecHelper.offsetRandomly(Vec3.ZERO, r, .5f)
                .multiply(1, .25f, 1)
                .normalize()
                .scale((empty ? .25f : .5) + r.nextDouble() * .125f))
            .add(0, .5, 0);
    
        if (heatLevel.isAtLeast(BlazeEnchanterBlock.HeatLevel.SEETHING)) {
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, v2.x, v2.y, v2.z, 0, yMotion, 0);
        } else if (heatLevel.isAtLeast(BlazeEnchanterBlock.HeatLevel.KINDLED)) {
            level.addParticle(ParticleTypes.FLAME, v2.x, v2.y, v2.z, 0, yMotion, 0);
        }
    }
    
    protected static int ENCHANT_PARTICLE_COUNT = 20;
    
    protected void spawnEnchantParticles() {
        if (isVirtual())
            return;
        Vec3 vec = VecHelper.getCenterOf(worldPosition);
        vec = vec.add(0, 1, 0);
        ParticleOptions particle = ParticleTypes.ENCHANT;
        for (int i = 0; i < ENCHANT_PARTICLE_COUNT; i++) {
            Vec3 m = VecHelper.offsetRandomly(Vec3.ZERO, level.random, 1f);
            m = new Vec3(m.x, Math.abs(m.y), m.z);
            level.addAlwaysVisibleParticle(particle, vec.x, vec.y, vec.z, m.x, m.y, m.z);
        }
    }

    protected boolean continueProcessing() {
        if (level.isClientSide && !isVirtual())
            return true;
        if (processingTicks < 5)
            return true;
        if (!Enchanting.valid(heldItem.stack, targetItem, hyper()))
            return false;

        Pair<FluidStack, ItemStack> enchantItem = Enchanting.enchant(heldItem.stack, targetItem, true, hyper());
        FluidStack fluidFromItem = enchantItem.getFirst();
        
        if (processingTicks > 5) {
            if (processingTicks % 40 == 0) {
                level.playSound(null, worldPosition, SoundEvents.BEACON_AMBIENT, SoundSource.BLOCKS, 1.0f, 1.0f);
            }
            var tankFluid = internalTank.getPrimaryHandler().getFluid().getFluid();
            if ((!ModFluids.EXPERIENCE.is(tankFluid) && !ModFluids.HYPER_EXPERIENCE.is(tankFluid) ||
                internalTank.getPrimaryHandler().getFluidAmount() < fluidFromItem.getAmount())) {
                processingTicks = ENCHANTING_TIME;
            }
            return true;
        }

        // Advancement
        if(EnchantmentHelper.getEnchantments(heldItem.stack).isEmpty())
            award(ModAdvancements.FIRST_ORDER.asCreateAdvancement());
        else
            award(ModAdvancements.ADDITIONAL_ORDER.asCreateAdvancement());

        // Process finished
        enchantItem = Enchanting.enchant(heldItem.stack, targetItem, true, hyper());
        heldItem.stack = enchantItem.getSecond();
        internalTank.getPrimaryHandler().getFluid().shrink(fluidFromItem.getAmount());
        sendParticles = true;
        level.playSound(null, worldPosition, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, ENCHANTING_TIME / (float) processingTicks, 1.0f);
        notifyUpdate();
        return true;
    }

    private float itemMovementPerTick() {
        return 1 / 8f;
    }

    public void setTargetItem(ItemStack itemStack) {
        targetItem = itemStack;
    }

    private ItemStack tryInsertingFromSide(TransportedItemStack transportedStack, Direction side, boolean simulate) {
        ItemStack inserted = transportedStack.stack;
        ItemStack returned = ItemStack.EMPTY;

        if (!getHeldItemStack().isEmpty())
            return inserted;

        if (inserted.getCount() > 1 && Enchanting.valid(targetItem, inserted, hyper())) {
            returned = ItemHandlerHelper.copyStackWithSize(inserted, inserted.getCount() - 1);
            inserted = ItemHandlerHelper.copyStackWithSize(inserted, 1);
        }

        if (simulate)
            return returned;

        transportedStack = transportedStack.copy();
        transportedStack.stack = inserted.copy();
        transportedStack.beltPosition = side.getAxis()
                .isVertical() ? .5f : 0;
        transportedStack.prevSideOffset = transportedStack.sideOffset;
        transportedStack.prevBeltPosition = transportedStack.beltPosition;
        setHeldItem(transportedStack, side);
        setChanged();
        sendData();

        return returned;
    }

    public ItemStack getHeldItemStack() {
        return heldItem == null ? ItemStack.EMPTY : heldItem.stack;
    }

    public void setHeldItem(TransportedItemStack heldItem, Direction insertedFrom) {
        this.heldItem = heldItem;
        this.heldItem.insertedFrom = insertedFrom;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        for (LazyOptional<EnchantingItemHandler> lazyOptional : itemHandlers.values())
            lazyOptional.invalidate();
    }

    public boolean hyper() {
        return ModFluids.HYPER_EXPERIENCE.is(internalTank.getPrimaryHandler().getFluid().getFluid());
    }

    @Override
    public void write(CompoundTag compoundTag, boolean clientPacket) {
        super.write(compoundTag, clientPacket);
        compoundTag.putInt("ProcessingTicks", processingTicks);
        compoundTag.put("TargetItem", targetItem.serializeNBT());
        compoundTag.putBoolean("Goggles", goggles);
        if (heldItem != null)
            compoundTag.put("HeldItem", heldItem.serializeNBT());
        if (sendParticles && clientPacket) {
            compoundTag.putBoolean("SpawnParticles", true);
            sendParticles = false;
        }
    }

    @Override
    protected void read(CompoundTag compoundTag, boolean clientPacket) {
        super.read(compoundTag, clientPacket);
        heldItem = null;
        processingTicks = compoundTag.getInt("ProcessingTicks");
        targetItem = ItemStack.of(compoundTag.getCompound("TargetItem"));
        goggles = compoundTag.getBoolean("Goggles");
        if (compoundTag.contains("HeldItem"))
            heldItem = TransportedItemStack.read(compoundTag.getCompound("HeldItem"));
        if (!clientPacket)
            return;
        if (compoundTag.contains("SpawnParticles"))
            spawnEnchantParticles();
    }

    @Override
    @NotNull
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (side != null && side.getAxis()
                .isHorizontal() && isItemHandlerCap(capability))
            return itemHandlers.get(side)
                    .cast();

        if ((side == Direction.DOWN || side == null) && isFluidHandlerCap(capability))
            return internalTank.getCapability()
                    .cast();
        return super.getCapability(capability, side);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        ModLang.translate("gui.goggles.blaze_enchanter").forGoggles(tooltip);
        Pair<Enchantment, Integer> ei;
        if (targetItem != null && (ei = EnchantingGuideItem.getEnchantment(targetItem)) != null) {
            tooltip.add(new TextComponent("     ").append(ei.getFirst().getFullname(ei.getSecond() + (hyper()? 1 : 0))));
        }
        containedFluidTooltip(tooltip, isPlayerSneaking, getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY));
        return true;
    }

    public void updateHeatLevel(BlazeEnchanterBlock.HeatLevel heatLevel) {
        if (level != null)
            level.setBlockAndUpdate(getBlockPos(), getBlockState().setValue(BlazeEnchanterBlock.HEAT_LEVEL,heatLevel));
    }

}