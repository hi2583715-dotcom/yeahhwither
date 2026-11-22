package com.witherform;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.ItemEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Wither Form
 * Hold the Wither Skull (custom_data "wither_form") in your OFFHAND to become the Wither:
 *  - transformation animation
 *  - creative-style flight
 *  - wither particle aura, wither immunity, light resistance
 *  - right-click: fires 3 wither skulls (left, straight, right)
 */
public class WitherForm implements ModInitializer {

	// ---- tuning ----
	private static final String TAG = "wither_form";
	private static final int COOLDOWN_TICKS = 9;          // 0.45 s
	private static final double SPREAD_DEGREES = 12.0;    // left / right skull angle
	private static final double SKULL_SPEED = 1.4;
	private static final float FLY_SPEED = 0.04f;         // vanilla creative = 0.05
	private static final int ANIM_TICKS = 45;

	// ---- state ----
	private static int tick = 0;
	private static final Set<UUID> ACTIVE = new HashSet<>();
	private static final Map<UUID, Integer> ANIM_START = new HashMap<>();
	private static final Map<UUID, Integer> LAST_FIRE = new HashMap<>();
	private static final List<Entity> TRACKED_SKULLS = new ArrayList<>();

	@Override
	public void onInitialize() {
		// Right-click in air
		ItemEvents.USE.register((level, player, hand) -> {
			ItemStack used = player.getItemInHand(hand);
			boolean offhandForm = isForm(player.getOffhandItem());
			boolean fireNow = (hand == InteractionHand.OFF_HAND && isForm(used))
					|| (hand == InteractionHand.MAIN_HAND && used.isEmpty() && offhandForm);
			if (!fireNow) {
				return null; // not ours, let vanilla / other mods handle it
			}
			if (!level.isClientSide() && player instanceof ServerPlayer sp) {
				fire(sp);
			}
			return InteractionResult.SUCCESS;
		});

		// Right-click on a block: stops the skull from being placed, and fires if it's in the offhand
		ItemEvents.USE_ON.register((UseOnContext ctx) -> {
			ItemStack stack = ctx.getItemInHand();
			if (!isForm(stack)) {
				return null;
			}
			Player player = ctx.getPlayer();
			if (ctx.getHand() == InteractionHand.OFF_HAND && player != null) {
				if (!ctx.getLevel().isClientSide() && player instanceof ServerPlayer sp) {
					fire(sp);
				}
				return InteractionResult.SUCCESS;
			}
			return InteractionResult.FAIL; // main hand: never place it
		});

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tick++;
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				tickPlayer(p);
			}
			tickSkullTrails();
		});

		// /witherskull gives the item
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				dispatcher.register(Commands.literal("witherskull").executes(ctx -> {
					ServerPlayer p = ctx.getSource().getPlayerOrException();
					p.addItem(createItem());
					ctx.getSource().sendSuccess(() -> Component.literal("Given the Wither Skull."), false);
					return Command.SINGLE_SUCCESS;
				})));
	}

	// ================= item =================

	public static ItemStack createItem() {
		ItemStack s = new ItemStack(Items.WITHER_SKELETON_SKULL);
		CompoundTag tag = new CompoundTag();
		tag.putBoolean(TAG, true);
		s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
		s.set(DataComponents.CUSTOM_NAME, gradientName("Wither Skull"));
		s.set(DataComponents.LORE, new ItemLore(List.of(
				(Component) Component.literal("Embrace the Wither")
						.withStyle(Style.EMPTY.withColor(0x6b6b73).withItalic(true)),
				(Component) Component.literal("Hold in your offhand to take its form")
						.withStyle(Style.EMPTY.withColor(0x4a4a52).withItalic(false)))));
		s.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
		return s;
	}

	private static MutableComponent gradientName(String name) {
		MutableComponent root = Component.empty();
		int letters = (int) name.chars().filter(c -> c != ' ').count();
		int idx = 0;
		for (char c : name.toCharArray()) {
			if (c == ' ') {
				root.append(Component.literal(" "));
				continue;
			}
			double t = letters <= 1 ? 0 : (double) idx / (letters - 1);
			idx++;
			t = 1.0 - Math.abs(2.0 * t - 1.0); // dark -> silver -> dark
			int r = (int) Math.round(0x2a + (0xa8 - 0x2a) * t);
			int g = (int) Math.round(0x2a + (0xa8 - 0x2a) * t);
			int b = (int) Math.round(0x2e + (0xb0 - 0x2e) * t);
			int rgb = (r << 16) | (g << 8) | b;
			root.append(Component.literal(String.valueOf(c))
					.withStyle(Style.EMPTY.withColor(rgb).withBold(true).withItalic(false)));
		}
		return root;
	}

	private static boolean isForm(ItemStack stack) {
		if (stack == null || !stack.is(Items.WITHER_SKELETON_SKULL)) {
			return false;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		return data != null && data.contains(TAG);
	}

	// ================= per-player tick =================

	private static boolean canChangeFlight(ServerPlayer p) {
		return !p.isCreative() && !p.isSpectator();
	}

	private static void tickPlayer(ServerPlayer p) {
		UUID id = p.getUUID();
		boolean has = isForm(p.getOffhandItem());
		boolean active = ACTIVE.contains(id);

		if (has && !active) {
			activate(p);
		} else if (!has && active) {
			deactivate(p);
		} else if (!has) {
			// safety net: clear stray flight left over from a previous session
			if (canChangeFlight(p) && p.getAbilities().mayfly) {
				p.getAbilities().mayfly = false;
				p.getAbilities().flying = false;
				p.getAbilities().setFlyingSpeed(0.05f);
				p.onUpdateAbilities();
			}
		}

		if (has) {
			maintain(p);
		}

		Integer start = ANIM_START.get(id);
		if (start != null) {
			int t = tick - start;
			if (t > ANIM_TICKS) {
				ANIM_START.remove(id);
			} else {
				animationFrame(p, t);
			}
		}
	}

	private static void activate(ServerPlayer p) {
		ACTIVE.add(p.getUUID());
		ANIM_START.put(p.getUUID(), tick);
		if (canChangeFlight(p)) {
			p.getAbilities().mayfly = true;
			p.getAbilities().setFlyingSpeed(FLY_SPEED);
			p.onUpdateAbilities();
		}
		p.displayClientMessage(Component.literal("You have become the Wither.")
				.withStyle(Style.EMPTY.withColor(0x8a8a92).withBold(true)), true);
	}

	private static void deactivate(ServerPlayer p) {
		ACTIVE.remove(p.getUUID());
		ANIM_START.remove(p.getUUID());
		if (canChangeFlight(p)) {
			p.getAbilities().mayfly = false;
			p.getAbilities().flying = false;
			p.getAbilities().setFlyingSpeed(0.05f);
			p.onUpdateAbilities();
			p.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 300, 0, false, false));
		}
		if (p.level() instanceof ServerLevel sl) {
			sl.sendParticles(ParticleTypes.LARGE_SMOKE, p.getX(), p.getY() + 1, p.getZ(), 30, 0.4, 0.8, 0.4, 0.02);
		}
		p.displayClientMessage(Component.literal("Wither form ended")
				.withStyle(Style.EMPTY.withColor(0x6b6b73)), true);
	}

	private static void maintain(ServerPlayer p) {
		if (canChangeFlight(p) && (!p.getAbilities().mayfly)) {
			p.getAbilities().mayfly = true;
			p.getAbilities().setFlyingSpeed(FLY_SPEED);
			p.onUpdateAbilities();
		}
		p.removeEffect(MobEffects.WITHER);
		if (tick % 20 == 0) {
			p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 60, 0, false, false));
		}
		if (p.level() instanceof ServerLevel sl) {
			double x = p.getX(), y = p.getY(), z = p.getZ();
			sl.sendParticles(ParticleTypes.SMOKE, x, y + 1.0, z, 3, 0.35, 0.7, 0.35, 0.01);
			sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 1.0, z, 1, 0.3, 0.6, 0.3, 0.01);
			if (tick % 3 == 0) {
				sl.sendParticles(ParticleTypes.SOUL, x, y + 0.2, z, 1, 0.25, 0.1, 0.25, 0.02);
			}
			// two orbiting soul wisps
			double a = tick * 0.35;
			for (int i = 0; i < 2; i++) {
				double ang = a + i * Math.PI;
				sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
						x + Math.cos(ang) * 0.9, y + 1.1 + Math.sin(a * 0.5) * 0.3, z + Math.sin(ang) * 0.9,
						1, 0, 0, 0, 0);
			}
		}
	}

	// ================= transformation animation =================

	private static void animationFrame(ServerPlayer p, int t) {
		if (!(p.level() instanceof ServerLevel sl)) {
			return;
		}
		double x = p.getX(), y = p.getY(), z = p.getZ();
		if (t == 0) {
			sl.playSound(null, x, y, z, SoundEvents.WITHER_SPAWN, SoundSource.PLAYERS, 0.9f, 1.3f);
		}
		// rising double helix that tightens toward the player
		double radius = Math.max(0.3, 2.2 - t * 0.04);
		double height = t * 0.045;
		for (int arm = 0; arm < 3; arm++) {
			double ang = t * 0.55 + arm * (Math.PI * 2 / 3);
			double px = x + Math.cos(ang) * radius;
			double pz = z + Math.sin(ang) * radius;
			sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, px, y + height, pz, 2, 0.02, 0.02, 0.02, 0.0);
			sl.sendParticles(ParticleTypes.SMOKE, px, y + height, pz, 1, 0.05, 0.05, 0.05, 0.0);
		}
		// dark column
		sl.sendParticles(ParticleTypes.LARGE_SMOKE, x, y + 1.0, z, 2, 0.2, 1.0, 0.2, 0.01);
		if (t == ANIM_TICKS) {
			sl.playSound(null, x, y, z, SoundEvents.WITHER_BREAK_BLOCK, SoundSource.PLAYERS, 0.8f, 0.8f);
			sl.playSound(null, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.PLAYERS, 0.6f, 1.4f);
			sl.sendParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 1.0, z, 1, 0, 0, 0, 0);
			sl.sendParticles(ParticleTypes.SOUL, x, y + 1.0, z, 60, 0.6, 0.9, 0.6, 0.08);
			sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, x, y + 1.0, z, 60, 0.5, 0.8, 0.5, 0.12);
		}
	}

	// ================= triple skull blast =================

	private static void fire(ServerPlayer p) {
		Integer last = LAST_FIRE.get(p.getUUID());
		if (last != null && tick - last < COOLDOWN_TICKS) {
			return;
		}
		if (!(p.level() instanceof ServerLevel sl)) {
			return;
		}
		LAST_FIRE.put(p.getUUID(), tick);

		Vec3 look = p.getLookAngle();
		Vec3 origin = p.getEyePosition().add(look.scale(1.2)).add(0, -0.25, 0);

		// +angle = left, 0 = straight, -angle = right
		double[] angles = {SPREAD_DEGREES, 0.0, -SPREAD_DEGREES};
		for (double deg : angles) {
			Vec3 dir = look.yRot((float) Math.toRadians(deg)).normalize();
			Entity skull = EntityType.WITHER_SKULL.create(sl, EntitySpawnReason.TRIGGERED);
			if (skull == null) {
				continue;
			}
			skull.setPos(origin.x, origin.y, origin.z);
			skull.setDeltaMovement(dir.scale(SKULL_SPEED));
			skull.setYRot((float) (Math.toDegrees(Math.atan2(-dir.x, dir.z))));
			skull.setXRot((float) (-Math.toDegrees(Math.asin(dir.y))));
			if (skull instanceof Projectile proj) {
				proj.setOwner(p);
			}
			sl.addFreshEntity(skull);
			TRACKED_SKULLS.add(skull);
		}

		sl.playSound(null, origin.x, origin.y, origin.z, SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 1.0f, 0.8f);
		sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, origin.x, origin.y, origin.z, 25, 0.3, 0.3, 0.3, 0.06);
		sl.sendParticles(ParticleTypes.LARGE_SMOKE, origin.x, origin.y, origin.z, 8, 0.2, 0.2, 0.2, 0.03);
	}

	private static void tickSkullTrails() {
		Iterator<Entity> it = TRACKED_SKULLS.iterator();
		while (it.hasNext()) {
			Entity e = it.next();
			if (e.isRemoved() || !(e.level() instanceof ServerLevel sl)) {
				it.remove();
				continue;
			}
			sl.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, e.getX(), e.getY(), e.getZ(), 2, 0.08, 0.08, 0.08, 0.01);
			sl.sendParticles(ParticleTypes.SMOKE, e.getX(), e.getY(), e.getZ(), 2, 0.1, 0.1, 0.1, 0.01);
			sl.sendParticles(ParticleTypes.SOUL, e.getX(), e.getY(), e.getZ(), 1, 0.05, 0.05, 0.05, 0.02);
		}
	}
}
