package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.SweetBerryBushBlock;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AutoBerryFarm extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Scan radius in blocks.")
        .defaultValue(4)
        .min(1)
        .sliderMax(6)
        .build()
    );
    private final Setting<Boolean> stealth = sgGeneral.add(new BoolSetting.Builder()
        .name("stealth-mode")
        .description("Safer interactions with jittered timing, legit rotations and 1 action per tick.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("max-per-tick")
        .description("Maximum berry bushes to harvest per tick.")
        .defaultValue(2)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> minDelay = sgGeneral.add(new IntSetting.Builder()
        .name("min-delay-ticks")
        .description("Minimum delay between actions in ticks.")
        .defaultValue(2)
        .min(0)
        .sliderMax(20)
        .build()
    );

    private final Setting<Integer> maxDelay = sgGeneral.add(new IntSetting.Builder()
        .name("max-delay-ticks")
        .description("Maximum delay between actions in ticks.")
        .defaultValue(6)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private final Setting<Integer> rotatePriority = sgGeneral.add(new IntSetting.Builder()
        .name("rotate-priority")
        .description("Rotation priority while interacting.")
        .defaultValue(5)
        .min(0)
        .sliderMax(10)
        .build()
    );

    private final Setting<Boolean> los = sgGeneral.add(new BoolSetting.Builder()
        .name("line-of-sight")
        .description("Only harvest bushes with line of sight.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> inScreens = sgGeneral.add(new BoolSetting.Builder()
        .name("while-in-screens")
        .description("Run while GUI screens are open.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> sneakBefore = sgGeneral.add(new BoolSetting.Builder()
        .name("sneak-before-interact")
        .description("Temporarily sneak while harvesting.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> validateCrosshair = sgGeneral.add(new BoolSetting.Builder()
        .name("validate-crosshair")
        .description("Raycast-validate crosshair is on the bush before interacting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> perBushCooldown = sgGeneral.add(new IntSetting.Builder()
        .name("per-bush-cooldown")
        .description("Cooldown in ticks for a bush after interacting or failed validation.")
        .defaultValue(10)
        .min(0)
        .sliderMax(40)
        .build()
    );

    private final Random rng = new Random();
    private int cooldownTicks;
    private final Map<BlockPos, Integer> bushCooldownTicks = new HashMap<>();

    public AutoBerryFarm() {
        super(Categories.Player, "auto-berry-farm", "Automatically harvests sweet berries within range. Stealth mode uses human-like timing and legit rotations.");
    }

    @Override
    public void onActivate() {
        cooldownTicks = 0;
        bushCooldownTicks.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!isActive()) return;
        if (mc.player == null || mc.world == null) return;
        if (!inScreens.get() && mc.currentScreen != null) return;

        if (!bushCooldownTicks.isEmpty()) {
            Iterator<Map.Entry<BlockPos, Integer>> it = bushCooldownTicks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Integer> e = it.next();
                int v = e.getValue() - 1;
                if (v <= 0) it.remove();
                else e.setValue(v);
            }
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        List<BlockPos> targets = findMatureBushes();
        if (targets.isEmpty()) return;

        int allowed = stealth.get() ? 1 : maxPerTick.get();
        int done = 0;
        for (BlockPos pos : targets) {
            if (done >= allowed) break;
            if (interactBush(pos)) done++;
        }

        if (done > 0) {
            int mn = Math.min(minDelay.get(), maxDelay.get());
            int mx = Math.max(minDelay.get(), maxDelay.get());
            int base = mn + rng.nextInt(mx - mn + 1);
            if (stealth.get()) base += rng.nextInt(2);
            cooldownTicks = base;
        }
    }

    private List<BlockPos> findMatureBushes() {
        List<BlockPos> list = new ArrayList<>();
        BlockPos playerPos = mc.player.getBlockPos();
        int r = radius.get();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = playerPos.add(dx, dy, dz);
                    if (bushCooldownTicks.getOrDefault(pos, 0) > 0) continue;
                    BlockState st = mc.world.getBlockState(pos);
                    if (st.getBlock() instanceof SweetBerryBushBlock) {
                        if (st.get(SweetBerryBushBlock.AGE) == 3) {
                            if (!los.get() || hasLineOfSight(pos)) list.add(pos);
                        }
                    }
                }
            }
        }

        list.sort(Comparator.comparingDouble(p -> mc.player.squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5)));
        return list;
    }

    private boolean hasLineOfSight(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d hit = Vec3d.ofCenter(pos);
        RaycastContext rc = new RaycastContext(eye, hit, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        var hr = mc.world.raycast(rc);
        if (hr == null) return true;
        if (hr.getType() == HitResult.Type.MISS) return true;
        if (hr.getType() == HitResult.Type.BLOCK) return ((BlockHitResult) hr).getBlockPos().equals(pos);
        return true;
    }

    private boolean interactBush(BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        Vec3d hit = center;
        if (stealth.get()) {
            double ox = (rng.nextDouble() - 0.5) * 0.12;
            double oy = (rng.nextDouble() - 0.5) * 0.12;
            double oz = (rng.nextDouble() - 0.5) * 0.12;
            hit = center.add(ox, oy, oz);
        }

        Vec3d eye = mc.player.getEyePos();
        RaycastContext rc = new RaycastContext(eye, hit, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
        HitResult hr = mc.world.raycast(rc);
        BlockHitResult bhr;
        if (hr instanceof BlockHitResult bhr0 && bhr0.getType() == HitResult.Type.BLOCK && bhr0.getBlockPos().equals(pos)) {
            bhr = bhr0;
        } else {
            bhr = new BlockHitResult(hit, Direction.UP, pos, false);
        }

        double yaw = Rotations.getYaw(bhr.getPos());
        double pitch = Rotations.getPitch(bhr.getPos());

        Runnable doInteract = () -> {
            if (validateCrosshair.get()) {
                HitResult hr2 = mc.world.raycast(new RaycastContext(mc.player.getEyePos(), bhr.getPos(), RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
                if (!(hr2 instanceof BlockHitResult b2) || !b2.getBlockPos().equals(pos)) {
                    bushCooldownTicks.put(pos.toImmutable(), Math.max(1, perBushCooldown.get() - 2 + rng.nextInt(5)));
                    return;
                }
            }

            if (sneakBefore.get()) {
                boolean wasSneaking = mc.player.isSneaking();
                mc.player.setSneaking(true);
                BlockUtils.interact(bhr, Hand.MAIN_HAND, true);
                mc.player.setSneaking(wasSneaking);
            } else {
                BlockUtils.interact(bhr, Hand.MAIN_HAND, true);
            }

            bushCooldownTicks.put(pos.toImmutable(), Math.max(1, perBushCooldown.get() - 2 + rng.nextInt(5)));
        };

        if (stealth.get()) Rotations.rotate(yaw, pitch, rotatePriority.get(), true, doInteract);
        else Rotations.rotate(yaw, pitch, rotatePriority.get(), doInteract);

        return true;
    }
}
