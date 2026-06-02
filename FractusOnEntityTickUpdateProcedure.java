package net.mcreator.thebackwoods.procedures;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.Optional;

@EventBusSubscriber
public class FractusOnEntityTickUpdateProcedure {
	// 1.21.8 neoforge - Fractus drone/sentry AI and dense vanilla redstone-dust laser.
	// This version intentionally uses only particles for the laser visual; no custom beam renderer is used here.

	private static final ResourceLocation FRACTUS_ID = ResourceLocation.parse("the_backwoods:fractus");
	private static final ResourceLocation FRACTUS_LASER_SOUND = ResourceLocation.parse("the_backwoods:fractus_laser");
	private static final ResourceLocation FRACTUS_LASER_BURST_SOUND = ResourceLocation.parse("the_backwoods:fractus_laser_burst");
	private static final ResourceLocation FRACTUS_ANGER_SOUND = ResourceLocation.parse("the_backwoods:fractus_anger");
	private static final ResourceKey<Level> SUB_STRATA_DIMENSION = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("the_backwoods:backwoods"));

	// Targeting
	private static final double DETECTION_RANGE = 28.0;
	private static final double LASER_RANGE = 32.0;
	private static final double ANGRY_LASER_RANGE = 44.0;
	private static final double AIM_LOCK_TURN_RATE = 0.090;
	private static final double ANGRY_AIM_LOCK_TURN_RATE = 0.120;
	private static final double FIRING_AIM_TURN_RATE = 0.035;
	private static final double ANGRY_FIRING_AIM_TURN_RATE = 0.055;
	private static final double TARGET_LEAD_TICKS = 2.25;
	private static final double ANGRY_TARGET_LEAD_TICKS = 3.0;
	private static final double BURST_LASER_RANGE = 128.0;
	private static final double BURST_LASER_RADIUS = 2.6;

	// Home / leash
	private static final double HOME_LEASH_RANGE = 42.0;

	// Drone movement
	private static final double PREFERRED_COMBAT_RANGE = 13.0;
	private static final double ANGRY_PREFERRED_COMBAT_RANGE = 17.0;
	private static final double TOO_CLOSE_RANGE = 6.0;
	private static final double HOVER_HEIGHT = 3.25;
	private static final double ANGRY_HOVER_HEIGHT = 4.35;
	private static final double ESCAPED_CONTAINMENT_HOVER_BONUS = 2.25;
	private static final double IDLE_HOVER_HEIGHT = 2.25;
	private static final double DRONE_ACCELERATION = 0.085;
	private static final double MAX_DRONE_SPEED = 0.36;
	private static final double ANGRY_MAX_DRONE_SPEED = 0.44;
	private static final double IDLE_MAX_SPEED = 0.16;
	private static final double RETREAT_BURST_SPEED = 0.68;
	private static final double COOLDOWN_REPOSITION_SPEED = 0.58;
	private static final double VERTICAL_SPEED_LIMIT = 0.22;
	private static final double ORBIT_RADIUS = 3.0;
	private static final double ORBIT_SPEED = 0.055;
	private static final double ANGRY_ORBIT_SPEED = 0.075;
	private static final double BOB_AMOUNT = 0.38;
	private static final double BOB_SPEED = 0.12;
	private static final double IDLE_SCAN_SPEED = 0.006;
	private static final double RETREAT_RANGE_BONUS = 8.0;
	private static final double COVER_SEARCH_RADIUS = 8.0;
	private static final double COVER_MIN_PLAYER_DISTANCE = 9.0;
	private static final int COVER_SEARCH_STEPS = 16;
	private static final int ANGER_RETREAT_TICKS = 35;
	private static final int COOLDOWN_REPOSITION_TICKS = 18;
	private static final int SUPPRESSION_TICKS = 45;
	private static final double SUPPRESSION_RANGE = 48.0;
	private static final double FLANK_RANGE_BONUS = 4.0;
	private static final double FLANK_SIDE_DISTANCE = 9.0;
	private static final double FLANK_REPOSITION_SPEED = 0.50;
	private static final int VULNERABILITY_TICKS = 30;
	private static final int COVER_FLANK_TICKS = 60;
	private static final int BURST_TOTAL_TICKS = 110;
	private static final int BURST_FIRE_PEAK_TICK = 50;
	private static final int BURST_CORE_END_TICK = 73;
	private static final int BURST_COOLDOWN_TICKS = 350;
	private static final int STRONG_TARGET_BURST_COOLDOWN_TICKS = 180;
	private static final double BURST_KNOCKBACK_HORIZONTAL = 2.4;
	private static final double BURST_KNOCKBACK_VERTICAL = 0.55;
	private static final float STRONG_TARGET_HEALTH_THRESHOLD = 80.0f;
	private static final float STRONG_TARGET_CURRENT_HEALTH_THRESHOLD = 60.0f;
	private static final int STRONG_TARGET_BURST_CHECK_INTERVAL = 80;
	private static final double STRONG_TARGET_BURST_CHANCE = 0.38;
	private static final int ESCAPED_BURST_CHECK_INTERVAL = 150;
	private static final double ESCAPED_BURST_CHANCE = 0.20;
	private static final double PROJECTILE_DODGE_CHANCE = 0.42;
	private static final double ANGRY_PROJECTILE_DODGE_CHANCE = 0.58;
	private static final double ESCAPED_PROJECTILE_DODGE_BONUS = 0.14;
	private static final int PROJECTILE_DODGE_ATTEMPTS = 12;
	private static final int STUCK_ESCAPE_TICKS = 12;
	private static final int OPEN_SPACE_SEARCH_STEPS = 20;
	private static final double OPEN_SPACE_SEARCH_RADIUS = 7.0;
	private static final double OPEN_SPACE_VERTICAL_RANGE = 4.0;
	private static final double IDLE_PATROL_RADIUS = 4.5;
	private static final double IDLE_PATROL_REACH_DISTANCE = 0.85;
	private static final int IDLE_PATROL_WAIT_TICKS = 14;
	private static final int IDLE_PATROL_POINTS = 3;
	private static final double HARMFUL_BLOCK_AVOID_RADIUS = 2.25;
	private static final double HARMFUL_BLOCK_ESCAPE_SPEED = 0.62;
	private static final int TARGET_LOS_CANDIDATE_LIMIT = 3;
	private static final double ESCAPED_PASSIVE_FLEE_RANGE = 18.0;
	private static final double ESCAPED_MOB_RETALIATION_RANGE = 24.0;
	private static final double PASSIVE_FLEE_SPEED = 1.35;
	private static final double PASSIVE_FLEE_PUSH = 0.22;
	private static final int ESCAPED_THREAT_UPDATE_INTERVAL = 10;
	private static final int DESTROYING_FIRE_TICKS = 34;
	private static final int DESTROYING_COOLDOWN_TICKS = 120;
	private static final double DESTROYING_LASER_RANGE = 52.0;
	private static final double DESTROYING_VERTICAL_AIM_MIN = -0.35;
	private static final double DESTROYING_VERTICAL_AIM_MAX = 0.12;

	// Anger
	private static final float ANGER_HEALTH_THRESHOLD = 20.0f;
	private static final int ANGER_SOUND_INTERVAL_TICKS = 90; // 4.5 seconds at 20 TPS.

	// Laser timing
	private static final int CHARGE_TICKS = 28;
	// 1.21.8 neoforge - fractus_laser starts fading around 6.15 seconds.
	// Minecraft runs at 20 ticks per second, so 6.15 * 20 = 123 ticks.
	private static final int FIRE_TICKS = 123;
	// Your laser sound fades from about 6.15s to 8.0s.
	// 46 ticks gives the audio tail time to finish before another shot.
	private static final int COOLDOWN_TICKS = 46;

	// Laser damage
	private static final int DAMAGE_INTERVAL_TICKS = 10;
	private static final int ANGRY_DAMAGE_INTERVAL_TICKS = 6;
	private static final float LASER_DAMAGE = 2.0f;
	private static final float ANGRY_LASER_DAMAGE = 3.0f;
	private static final float BURST_LASER_DAMAGE = 70.0f;

	// Weak block destruction.
	// Blocks with hardness from 0 up to this value can be destroyed by the firing laser.
	// Negative hardness blocks, like bedrock-style unbreakable blocks, are ignored.
	private static final float WEAK_BLOCK_MAX_HARDNESS = 0.2f;
	private static final float ANGRY_WEAK_BLOCK_MAX_HARDNESS = 2.0f;
	private static final float BURST_BLOCK_MAX_HARDNESS = 60.0f;
	private static final double BLOCK_BREAK_STEP = 0.25;
	private static final double BURST_BLOCK_BREAK_STEP = 0.75;

	// Dense redstone dust laser visual.
	// Lower spacing = denser beam = more particles.
	private static final double LASER_PARTICLE_SPACING = 0.14;
	private static final double ANGRY_LASER_PARTICLE_SPACING = 0.08;
	private static final double CHARGE_PARTICLE_SPACING = 0.78;
	private static final double ANGRY_CHARGE_PARTICLE_SPACING = 0.50;
	private static final double LASER_PARTICLE_JITTER = 0.018;
	private static final double ANGRY_LASER_PARTICLE_JITTER = 0.028;
	private static final double CHARGE_PARTICLE_JITTER = 0.035;
	private static final double FIRING_SPIRAL_RADIUS = 0.055;
	private static final double FIRE_START_RING_RADIUS = 0.72;

	// 1.21.8 neoforge - vanilla dust particles with phase-readable colors.
	private static final DustParticleOptions NORMAL_LASER_PARTICLE = new DustParticleOptions(0xFF6A00, 1.0f);
	private static final DustParticleOptions ANGRY_LASER_PARTICLE = new DustParticleOptions(0xFF1A00, 1.25f);
	private static final DustParticleOptions BURST_LASER_PARTICLE = new DustParticleOptions(0xFF0000, 1.8f);
	private static final DustParticleOptions BURST_CHARGE_START_PARTICLE = new DustParticleOptions(0xFF6A00, 1.45f);
	private static final DustParticleOptions BURST_CHARGE_MID_PARTICLE = new DustParticleOptions(0xFF2A00, 1.65f);

	// Persistent data keys.
	// These are not synced data. They are server-side entity memory.
	private static final String K_HOME_SET = "fractus_home_set";
	private static final String K_HOME_X = "fractus_home_x";
	private static final String K_HOME_Y = "fractus_home_y";
	private static final String K_HOME_Z = "fractus_home_z";

	private static final String K_CHARGE = "fractus_laser_charge";
	private static final String K_FIRE = "fractus_laser_fire";
	private static final String K_COOLDOWN = "fractus_laser_cooldown";
	private static final String K_AIM_X = "fractus_laser_aim_x";
	private static final String K_AIM_Y = "fractus_laser_aim_y";
	private static final String K_AIM_Z = "fractus_laser_aim_z";
	private static final String K_WAS_ANGRY = "fractus_was_angry";
	private static final String K_ANGER_RETREAT = "fractus_anger_retreat";
	private static final String K_SUPPRESSION = "fractus_suppression";
	private static final String K_VULNERABLE = "fractus_vulnerable";
	private static final String K_RETURNING_HOME = "fractus_returning_home";
	private static final String K_COVER_WAIT = "fractus_cover_wait";
	private static final String K_BURST_TIMER = "fractus_burst_timer";
	private static final String K_BURST_COOLDOWN = "fractus_burst_cooldown";
	private static final String K_DESTROYING_FIRE = "fractus_destroying_fire";
	private static final String K_DESTROYING_COOLDOWN = "fractus_destroying_cooldown";
	private static final String K_DESTROYING_AIM_X = "fractus_destroying_aim_x";
	private static final String K_DESTROYING_AIM_Y = "fractus_destroying_aim_y";
	private static final String K_DESTROYING_AIM_Z = "fractus_destroying_aim_z";

	private static final String K_ORBIT_SEED = "fractus_orbit_seed";
	private static final String K_ORBIT_SIDE = "fractus_orbit_side";
	private static final String K_IDLE_PATROL_INDEX = "fractus_idle_patrol_index";
	private static final String K_IDLE_PATROL_WAIT = "fractus_idle_patrol_wait";
	private static final String K_STUCK_TICKS = "fractus_stuck_ticks";
	private static final String K_LAST_X = "fractus_last_x";
	private static final String K_LAST_Y = "fractus_last_y";
	private static final String K_LAST_Z = "fractus_last_z";

	private static final String K_ANGER_SOUND_COOLDOWN = "fractus_anger_sound_cooldown";
	private static final String K_LASER_SOUND_PLAYING = "fractus_laser_sound_playing";
	private static final String K_ESCAPED_THREAT_UPDATE = "fractus_escaped_threat_update";

	// 0 = idle, 1 = tracking, 2 = charging, 3 = firing, 4 = cooldown.
	private static final String K_LASER_STATE = "fractus_laser_state";

	// Keep this because MCreator-generated entity classes may call it.
	// The real logic is called by the event subscriber below.
	public static void execute() {
	}

	// 1.21.8 neoforge - this makes the procedure actually run every entity tick,
	// even if MCreator's generated entity only calls the empty execute() method.
	@SubscribeEvent
	public static void onEntityTick(EntityTickEvent.Pre event) {
		Entity entity = event.getEntity();

		if (entity == null || !isFractus(entity)) {
			return;
		}

		execute(entity.level(), entity.getX(), entity.getY(), entity.getZ(), entity);
	}

	@SubscribeEvent
	public static void onLivingDeath(LivingDeathEvent event) {
		LivingEntity entity = event.getEntity();

		if (entity == null || !isFractus(entity) || !(entity.level() instanceof ServerLevel serverLevel)) {
			return;
		}

		stopFractusActiveSounds(serverLevel, entity);
		resetLaser(entity);
		spawnDeathBurst(serverLevel, entity);
	}

	@SubscribeEvent
	public static void onIncomingDamage(LivingIncomingDamageEvent event) {
		LivingEntity entity = event.getEntity();

		if (entity == null || !isFractus(entity)) {
			return;
		}

		boolean angry = isAngry(entity);
		boolean projectileDamage = isProjectileDamage(event.getSource());

		markRetaliationTarget(entity, event.getSource());

		if (projectileDamage && tryDodgeProjectile(entity, event.getSource())) {
			event.setCanceled(true);
			return;
		}

		if (!angry && !projectileDamage && (persistentInt(entity, K_CHARGE, 0) > 0 || persistentInt(entity, K_FIRE, 0) > 0)) {
			entity.getPersistentData().putInt(K_CHARGE, 0);
			entity.getPersistentData().putInt(K_FIRE, 0);
			entity.getPersistentData().putInt(K_LASER_STATE, 1);

			if (entity.level() instanceof ServerLevel serverLevel) {
				stopFractusLaserSound(serverLevel, entity);
				spawnChargeInterruptParticles(serverLevel, entity);
			}
		}

		if (angry || !projectileDamage) {
			return;
		}

		event.setCanceled(true);

		if (entity.level() instanceof ServerLevel serverLevel) {
			spawnShieldParticles(serverLevel, entity);
		}
	}

	public static void execute(LevelAccessor world, double x, double y, double z, Entity entity) {
		if (entity == null || !isFractus(entity)) {
			return;
		}

		entity.setNoGravity(true);
		entity.fallDistance = 0.0f;
		ensureHome(entity, x, y, z);

		// AI, damage, sounds, block breaking, regeneration, and particles should happen on the logical server.
		// ServerLevel#sendParticles sends the red dust beam to nearby clients.
		if (!(world instanceof ServerLevel serverLevel)) {
			return;
		}

		int vulnerabilityTicks = tickStoredTimer(entity, K_VULNERABLE);

		if (entity instanceof LivingEntity livingEntity) {
			if (vulnerabilityTicks > 0) {
				livingEntity.removeEffect(MobEffects.REGENERATION);
				spawnVulnerabilityParticles(serverLevel, entity, vulnerabilityTicks);
			} else {
				applyInfiniteRegeneration(livingEntity);
			}
		}

		boolean angry = isAngry(entity);
		boolean wasAngry = persistentBoolean(entity, K_WAS_ANGRY, false);

		if (angry && !wasAngry) {
			entity.getPersistentData().putInt(K_ANGER_RETREAT, ANGER_RETREAT_TICKS);
			spawnAngerTransitionParticles(serverLevel, entity);
		}

		entity.getPersistentData().putBoolean(K_WAS_ANGRY, angry);
		handleAngerSound(serverLevel, entity, angry);

		int angerRetreatTicks = tickStoredTimer(entity, K_ANGER_RETREAT);
		int suppressionTicks = tickStoredTimer(entity, K_SUPPRESSION);
		int cooldown = tickStoredTimer(entity, K_COOLDOWN);
		int fireTicks = Math.max(0, persistentInt(entity, K_FIRE, 0));
		int chargeTicks = Math.max(0, persistentInt(entity, K_CHARGE, 0));
		int burstTicks = tickStoredTimer(entity, K_BURST_TIMER);
		int burstCooldown = tickStoredTimer(entity, K_BURST_COOLDOWN);
		int previousLaserState = persistentInt(entity, K_LASER_STATE, 0);
		LivingEntity target = findTarget(serverLevel, entity, x, y, z);

		if (isEscapedContainmentDimension(entity)) {
			alertNearbyMobsAndPanicPassives(serverLevel, entity);
		}

		if (cooldown > 0) {
			stopFractusLaserSound(serverLevel, entity);
		}

		if (shouldStartBurstAttack(entity, target, burstTicks, burstCooldown)) {
			burstTicks = BURST_TOTAL_TICKS;
			entity.getPersistentData().putInt(K_BURST_TIMER, burstTicks);
			entity.getPersistentData().putInt(K_BURST_COOLDOWN, burstCooldownTicksForTarget(target));
			playFractusLaserBurstSound(serverLevel, entity);
		}

		if (burstTicks > 0) {
			handleBurstLaser(serverLevel, entity, target, burstTicks);
		}

		if (target == null) {
			driftIdleScan(entity);
			entity.getPersistentData().putBoolean(K_RETURNING_HOME, false);
			entity.getPersistentData().putInt(K_COVER_WAIT, 0);

			if (handleEscapedDestroyingMode(serverLevel, entity)) {
				resetLaser(entity);
				moveToward(entity, idleHomePoint(entity), IDLE_MAX_SPEED);
				faceMovement(entity);
				return;
			}

			stopFractusLaserSound(serverLevel, entity);
			resetLaser(entity);
			entity.getPersistentData().putInt(K_LASER_STATE, cooldown > 0 ? 4 : 0);
			moveToward(entity, idleHomePoint(entity), IDLE_MAX_SPEED);
			faceMovement(entity);
			return;
		}

		if (shouldReturnHome(entity)) {
			stopFractusLaserSound(serverLevel, entity);
			resetLaser(entity);
			entity.getPersistentData().putInt(K_COVER_WAIT, 0);

			if (angry && !persistentBoolean(entity, K_RETURNING_HOME, false)) {
				playFractusAngerSound(serverLevel, entity);
			}

			entity.getPersistentData().putBoolean(K_RETURNING_HOME, true);
			entity.getPersistentData().putInt(K_LASER_STATE, cooldown > 0 ? 4 : 0);
			spawnLeashBreakTrail(serverLevel, entity);
			moveToward(entity, idleHomePoint(entity), IDLE_MAX_SPEED);
			faceMovement(entity);
			return;
		}

		entity.getPersistentData().putBoolean(K_RETURNING_HOME, false);
		cancelDestroyingMode(serverLevel, entity);

		if (previousLaserState == 0) {
			playTargetAcquiredSound(serverLevel, entity);
		}

		faceTarget(entity, target);

		double currentLaserRange = angry ? ANGRY_LASER_RANGE : LASER_RANGE;
		boolean canSeeTarget = hasClearShot(serverLevel, entity, target, currentLaserRange);
		double distance = entity.distanceTo(target);

		if (cooldown > 0) {
			entity.getPersistentData().putInt(K_LASER_STATE, 4);
			entity.getPersistentData().putInt(K_CHARGE, 0);
			entity.getPersistentData().putInt(K_FIRE, 0);
			moveCombat(entity, target, false, angry, cooldown, angerRetreatTicks);
			return;
		}

		if ((!canSeeTarget && !angry) || distance > currentLaserRange) {
			if (fireTicks > 0) {
				stopFractusLaserSound(serverLevel, entity);
			}

			int coverWaitTicks = !canSeeTarget ? persistentInt(entity, K_COVER_WAIT, 0) + 1 : 0;
			entity.getPersistentData().putInt(K_COVER_WAIT, coverWaitTicks);
			entity.getPersistentData().putInt(K_LASER_STATE, 1);
			entity.getPersistentData().putInt(K_CHARGE, Math.max(0, chargeTicks - 2));
			entity.getPersistentData().putInt(K_FIRE, 0);

			if (coverWaitTicks >= COVER_FLANK_TICKS) {
				moveFlankForSight(entity, target, angry);
			} else {
				moveCombat(entity, target, false, angry, cooldown, angerRetreatTicks);
			}

			return;
		}

		entity.getPersistentData().putInt(K_COVER_WAIT, 0);

		if (fireTicks > 0) {
			entity.getPersistentData().putInt(K_LASER_STATE, 3);
			entity.getPersistentData().putInt(K_FIRE, fireTicks - 1);
			moveCombat(entity, target, true, angry, cooldown, angerRetreatTicks);

			// Play once, exactly when the firing state starts.
			if (fireTicks == FIRE_TICKS) {
				playFractusLaserSound(serverLevel, entity);
				spawnFireStartWarning(serverLevel, entity, angry);
			}

			Vec3 laserDirection = updateLaserAim(entity, target, angry, true);
			LaserHit firstHit = raycastLaser(serverLevel, entity, laserDirection, currentLaserRange);
			LaserHit finalHit = firstHit;

			// Realistic weak-block destruction:
			// If the beam hits a player/entity first, stop there and do not break blocks behind that entity.
			// If no entity was hit, the missed beam can burn through weak blocks in its path.
			if (firstHit.entity() == null) {
				destroyWeakBlocksInLaserPath(serverLevel, entity, laserStart(entity), firstHit.location(), firstHit.blockPos(), angry);
				finalHit = raycastLaser(serverLevel, entity, laserDirection, currentLaserRange);
			}

			spawnLaser(
				serverLevel,
				laserStart(entity),
				finalHit.location(),
				angry ? ANGRY_LASER_PARTICLE_SPACING : LASER_PARTICLE_SPACING,
				angry ? ANGRY_LASER_PARTICLE_JITTER : LASER_PARTICLE_JITTER,
				true,
				angry
			);

			if (finalHit.entity() instanceof Player) {
				spawnPlayerLaserImpact(serverLevel, finalHit.location(), angry);
			}

			igniteLaserHitBlock(serverLevel, finalHit.blockPos(), finalHit.blockFace());

			int damageInterval = angry ? ANGRY_DAMAGE_INTERVAL_TICKS : DAMAGE_INTERVAL_TICKS;
			float damage = angry ? ANGRY_LASER_DAMAGE : LASER_DAMAGE;

			if (finalHit.entity() instanceof LivingEntity hitLiving && canDamage(entity, hitLiving) && entity.tickCount % damageInterval == 0) {
				hitLiving.hurt(new DamageSource(world.holderOrThrow(DamageTypes.MAGIC)), damage);
			}

			if (fireTicks - 1 <= 0) {
				entity.getPersistentData().putInt(K_COOLDOWN, COOLDOWN_TICKS);
				entity.getPersistentData().putInt(K_CHARGE, 0);
				entity.getPersistentData().putInt(K_VULNERABLE, VULNERABILITY_TICKS);
				shuffleCooldownOrbit(entity);
			}

			return;
		}

		if (suppressionTicks <= 0 && isNearbyFractusFiring(serverLevel, entity)) {
			suppressionTicks = SUPPRESSION_TICKS;
			entity.getPersistentData().putInt(K_SUPPRESSION, suppressionTicks);
		}

		if (suppressionTicks > 0) {
			entity.getPersistentData().putInt(K_LASER_STATE, 1);
			entity.getPersistentData().putInt(K_CHARGE, Math.max(0, chargeTicks - 1));
			moveCombat(entity, target, false, angry, cooldown, angerRetreatTicks);
			spawnSuppressionPulse(serverLevel, entity, suppressionTicks);
			return;
		}

		chargeTicks++;
		entity.getPersistentData().putInt(K_CHARGE, chargeTicks);
		entity.getPersistentData().putInt(K_LASER_STATE, 2);
		moveCombat(entity, target, true, angry, cooldown, angerRetreatTicks);

		Vec3 laserDirection = updateLaserAim(entity, target, angry, false);
		LaserHit previewHit = raycastLaser(serverLevel, entity, laserDirection, currentLaserRange);
		double chargeProgress = Mth.clamp((double) chargeTicks / CHARGE_TICKS, 0.0, 1.0);
		double previewSpacing = angry
			? Mth.lerp(chargeProgress, ANGRY_CHARGE_PARTICLE_SPACING, 0.16)
			: Mth.lerp(chargeProgress, CHARGE_PARTICLE_SPACING, 0.22);
		double previewJitter = CHARGE_PARTICLE_JITTER + chargeProgress * 0.018;
		spawnLaser(
			serverLevel,
			laserStart(entity),
			previewHit.location(),
			previewSpacing,
			previewJitter,
			false,
			angry
		);
		spawnChargeBeamHum(serverLevel, laserStart(entity), previewHit.location(), chargeProgress, angry);
		spawnChargeParticles(serverLevel, entity, chargeTicks, angry);

		if (chargeTicks == CHARGE_TICKS - 1) {
			spawnPreFireWarning(serverLevel, entity, angry);
		}

		if (chargeTicks >= CHARGE_TICKS) {
			entity.getPersistentData().putInt(K_FIRE, FIRE_TICKS);
			entity.getPersistentData().putInt(K_CHARGE, 0);
		}
	}

	private static boolean isFractus(Entity entity) {
		ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
		return FRACTUS_ID.equals(id) || "fractus".equals(id.getPath());
	}

	private static boolean isAngry(Entity entity) {
		return entity instanceof LivingEntity living && living.getHealth() <= ANGER_HEALTH_THRESHOLD;
	}

	private static boolean isEscapedContainmentDimension(Entity entity) {
		return !SUB_STRATA_DIMENSION.equals(entity.level().dimension());
	}

	private static boolean shouldStartBurstAttack(Entity entity, LivingEntity target, int burstTicks, int burstCooldown) {
		if (target == null || burstTicks > 0 || burstCooldown > 0) {
			return false;
		}

		if (entity instanceof LivingEntity livingEntity && livingEntity.getHealth() <= 12.0f) {
			return true;
		}

		boolean strongTarget = isStrongTarget(target);

		if (strongTarget && entity.tickCount % STRONG_TARGET_BURST_CHECK_INTERVAL == 0 && entity.getRandom().nextDouble() < STRONG_TARGET_BURST_CHANCE) {
			return true;
		}

		return isEscapedContainmentDimension(entity)
			&& entity.tickCount % ESCAPED_BURST_CHECK_INTERVAL == 0
			&& entity.getRandom().nextDouble() < ESCAPED_BURST_CHANCE;
	}

	private static boolean isStrongTarget(LivingEntity target) {
		return target != null && (target.getMaxHealth() >= STRONG_TARGET_HEALTH_THRESHOLD || target.getHealth() >= STRONG_TARGET_CURRENT_HEALTH_THRESHOLD);
	}

	private static int burstCooldownTicksForTarget(LivingEntity target) {
		return isStrongTarget(target) ? STRONG_TARGET_BURST_COOLDOWN_TICKS : BURST_COOLDOWN_TICKS;
	}

	private static void applyInfiniteRegeneration(LivingEntity entity) {
		entity.addEffect(new MobEffectInstance(MobEffects.REGENERATION, (int) Double.POSITIVE_INFINITY, 0, true, false));
	}

	private static void handleAngerSound(ServerLevel level, Entity entity, boolean angry) {
		if (!angry) {
			entity.getPersistentData().putInt(K_ANGER_SOUND_COOLDOWN, 0);
			return;
		}

		int cooldown = tickStoredTimer(entity, K_ANGER_SOUND_COOLDOWN);

		if (cooldown > 0) {
			return;
		}

		playFractusAngerSound(level, entity);
		entity.getPersistentData().putInt(K_ANGER_SOUND_COOLDOWN, ANGER_SOUND_INTERVAL_TICKS);
	}

	// 1.21.8 neoforge - CompoundTag primitive getters return Optional values.
	private static int persistentInt(Entity entity, String key, int fallback) {
		return entity.getPersistentData().getInt(key).orElse(fallback);
	}

	private static double persistentDouble(Entity entity, String key, double fallback) {
		return entity.getPersistentData().getDouble(key).orElse(fallback);
	}

	private static boolean persistentBoolean(Entity entity, String key, boolean fallback) {
		return entity.getPersistentData().getBoolean(key).orElse(fallback);
	}

	private static void ensureHome(Entity entity, double x, double y, double z) {
		if (persistentBoolean(entity, K_HOME_SET, false)) {
			return;
		}

		entity.getPersistentData().putBoolean(K_HOME_SET, true);
		entity.getPersistentData().putDouble(K_HOME_X, x);
		entity.getPersistentData().putDouble(K_HOME_Y, y);
		entity.getPersistentData().putDouble(K_HOME_Z, z);
		entity.getPersistentData().putDouble(K_ORBIT_SEED, entity.getRandom().nextDouble() * Math.PI * 2.0);
		entity.getPersistentData().putInt(K_ORBIT_SIDE, entity.getRandom().nextBoolean() ? 1 : -1);
	}

	private static void playFractusLaserSound(ServerLevel level, Entity entity) {
		entity.getPersistentData().putBoolean(K_LASER_SOUND_PLAYING, true);
		BuiltInRegistries.SOUND_EVENT.get(FRACTUS_LASER_SOUND).ifPresent(sound -> level.playSound(
			null,
			BlockPos.containing(entity.getX(), entity.getY(), entity.getZ()),
			sound.value(),
			SoundSource.HOSTILE,
			1.4f,
			1.0f
		));
	}

	private static void playFractusLaserBurstSound(ServerLevel level, Entity entity) {
		BuiltInRegistries.SOUND_EVENT.get(FRACTUS_LASER_BURST_SOUND).ifPresent(sound -> level.playSound(
			null,
			BlockPos.containing(entity.getX(), entity.getY(), entity.getZ()),
			sound.value(),
			SoundSource.HOSTILE,
			10.0f,
			1.0f
		));
	}

	private static void playFractusAngerSound(ServerLevel level, Entity entity) {
		BuiltInRegistries.SOUND_EVENT.get(FRACTUS_ANGER_SOUND).ifPresent(sound -> level.playSound(
			null,
			BlockPos.containing(entity.getX(), entity.getY(), entity.getZ()),
			sound.value(),
			SoundSource.HOSTILE,
			0.7f,
			1.0f
		));
	}

	private static void playTargetAcquiredSound(ServerLevel level, Entity entity) {
		level.playSound(
			null,
			BlockPos.containing(entity.getX(), entity.getY(), entity.getZ()),
			SoundEvents.WARDEN_TENDRIL_CLICKS,
			SoundSource.HOSTILE,
			0.75f,
			0.55f
		);
	}

	private static void stopFractusLaserSound(ServerLevel level, Entity entity) {
		if (!persistentBoolean(entity, K_LASER_SOUND_PLAYING, false) && persistentInt(entity, K_FIRE, 0) <= 0) {
			return;
		}

		entity.getPersistentData().putBoolean(K_LASER_SOUND_PLAYING, false);
		ClientboundStopSoundPacket stopLaser = new ClientboundStopSoundPacket(FRACTUS_LASER_SOUND, SoundSource.HOSTILE);

		for (ServerPlayer player : level.players()) {
			player.connection.send(stopLaser);
		}
	}

	private static void stopFractusActiveSounds(ServerLevel level, Entity entity) {
		entity.getPersistentData().putBoolean(K_LASER_SOUND_PLAYING, false);
		ClientboundStopSoundPacket stopLaser = new ClientboundStopSoundPacket(FRACTUS_LASER_SOUND, SoundSource.HOSTILE);
		ClientboundStopSoundPacket stopBurst = new ClientboundStopSoundPacket(FRACTUS_LASER_BURST_SOUND, SoundSource.HOSTILE);
		ClientboundStopSoundPacket stopAnger = new ClientboundStopSoundPacket(FRACTUS_ANGER_SOUND, SoundSource.HOSTILE);

		for (ServerPlayer player : level.players()) {
			player.connection.send(stopLaser);
			player.connection.send(stopBurst);
			player.connection.send(stopAnger);
		}
	}

	private static int tickStoredTimer(Entity entity, String key) {
		int value = Math.max(0, persistentInt(entity, key, 0));

		if (value > 0) {
			entity.getPersistentData().putInt(key, value - 1);
		}

		return value;
	}

	private static void resetLaser(Entity entity) {
		entity.getPersistentData().putInt(K_CHARGE, 0);
		entity.getPersistentData().putInt(K_FIRE, 0);
		entity.getPersistentData().putDouble(K_AIM_X, 0.0);
		entity.getPersistentData().putDouble(K_AIM_Y, 0.0);
		entity.getPersistentData().putDouble(K_AIM_Z, 0.0);
	}

	private static Vec3 home(Entity entity) {
		return new Vec3(
			persistentDouble(entity, K_HOME_X, entity.getX()),
			persistentDouble(entity, K_HOME_Y, entity.getY()),
			persistentDouble(entity, K_HOME_Z, entity.getZ())
		);
	}

	private static boolean shouldReturnHome(Entity entity) {
		if (isEscapedContainmentDimension(entity)) {
			return false;
		}

		return entity.position().distanceTo(home(entity)) > HOME_LEASH_RANGE;
	}

	private static void alertNearbyMobsAndPanicPassives(ServerLevel level, Entity entity) {
		if (!(entity instanceof LivingEntity fractusLiving)) {
			return;
		}

		int updateTimer = tickStoredTimer(entity, K_ESCAPED_THREAT_UPDATE);

		if (updateTimer > 0) {
			return;
		}

		entity.getPersistentData().putInt(K_ESCAPED_THREAT_UPDATE, ESCAPED_THREAT_UPDATE_INTERVAL);
		AABB searchBox = entity.getBoundingBox().inflate(Math.max(ESCAPED_PASSIVE_FLEE_RANGE, ESCAPED_MOB_RETALIATION_RANGE));

		for (Mob mob : level.getEntitiesOfClass(Mob.class, searchBox, mob -> mob.isAlive() && mob != entity && !isFractus(mob))) {
			if (isPassiveFleeCandidate(mob)) {
				panicPassiveAwayFromFractus(mob, entity);
				continue;
			}

			if (mob.distanceTo(entity) <= ESCAPED_MOB_RETALIATION_RANGE && canRetaliateAgainst(entity, mob) && canSeeForTargeting(level, mob, fractusLiving)) {
				mob.setTarget(fractusLiving);
				mob.setLastHurtByMob(fractusLiving);
			}
		}
	}

	private static boolean isPassiveFleeCandidate(Mob mob) {
		return mob instanceof AgeableMob && !isSeaAnimal(mob) && !isFractus(mob);
	}

	private static void panicPassiveAwayFromFractus(Mob mob, Entity fractus) {
		if (mob.distanceTo(fractus) > ESCAPED_PASSIVE_FLEE_RANGE) {
			return;
		}

		Vec3 away = mob.position().subtract(fractus.position());
		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = new Vec3(mob.getRandom().nextDouble() - 0.5, 0.0, mob.getRandom().nextDouble() - 0.5);
		}

		horizontalAway = horizontalAway.normalize();
		Vec3 fleePoint = mob.position().add(horizontalAway.scale(10.0));

		mob.getNavigation().moveTo(fleePoint.x, fleePoint.y, fleePoint.z, PASSIVE_FLEE_SPEED);
		mob.setDeltaMovement(mob.getDeltaMovement().add(horizontalAway.scale(PASSIVE_FLEE_PUSH)));
		mob.hasImpulse = true;
	}

	private static boolean handleEscapedDestroyingMode(ServerLevel level, Entity entity) {
		if (!isWorldTakeoverDimension(entity)) {
			return false;
		}

		int fireTicks = tickStoredTimer(entity, K_DESTROYING_FIRE);

		if (fireTicks <= 0) {
			int cooldown = tickStoredTimer(entity, K_DESTROYING_COOLDOWN);

			if (cooldown > 0) {
				return false;
			}

			fireTicks = DESTROYING_FIRE_TICKS;
			entity.getPersistentData().putInt(K_DESTROYING_FIRE, fireTicks);
			entity.getPersistentData().putInt(K_DESTROYING_COOLDOWN, DESTROYING_COOLDOWN_TICKS + entity.getRandom().nextInt(80));
			storeDestroyingAim(entity, randomDestroyingDirection(entity));
			playFractusLaserSound(level, entity);
		}

		Vec3 start = laserStart(entity);
		Vec3 direction = destroyingAim(entity);
		BlockHitResult blockHit = clipBlocks(level, entity, start, start.add(direction.scale(DESTROYING_LASER_RANGE)));
		Vec3 end = blockHit.getType() == HitResult.Type.MISS ? start.add(direction.scale(DESTROYING_LASER_RANGE)) : blockHit.getLocation();

		destroyWeakBlocksInLaserPath(level, entity, start, end, blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getBlockPos(), true);
		blockHit = clipBlocks(level, entity, start, start.add(direction.scale(DESTROYING_LASER_RANGE)));
		end = blockHit.getType() == HitResult.Type.MISS ? start.add(direction.scale(DESTROYING_LASER_RANGE)) : blockHit.getLocation();
		spawnLaser(level, start, end, ANGRY_LASER_PARTICLE_SPACING, ANGRY_LASER_PARTICLE_JITTER, true, true);
		igniteLaserHitBlock(level, blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getBlockPos(), blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getDirection());
		entity.getPersistentData().putInt(K_LASER_STATE, 3);

		if (fireTicks <= 1) {
			stopFractusLaserSound(level, entity);
			entity.getPersistentData().putInt(K_LASER_STATE, 0);
		}

		return true;
	}

	private static void cancelDestroyingMode(ServerLevel level, Entity entity) {
		if (persistentInt(entity, K_DESTROYING_FIRE, 0) <= 0) {
			return;
		}

		entity.getPersistentData().putInt(K_DESTROYING_FIRE, 0);
		stopFractusLaserSound(level, entity);
	}

	private static boolean isWorldTakeoverDimension(Entity entity) {
		return entity.level().dimension() == Level.OVERWORLD || entity.level().dimension() == Level.NETHER;
	}

	private static Vec3 randomDestroyingDirection(Entity entity) {
		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);
		double angle = seed + entity.getRandom().nextDouble() * Math.PI * 2.0;
		double y = Mth.lerp(entity.getRandom().nextDouble(), DESTROYING_VERTICAL_AIM_MIN, DESTROYING_VERTICAL_AIM_MAX);
		return new Vec3(Math.cos(angle), y, Math.sin(angle)).normalize();
	}

	private static void storeDestroyingAim(Entity entity, Vec3 direction) {
		entity.getPersistentData().putDouble(K_DESTROYING_AIM_X, direction.x);
		entity.getPersistentData().putDouble(K_DESTROYING_AIM_Y, direction.y);
		entity.getPersistentData().putDouble(K_DESTROYING_AIM_Z, direction.z);
	}

	private static Vec3 destroyingAim(Entity entity) {
		Vec3 direction = new Vec3(
			persistentDouble(entity, K_DESTROYING_AIM_X, 0.0),
			persistentDouble(entity, K_DESTROYING_AIM_Y, 0.0),
			persistentDouble(entity, K_DESTROYING_AIM_Z, 0.0)
		);

		return direction.lengthSqr() < 0.001 ? randomDestroyingDirection(entity) : direction.normalize();
	}

	private static Vec3 idleHomePoint(Entity entity) {
		Vec3 home = home(entity);
		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);
		int index = Math.floorMod(persistentInt(entity, K_IDLE_PATROL_INDEX, 0), IDLE_PATROL_POINTS);
		int wait = tickStoredTimer(entity, K_IDLE_PATROL_WAIT);
		Vec3 target = idlePatrolPoint(home, seed, index, entity.tickCount, isEscapedContainmentDimension(entity));

		if (wait <= 0 && entity.position().distanceTo(target) <= IDLE_PATROL_REACH_DISTANCE) {
			index = (index + 1) % IDLE_PATROL_POINTS;
			entity.getPersistentData().putInt(K_IDLE_PATROL_INDEX, index);
			entity.getPersistentData().putInt(K_IDLE_PATROL_WAIT, IDLE_PATROL_WAIT_TICKS);
			target = idlePatrolPoint(home, seed, index, entity.tickCount, isEscapedContainmentDimension(entity));
		}

		return target;
	}

	private static Vec3 idlePatrolPoint(Vec3 home, double seed, int index, int tickCount, boolean escapedContainment) {
		double angle = seed + Math.PI * 2.0 * index / IDLE_PATROL_POINTS;
		double bob = Math.sin(tickCount * BOB_SPEED + seed + index) * BOB_AMOUNT;
		double hoverHeight = IDLE_HOVER_HEIGHT + (escapedContainment ? ESCAPED_CONTAINMENT_HOVER_BONUS : 0.0);

		return home.add(
			Math.cos(angle) * IDLE_PATROL_RADIUS,
			hoverHeight + bob,
			Math.sin(angle) * IDLE_PATROL_RADIUS
		);
	}

	private static LivingEntity findTarget(ServerLevel level, Entity self, double x, double y, double z) {
		LivingEntity retaliationTarget = retaliationTarget(self);

		if (retaliationTarget != null && retaliationTarget.distanceTo(self) <= DETECTION_RANGE * 1.6 && canSeeForTargeting(level, self, retaliationTarget)) {
			return retaliationTarget;
		}

		AABB searchBox = new AABB(
			x - DETECTION_RANGE,
			y - DETECTION_RANGE,
			z - DETECTION_RANGE,
			x + DETECTION_RANGE,
			y + DETECTION_RANGE,
			z + DETECTION_RANGE
		);

		return level.getEntitiesOfClass(LivingEntity.class, searchBox, target -> canTargetNormally(self, target))
			.stream()
			.sorted(Comparator.comparingDouble(target -> target.distanceToSqr(self)))
			.limit(TARGET_LOS_CANDIDATE_LIMIT)
			.filter(target -> canSeeForTargeting(level, self, target))
			.min(Comparator.comparingDouble(target -> targetPriorityScore(self, target)))
			.orElse(null);
	}

	private static double targetPriorityScore(Entity self, LivingEntity target) {
		double score = target.distanceToSqr(self);

		if (target instanceof Player) {
			score -= DETECTION_RANGE * DETECTION_RANGE * 2.0;
		}

		return score;
	}

	private static boolean canDamage(Entity self, LivingEntity target) {
		return canTargetNormally(self, target) || isRetaliationTarget(self, target);
	}

	private static boolean canBurstDamage(Entity self, LivingEntity target) {
		return canTargetNormally(self, target) || canRetaliateAgainst(self, target);
	}

	private static boolean canTargetNormally(Entity self, LivingEntity target) {
		if (target == null || target == self || !target.isAlive()) {
			return false;
		}

		if (isFractus(target)) {
			return false;
		}

		if (target instanceof AgeableMob ageableMob && ageableMob.isBaby()) {
			return false;
		}

		if (isSeaAnimal(target)) {
			return false;
		}

		if (target instanceof Player player) {
			return !player.isCreative() && !player.isSpectator();
		}

		// In escaped-containment dimensions, Fractus stops limiting itself to players and hunts any living thing it can see.
		return isEscapedContainmentDimension(self);
	}

	private static boolean isSeaAnimal(LivingEntity target) {
		if (target instanceof WaterAnimal) {
			return true;
		}

		String idPath = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).getPath();
		return "turtle".equals(idPath) || "axolotl".equals(idPath);
	}

	private static boolean canRetaliateAgainst(Entity self, LivingEntity target) {
		return target != null
			&& target != self
			&& target.isAlive()
			&& !isFractus(target)
			&& !(target instanceof AgeableMob ageableMob && ageableMob.isBaby())
			&& !isSeaAnimal(target);
	}

	private static boolean isRetaliationTarget(Entity self, LivingEntity target) {
		return retaliationTarget(self) == target && canRetaliateAgainst(self, target);
	}

	private static LivingEntity retaliationTarget(Entity self) {
		if (self instanceof Mob mob) {
			LivingEntity target = mob.getTarget();

			if (canRetaliateAgainst(self, target)) {
				return target;
			}

			LivingEntity lastHurtBy = mob.getLastHurtByMob();

			if (canRetaliateAgainst(self, lastHurtBy)) {
				return lastHurtBy;
			}
		}

		return null;
	}

	private static boolean isProjectileDamage(DamageSource source) {
		Entity direct = source.getDirectEntity();
		return source.is(DamageTypeTags.IS_PROJECTILE) || direct instanceof Projectile;
	}

	private static void markRetaliationTarget(Entity self, DamageSource source) {
		LivingEntity attacker = sourceAttacker(source);

		if (attacker == null || !canRetaliateAgainst(self, attacker)) {
			return;
		}

		if (self instanceof Mob mob) {
			mob.setTarget(attacker);
		}
	}


	private static LivingEntity sourceAttacker(DamageSource source) {
		if (source == null) {
			return null;
		}

		Entity attacker = source.getEntity();

		if (attacker instanceof LivingEntity livingAttacker) {
			return livingAttacker;
		}

		Entity direct = source.getDirectEntity();

		if (direct instanceof Projectile projectile && projectile.getOwner() instanceof LivingEntity owner) {
			return owner;
		}

		if (direct instanceof LivingEntity directLiving) {
			return directLiving;
		}

		return null;
	}

	private static boolean tryDodgeProjectile(Entity entity, DamageSource source) {
		if (!(entity.level() instanceof ServerLevel level)) {
			return false;
		}

		double dodgeChance = isAngry(entity) ? ANGRY_PROJECTILE_DODGE_CHANCE : PROJECTILE_DODGE_CHANCE;

		if (isEscapedContainmentDimension(entity)) {
			dodgeChance += ESCAPED_PROJECTILE_DODGE_BONUS;
		}

		if (entity.getRandom().nextDouble() > Mth.clamp(dodgeChance, 0.0, 0.85)) {
			return false;
		}

		Vec3 away = entity.position().subtract(source.getDirectEntity() == null ? source.getSourcePosition() == null ? entity.position() : source.getSourcePosition() : source.getDirectEntity().position());
		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = new Vec3(entity.getRandom().nextDouble() - 0.5, 0.0, entity.getRandom().nextDouble() - 0.5);
		}

		horizontalAway = horizontalAway.normalize();
		Vec3 side = new Vec3(-horizontalAway.z, 0.0, horizontalAway.x).scale(entity.getRandom().nextBoolean() ? 1.0 : -1.0);

		for (int i = 0; i < PROJECTILE_DODGE_ATTEMPTS; i++) {
			double distance = 4.5 + entity.getRandom().nextDouble() * 5.5;
			double lift = 0.6 + entity.getRandom().nextDouble() * (isEscapedContainmentDimension(entity) ? 3.0 : 2.0);
			Vec3 candidate = entity.position()
				.add(horizontalAway.scale(distance))
				.add(side.scale((entity.getRandom().nextDouble() - 0.5) * 7.0))
				.add(0.0, lift, 0.0);

			if (!isOpenForDrone(level, entity, candidate)) {
				continue;
			}

			spawnDodgeParticles(level, entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0));
			entity.teleportTo(candidate.x, candidate.y, candidate.z);
			entity.setDeltaMovement(Vec3.ZERO);
			entity.hasImpulse = true;
			level.playSound(null, BlockPos.containing(candidate.x, candidate.y, candidate.z), SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.45f, 0.62f);
			spawnDodgeParticles(level, candidate.add(0.0, entity.getBbHeight() * 0.5, 0.0));
			return true;
		}

		return false;
	}

	private static void faceTarget(Entity entity, LivingEntity target) {
		entity.lookAt(EntityAnchorArgument.Anchor.EYES, target.getEyePosition());

		if (entity instanceof Mob mob) {
			float yRot = (float) (Mth.atan2(target.getZ() - entity.getZ(), target.getX() - entity.getX()) * (180.0F / Math.PI)) - 90.0F;

			mob.setYRot(yRot);
			mob.setYHeadRot(yRot);
			mob.setYBodyRot(yRot);
			mob.getNavigation().stop();
			mob.setTarget(target);
		}
	}

	private static void faceMovement(Entity entity) {
		Vec3 movement = entity.getDeltaMovement();

		if (movement.horizontalDistanceSqr() < 0.0005) {
			return;
		}

		float yRot = (float) (Mth.atan2(movement.z, movement.x) * (180.0F / Math.PI)) - 90.0F;
		entity.setYRot(yRot);

		if (entity instanceof Mob mob) {
			mob.setYHeadRot(yRot);
			mob.setYBodyRot(yRot);
		}
	}

	private static void driftIdleScan(Entity entity) {
		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);
		int side = persistentInt(entity, K_ORBIT_SIDE, 1);
		entity.getPersistentData().putDouble(K_ORBIT_SEED, seed + IDLE_SCAN_SPEED * side);
	}

	private static void shuffleCooldownOrbit(Entity entity) {
		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);
		int side = persistentInt(entity, K_ORBIT_SIDE, 1);
		entity.getPersistentData().putDouble(K_ORBIT_SEED, seed + (Math.PI * 0.65 + entity.getRandom().nextDouble() * Math.PI * 0.45) * side);
	}

	private static void moveCombat(Entity entity, LivingEntity target, boolean attacking, boolean angry, int cooldown, int angerRetreatTicks) {
		if (angerRetreatTicks > 0) {
			moveRetreat(entity, target, angry);
			return;
		}

		if (cooldown > COOLDOWN_TICKS - COOLDOWN_REPOSITION_TICKS) {
			moveCooldownReposition(entity, target, angry);
			return;
		}

		moveLikeDrone(entity, target, attacking, angry);
	}

	private static void moveRetreat(Entity entity, LivingEntity target, boolean angry) {
		Vec3 targetCenter = target.position();
		Vec3 away = entity.position().subtract(targetCenter);
		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = new Vec3(1.0, 0.0, 0.0);
		}

		horizontalAway = horizontalAway.normalize();

		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);
		double bob = Math.sin(entity.tickCount * BOB_SPEED + seed) * BOB_AMOUNT;
		double preferredRange = angry ? ANGRY_PREFERRED_COMBAT_RANGE : PREFERRED_COMBAT_RANGE;
		Vec3 desired = targetCenter
			.add(horizontalAway.scale(preferredRange + RETREAT_RANGE_BONUS))
			.add(0.0, currentHoverHeight(entity, angry) + 1.0 + bob, 0.0);

		moveToward(entity, desired, RETREAT_BURST_SPEED);
	}

	private static void moveCooldownReposition(Entity entity, LivingEntity target, boolean angry) {
		if (entity.level() instanceof ServerLevel level) {
			Optional<Vec3> coverPoint = findCooldownCoverPoint(level, entity, target, angry);

			if (coverPoint.isPresent()) {
				moveToward(entity, coverPoint.get(), COOLDOWN_REPOSITION_SPEED);
				return;
			}
		}

		Vec3 targetCenter = target.position();
		Vec3 away = entity.position().subtract(targetCenter);
		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = new Vec3(1.0, 0.0, 0.0);
		}

		horizontalAway = horizontalAway.normalize();

		Vec3 side = new Vec3(-horizontalAway.z, 0.0, horizontalAway.x)
			.scale(persistentInt(entity, K_ORBIT_SIDE, 1));
		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);
		double bob = Math.sin(entity.tickCount * BOB_SPEED + seed) * BOB_AMOUNT;
		double preferredRange = angry ? ANGRY_PREFERRED_COMBAT_RANGE : PREFERRED_COMBAT_RANGE;
		Vec3 desired = targetCenter
			.add(horizontalAway.scale(preferredRange))
			.add(side.scale(ORBIT_RADIUS * 2.35))
			.add(0.0, currentHoverHeight(entity, angry) + bob, 0.0);

		moveToward(entity, desired, COOLDOWN_REPOSITION_SPEED);
	}

	private static Optional<Vec3> findCooldownCoverPoint(ServerLevel level, Entity entity, LivingEntity target, boolean angry) {
		Vec3 targetEyes = target.getEyePosition();
		Vec3 entityPos = entity.position();
		Vec3 away = entityPos.subtract(target.position());
		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = new Vec3(1.0, 0.0, 0.0);
		}

		horizontalAway = horizontalAway.normalize();
		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);
		double bob = Math.sin(entity.tickCount * BOB_SPEED + seed) * BOB_AMOUNT;
		double preferredRange = angry ? ANGRY_PREFERRED_COMBAT_RANGE : PREFERRED_COMBAT_RANGE;
		Vec3 best = null;
		double bestScore = Double.NEGATIVE_INFINITY;

		for (int i = 0; i < COVER_SEARCH_STEPS; i++) {
			double angle = seed + Math.PI * 2.0 * i / COVER_SEARCH_STEPS;
			Vec3 radial = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
			Vec3 candidate = target.position()
				.add(horizontalAway.scale(preferredRange + 2.0))
				.add(radial.scale(COVER_SEARCH_RADIUS))
				.add(0.0, currentHoverHeight(entity, angry) + bob, 0.0);

			if (!isOpenForDrone(level, entity, candidate) || candidate.distanceTo(target.position()) < COVER_MIN_PLAYER_DISTANCE) {
				continue;
			}

			if (!hasCoverFrom(level, targetEyes, candidate.add(0.0, entity.getBbHeight() * 0.35, 0.0), target)) {
				continue;
			}

			double score = candidate.distanceToSqr(target.position()) - candidate.distanceToSqr(entityPos) * 0.35;

			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}

		return Optional.ofNullable(best);
	}

	private static boolean isOpenForDrone(ServerLevel level, Entity entity, Vec3 center) {
		AABB box = entity.getBoundingBox().move(center.subtract(entity.position())).inflate(0.15);
		return level.noCollision(entity, box) && !hasNearbyHarmfulBlock(level, center, HARMFUL_BLOCK_AVOID_RADIUS);
	}

	private static Optional<Vec3> adjustDesiredForHazards(ServerLevel level, Entity entity, Vec3 desired) {
		if (!hasNearbyHarmfulBlock(level, entity.position(), HARMFUL_BLOCK_AVOID_RADIUS) && !hasNearbyHarmfulBlock(level, desired, HARMFUL_BLOCK_AVOID_RADIUS)) {
			return Optional.empty();
		}

		Vec3 hazardCenter = nearestHarmfulBlockCenter(level, entity.position(), HARMFUL_BLOCK_AVOID_RADIUS + 1.5).orElse(desired);
		Vec3 away = entity.position().subtract(hazardCenter);
		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = new Vec3(entity.getRandom().nextDouble() - 0.5, 0.0, entity.getRandom().nextDouble() - 0.5);
		}

		horizontalAway = horizontalAway.normalize();
		Vec3 escape = entity.position().add(horizontalAway.scale(6.0)).add(0.0, 1.25, 0.0);

		if (isOpenForDrone(level, entity, escape)) {
			entity.setDeltaMovement(entity.getDeltaMovement().add(horizontalAway.scale(HARMFUL_BLOCK_ESCAPE_SPEED * 0.25)).add(0.0, 0.08, 0.0));
			return Optional.of(escape);
		}

		return findOpenMovementPoint(level, entity, entity.position().add(0.0, 3.0, 0.0));
	}

	private static boolean hasNearbyHarmfulBlock(ServerLevel level, Vec3 center, double radius) {
		return nearestHarmfulBlockCenter(level, center, radius).isPresent();
	}

	private static Optional<Vec3> nearestHarmfulBlockCenter(ServerLevel level, Vec3 center, double radius) {
		BlockPos centerPos = BlockPos.containing(center.x, center.y, center.z);
		int blockRadius = (int) Math.ceil(radius);
		Vec3 nearest = null;
		double nearestDistance = Double.MAX_VALUE;

		for (BlockPos pos : BlockPos.betweenClosed(centerPos.offset(-blockRadius, -blockRadius, -blockRadius), centerPos.offset(blockRadius, blockRadius, blockRadius))) {
			BlockState state = level.getBlockState(pos);

			if (!isHarmfulBlock(state)) {
				continue;
			}

			Vec3 blockCenter = pos.getCenter();
			double distance = blockCenter.distanceToSqr(center);

			if (distance <= radius * radius && distance < nearestDistance) {
				nearestDistance = distance;
				nearest = blockCenter;
			}
		}

		return Optional.ofNullable(nearest);
	}

	private static boolean isHarmfulBlock(BlockState state) {
		return state.is(Blocks.TNT)
			|| state.is(Blocks.FIRE)
			|| state.is(Blocks.SOUL_FIRE)
			|| state.is(Blocks.LAVA)
			|| state.is(Blocks.MAGMA_BLOCK)
			|| state.is(Blocks.CAMPFIRE)
			|| state.is(Blocks.SOUL_CAMPFIRE)
			|| state.getFluidState().is(FluidTags.LAVA);
	}

	private static boolean hasCoverFrom(ServerLevel level, Vec3 viewer, Vec3 coveredPoint, Entity target) {
		BlockHitResult blockHit = level.clip(new ClipContext(
			viewer,
			coveredPoint,
			ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE,
			target
		));

		return blockHit.getType() != HitResult.Type.MISS;
	}

	private static void moveFlankForSight(Entity entity, LivingEntity target, boolean angry) {
		Vec3 targetCenter = target.position();
		Vec3 away = entity.position().subtract(targetCenter);
		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = new Vec3(1.0, 0.0, 0.0);
		}

		horizontalAway = horizontalAway.normalize();
		Vec3 side = new Vec3(-horizontalAway.z, 0.0, horizontalAway.x)
			.scale(persistentInt(entity, K_ORBIT_SIDE, 1));
		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);
		double bob = Math.sin(entity.tickCount * BOB_SPEED + seed) * BOB_AMOUNT;
		double preferredRange = angry ? ANGRY_PREFERRED_COMBAT_RANGE : PREFERRED_COMBAT_RANGE;
		Vec3 desired = targetCenter
			.add(horizontalAway.scale(preferredRange + FLANK_RANGE_BONUS))
			.add(side.scale(FLANK_SIDE_DISTANCE))
			.add(0.0, currentHoverHeight(entity, angry) + bob, 0.0);

		moveToward(entity, desired, FLANK_REPOSITION_SPEED);
	}

	private static void moveLikeDrone(Entity entity, LivingEntity target, boolean attacking, boolean angry) {
		Vec3 targetCenter = target.position();
		Vec3 away = entity.position().subtract(targetCenter);
		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = new Vec3(1.0, 0.0, 0.0);
		}

		horizontalAway = horizontalAway.normalize();

		Vec3 side = new Vec3(-horizontalAway.z, 0.0, horizontalAway.x)
			.scale(persistentInt(entity, K_ORBIT_SIDE, 1));

		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);
		double orbitSpeed = angry ? ANGRY_ORBIT_SPEED : ORBIT_SPEED;
		double orbitPulse = Math.sin(entity.tickCount * orbitSpeed + seed) * ORBIT_RADIUS;
		double bob = Math.sin(entity.tickCount * BOB_SPEED + seed) * BOB_AMOUNT;
		double currentDistance = entity.distanceTo(target);
		double preferredRange = angry ? ANGRY_PREFERRED_COMBAT_RANGE : PREFERRED_COMBAT_RANGE;
		double desiredRange = currentDistance < TOO_CLOSE_RANGE ? preferredRange + 3.0 : preferredRange;

		Vec3 desired = targetCenter
			.add(horizontalAway.scale(desiredRange))
			.add(side.scale(orbitPulse))
			.add(0.0, currentHoverHeight(entity, angry) + bob, 0.0);

		double maxSpeed = angry ? ANGRY_MAX_DRONE_SPEED : MAX_DRONE_SPEED;
		moveToward(entity, desired, attacking ? maxSpeed * 0.55 : maxSpeed);
	}

	private static void moveToward(Entity entity, Vec3 desired, double maxSpeed) {
		if (entity.level() instanceof ServerLevel level) {
			desired = adjustDesiredForHazards(level, entity, desired).orElse(desired);
			desired = adjustDesiredForOpenSpace(level, entity, desired);
		}

		Vec3 toDesired = desired.subtract(entity.position());
		Vec3 acceleration = toDesired.scale(DRONE_ACCELERATION);
		Vec3 next = entity.getDeltaMovement().scale(0.82).add(acceleration);

		double horizontalSpeed = Math.sqrt(next.x * next.x + next.z * next.z);

		if (horizontalSpeed > maxSpeed) {
			double scale = maxSpeed / horizontalSpeed;
			next = new Vec3(next.x * scale, next.y, next.z * scale);
		}

		next = new Vec3(
			next.x,
			Mth.clamp(next.y, -VERTICAL_SPEED_LIMIT, VERTICAL_SPEED_LIMIT),
			next.z
		);

		entity.setDeltaMovement(next);
		entity.hasImpulse = true;
	}

	private static Vec3 adjustDesiredForOpenSpace(ServerLevel level, Entity entity, Vec3 desired) {
		Vec3 position = entity.position();
		double movedSqr = position.distanceToSqr(
			persistentDouble(entity, K_LAST_X, position.x),
			persistentDouble(entity, K_LAST_Y, position.y),
			persistentDouble(entity, K_LAST_Z, position.z)
		);
		boolean tryingToMove = desired.distanceToSqr(position) > 4.0;
		boolean stuck = tryingToMove && movedSqr < 0.0125;
		AABB nextBox = entity.getBoundingBox().move(desired.subtract(position).normalize().scale(0.6));
		boolean blockedAhead = !level.noCollision(entity, nextBox);
		int stuckTicks = stuck || blockedAhead ? persistentInt(entity, K_STUCK_TICKS, 0) + 1 : Math.max(0, persistentInt(entity, K_STUCK_TICKS, 0) - 1);

		entity.getPersistentData().putInt(K_STUCK_TICKS, stuckTicks);
		entity.getPersistentData().putDouble(K_LAST_X, position.x);
		entity.getPersistentData().putDouble(K_LAST_Y, position.y);
		entity.getPersistentData().putDouble(K_LAST_Z, position.z);

		if (!blockedAhead && stuckTicks < STUCK_ESCAPE_TICKS) {
			return desired;
		}

		return findOpenMovementPoint(level, entity, desired).orElse(desired);
	}

	private static Optional<Vec3> findOpenMovementPoint(ServerLevel level, Entity entity, Vec3 desired) {
		Vec3 origin = entity.position();
		Vec3 best = null;
		double bestScore = Double.MAX_VALUE;
		double seed = persistentDouble(entity, K_ORBIT_SEED, 0.0);

		for (int vertical = 0; vertical <= 3; vertical++) {
			double yOffset = vertical == 0 ? 1.4 : (vertical == 1 ? 2.8 : (vertical == 2 ? 0.0 : -1.0));

			for (int i = 0; i < OPEN_SPACE_SEARCH_STEPS; i++) {
				double angle = seed + Math.PI * 2.0 * i / OPEN_SPACE_SEARCH_STEPS;
				double radius = OPEN_SPACE_SEARCH_RADIUS * (0.55 + 0.45 * (i % 3) / 2.0);
				Vec3 candidate = origin.add(Math.cos(angle) * radius, Mth.clamp(desired.y - origin.y + yOffset, -OPEN_SPACE_VERTICAL_RANGE, OPEN_SPACE_VERTICAL_RANGE), Math.sin(angle) * radius);

				if (!isOpenForDrone(level, entity, candidate)) {
					continue;
				}

				BlockHitResult path = clipBlocks(level, entity, origin.add(0.0, entity.getBbHeight() * 0.5, 0.0), candidate.add(0.0, entity.getBbHeight() * 0.5, 0.0));

				if (path.getType() != HitResult.Type.MISS) {
					continue;
				}

				double score = candidate.distanceToSqr(desired) + candidate.distanceToSqr(origin) * 0.25 - yOffset * 1.5;

				if (score < bestScore) {
					bestScore = score;
					best = candidate;
				}
			}
		}

		return Optional.ofNullable(best);
	}

	private static boolean canSeeForTargeting(ServerLevel level, Entity self, LivingEntity target) {
		Vec3 start = laserStart(self);
		Vec3 targetEyes = target.getEyePosition();
		BlockHitResult blockHit = clipBlocks(level, self, start, targetEyes);

		return blockHit.getType() == HitResult.Type.MISS
			|| blockHit.getLocation().distanceToSqr(start) + 0.35 >= targetEyes.distanceToSqr(start);
	}

	private static boolean hasClearShot(ServerLevel level, Entity self, LivingEntity target, double maxRange) {
		Vec3 start = laserStart(self);
		Vec3 targetEyes = target.getEyePosition();

		if (start.distanceTo(targetEyes) > maxRange) {
			return false;
		}

		BlockHitResult blockHit = clipBlocks(level, self, start, targetEyes);

		return blockHit.getType() == HitResult.Type.MISS
			|| blockHit.getLocation().distanceToSqr(start) + 0.35 >= targetEyes.distanceToSqr(start);
	}

	private static boolean isNearbyFractusFiring(ServerLevel level, Entity self) {
		AABB searchBox = self.getBoundingBox().inflate(SUPPRESSION_RANGE);

		for (Entity entity : level.getEntities(self, searchBox, FractusOnEntityTickUpdateProcedure::isFractus)) {
			if (persistentInt(entity, K_LASER_STATE, 0) == 3 || persistentInt(entity, K_FIRE, 0) > 0) {
				return true;
			}
		}

		return false;
	}

	private static Vec3 predictedTargetEyePosition(LivingEntity target, boolean angry) {
		double leadTicks = angry ? ANGRY_TARGET_LEAD_TICKS : TARGET_LEAD_TICKS;
		return target.getEyePosition().add(target.getDeltaMovement().scale(leadTicks));
	}

	private static Vec3 updateLaserAim(Entity self, LivingEntity target, boolean angry, boolean firing) {
		Vec3 start = laserStart(self);
		Vec3 desired = predictedTargetEyePosition(target, angry).subtract(start);

		if (desired.lengthSqr() < 0.001) {
			desired = self.getLookAngle();
		}

		desired = desired.normalize();

		Vec3 current = new Vec3(
			persistentDouble(self, K_AIM_X, 0.0),
			persistentDouble(self, K_AIM_Y, 0.0),
			persistentDouble(self, K_AIM_Z, 0.0)
		);

		if (current.lengthSqr() < 0.001) {
			current = desired;
		} else {
			current = current.normalize();
		}

		double turnRate = firing
			? (angry ? ANGRY_FIRING_AIM_TURN_RATE : FIRING_AIM_TURN_RATE)
			: (angry ? ANGRY_AIM_LOCK_TURN_RATE : AIM_LOCK_TURN_RATE);
		Vec3 adjusted = rotateToward(current, desired, turnRate);

		self.getPersistentData().putDouble(K_AIM_X, adjusted.x);
		self.getPersistentData().putDouble(K_AIM_Y, adjusted.y);
		self.getPersistentData().putDouble(K_AIM_Z, adjusted.z);

		return adjusted;
	}

	private static Vec3 rotateToward(Vec3 current, Vec3 desired, double maxRadians) {
		double dot = Mth.clamp(current.dot(desired), -1.0, 1.0);
		double angle = Math.acos(dot);

		if (angle <= maxRadians || angle < 0.0001) {
			return desired;
		}

		double blend = maxRadians / angle;
		return current.scale(1.0 - blend).add(desired.scale(blend)).normalize();
	}

	private static LaserHit raycastLaser(ServerLevel level, Entity self, Vec3 direction, double maxRange) {
		Vec3 start = laserStart(self);

		if (direction.lengthSqr() < 0.001) {
			direction = self.getLookAngle();
		}

		Vec3 end = start.add(direction.normalize().scale(maxRange));

		BlockHitResult blockHit = clipBlocks(level, self, start, end);
		Vec3 blockedEnd = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();

		EntityHitResult entityHit = clipEntities(level, self, start, blockedEnd);

		if (entityHit != null && entityHit.getEntity() instanceof LivingEntity living && canDamage(self, living)) {
			return new LaserHit(entityHit.getLocation(), living, null, null);
		}

		return new LaserHit(blockedEnd, null, blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getBlockPos(), blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getDirection());
	}

	private static Vec3 laserStart(Entity entity) {
		if (entity instanceof LivingEntity living) {
			return living.getEyePosition().subtract(0.0, living.getBbHeight() * 0.18, 0.0);
		}

		return entity.position().add(0.0, entity.getBbHeight() * 0.65, 0.0);
	}

	private static BlockHitResult clipBlocks(ServerLevel level, Entity self, Vec3 start, Vec3 end) {
		return level.clip(new ClipContext(
			start,
			end,
			ClipContext.Block.COLLIDER,
			ClipContext.Fluid.NONE,
			self
		));
	}

	private static EntityHitResult clipEntities(ServerLevel level, Entity self, Vec3 start, Vec3 end) {
		Vec3 ray = end.subtract(start);
		AABB search = self.getBoundingBox().expandTowards(ray).inflate(1.0);

		Entity closestEntity = null;
		Vec3 closestHit = null;
		double closestDistance = Double.MAX_VALUE;

		for (Entity candidate : level.getEntities(self, search, candidate -> candidate instanceof LivingEntity living && canDamage(self, living))) {
			AABB hitBox = candidate.getBoundingBox().inflate(0.25);
			Optional<Vec3> optionalHit = hitBox.clip(start, end);

			if (hitBox.contains(start)) {
				optionalHit = Optional.of(start);
			}

			if (optionalHit.isEmpty()) {
				continue;
			}

			double distance = start.distanceToSqr(optionalHit.get());

			if (distance < closestDistance) {
				closestDistance = distance;
				closestEntity = candidate;
				closestHit = optionalHit.get();
			}
		}

		return closestEntity == null ? null : new EntityHitResult(closestEntity, closestHit);
	}

	private static void igniteLaserHitBlock(ServerLevel level, BlockPos blockPos, Direction blockFace) {
		if (blockPos == null) {
			return;
		}

		BlockPos firePos = blockFace == null ? blockPos : blockPos.relative(blockFace);

		if (!level.getBlockState(firePos).isAir()) {
			return;
		}

		BlockState fireState = Blocks.FIRE.defaultBlockState();

		if (fireState.canSurvive(level, firePos)) {
			level.setBlock(firePos, fireState, 3);
		}
	}

	private static void destroyWeakBlocksInLaserPath(ServerLevel level, Entity entity, Vec3 start, Vec3 end, BlockPos blockedPos, boolean angry) {
		Vec3 line = end.subtract(start);
		double length = line.length();

		if (length < 0.01) {
			return;
		}

		Vec3 direction = line.normalize();
		BlockPos lastPos = null;
		float maxHardness = angry ? ANGRY_WEAK_BLOCK_MAX_HARDNESS : WEAK_BLOCK_MAX_HARDNESS;

		for (double d = 0.0; d <= length; d += BLOCK_BREAK_STEP) {
			Vec3 sample = start.add(direction.scale(d));
			BlockPos pos = BlockPos.containing(sample.x, sample.y, sample.z);

			if (pos.equals(lastPos)) {
				continue;
			}

			lastPos = pos;
			BlockState state = level.getBlockState(pos);

			if (state.isAir()) {
				continue;
			}

			float hardness = state.getDestroySpeed(level, pos);

			// Do not break unbreakable blocks.
			if (hardness < 0.0f) {
				break;
			}

			// Stop at strong blocks; the final raycast will hit this obstruction.
			if (hardness > maxHardness) {
				break;
			}

			// Avoid deleting blocks with block entities, like chests or special modded containers.
			if (state.hasBlockEntity()) {
				break;
			}

			level.destroyBlock(pos, false, entity);
		}

		if (blockedPos != null) {
			destroyWeakBlock(level, entity, blockedPos, maxHardness);
		}
	}

	private static void destroyWeakBlock(ServerLevel level, Entity entity, BlockPos pos, float maxHardness) {
		BlockState state = level.getBlockState(pos);

		if (evaporateWaterAt(level, pos, state)) {
			return;
		}

		if (state.isAir()) {
			return;
		}

		float hardness = state.getDestroySpeed(level, pos);

		if (hardness < 0.0f || hardness > maxHardness || state.hasBlockEntity()) {
			return;
		}

		level.destroyBlock(pos, false, entity);
	}

	private static void handleBurstLaser(ServerLevel level, Entity entity, LivingEntity target, int burstTicks) {
		int elapsed = BURST_TOTAL_TICKS - burstTicks;
		Vec3 start = laserStart(entity);
		Vec3 direction = burstDirection(entity, target, start);
		Vec3 end = start.add(direction.scale(BURST_LASER_RANGE));

		if (elapsed < BURST_FIRE_PEAK_TICK) {
			spawnBurstBuildup(level, entity, start, elapsed);
			return;
		}

		if (elapsed <= BURST_CORE_END_TICK) {
			BlockHitResult blockHit = clipBlocks(level, entity, start, end);
			Vec3 blockedEnd = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();
			destroyBurstBlocksInLaserPath(level, entity, start, blockedEnd, blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getBlockPos());
			blockHit = clipBlocks(level, entity, start, end);
			blockedEnd = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();
			igniteLaserHitBlock(level, blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getBlockPos(), blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getDirection());

			spawnBurstBeam(level, start, blockedEnd, elapsed);

			if (elapsed == BURST_FIRE_PEAK_TICK) {
				damageBurstEntities(level, entity, start, blockedEnd);
			}

			return;
		}

		BlockHitResult blockHit = clipBlocks(level, entity, start, end);
		Vec3 impact = blockHit.getType() == HitResult.Type.MISS ? end : blockHit.getLocation();
		igniteLaserHitBlock(level, blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getBlockPos(), blockHit.getType() == HitResult.Type.MISS ? null : blockHit.getDirection());
		spawnBurstDissipation(level, impact, direction, elapsed);
	}

	private static Vec3 burstDirection(Entity entity, LivingEntity target, Vec3 start) {
		LivingEntity burstTarget = target != null ? target : retaliationTarget(entity);

		if (burstTarget != null) {
			Vec3 aim = burstTarget.getEyePosition().subtract(start);

			if (aim.lengthSqr() > 0.001) {
				return aim.normalize();
			}
		}

		Vec3 aim = new Vec3(
			persistentDouble(entity, K_AIM_X, 0.0),
			persistentDouble(entity, K_AIM_Y, 0.0),
			persistentDouble(entity, K_AIM_Z, 0.0)
		);

		if (aim.lengthSqr() > 0.001) {
			return aim.normalize();
		}

		return entity.getLookAngle().normalize();
	}

	private static void destroyBurstBlocksInLaserPath(ServerLevel level, Entity entity, Vec3 start, Vec3 end, BlockPos blockedPos) {
		Vec3 line = end.subtract(start);
		double length = line.length();

		if (length < 0.01) {
			return;
		}

		Vec3 direction = line.normalize();
		int radius = (int) Math.ceil(BURST_LASER_RADIUS);

		for (double d = 0.0; d <= length; d += BURST_BLOCK_BREAK_STEP) {
			Vec3 center = start.add(direction.scale(d));
			BlockPos centerPos = BlockPos.containing(center.x, center.y, center.z);

			for (BlockPos pos : BlockPos.betweenClosed(centerPos.offset(-radius, -radius, -radius), centerPos.offset(radius, radius, radius))) {
				if (pos.getCenter().distanceTo(center) > BURST_LASER_RADIUS + 0.35) {
					continue;
				}

				destroyWeakBlock(level, entity, pos, BURST_BLOCK_MAX_HARDNESS);
			}
		}

		if (blockedPos != null) {
			destroyWeakBlock(level, entity, blockedPos, BURST_BLOCK_MAX_HARDNESS);
		}
	}

	private static boolean evaporateWaterAt(ServerLevel level, BlockPos pos, BlockState state) {
		if (!state.getFluidState().is(FluidTags.WATER)) {
			return false;
		}

		if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
			level.setBlock(pos, state.setValue(BlockStateProperties.WATERLOGGED, false), 3);
		} else {
			level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
		}

		return true;
	}

	private static void damageBurstEntities(ServerLevel level, Entity entity, Vec3 start, Vec3 end) {
		Vec3 line = end.subtract(start);
		double lengthSqr = line.lengthSqr();

		if (lengthSqr < 0.001) {
			return;
		}

		AABB search = new AABB(start, end).inflate(BURST_LASER_RADIUS + 1.0);

		for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class, search, target -> canBurstDamage(entity, target))) {
			Vec3 toCandidate = candidate.getEyePosition().subtract(start);
			double t = Mth.clamp(toCandidate.dot(line) / lengthSqr, 0.0, 1.0);
			Vec3 closest = start.add(line.scale(t));

			if (candidate.getEyePosition().distanceTo(closest) <= BURST_LASER_RADIUS + candidate.getBbWidth() * 0.5) {
				candidate.hurt(new DamageSource(level.holderOrThrow(DamageTypes.MAGIC)), BURST_LASER_DAMAGE);
				applyBurstKnockback(candidate, start, closest);
			}
		}
	}

	private static void applyBurstKnockback(LivingEntity target, Vec3 start, Vec3 closestBeamPoint) {
		Vec3 away = target.position().add(0.0, target.getBbHeight() * 0.5, 0.0).subtract(closestBeamPoint);

		if (away.lengthSqr() < 0.001) {
			away = target.position().subtract(start);
		}

		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = target.getLookAngle().scale(-1.0);
		}

		horizontalAway = new Vec3(horizontalAway.x, 0.0, horizontalAway.z).normalize();
		Vec3 knockback = horizontalAway.scale(BURST_KNOCKBACK_HORIZONTAL).add(0.0, BURST_KNOCKBACK_VERTICAL, 0.0);
		target.setDeltaMovement(target.getDeltaMovement().add(knockback));
		target.hasImpulse = true;
	}

	private static void spawnBurstBuildup(ServerLevel level, Entity entity, Vec3 center, int elapsed) {
		double progress = Mth.clamp((double) elapsed / BURST_FIRE_PEAK_TICK, 0.0, 1.0);
		int count = 35 + (int) (progress * 85.0);
		double radius = 2.8 - progress * 2.1;
		DustParticleOptions particle = burstChargeParticle(progress);

		for (int i = 0; i < count; i++) {
			double angle = entity.getRandom().nextDouble() * Math.PI * 2.0;
			double y = (entity.getRandom().nextDouble() - 0.5) * 2.2;
			Vec3 pos = center.add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
			level.sendParticles(particle, pos.x, pos.y, pos.z, 1, 0.08, 0.08, 0.08, 0.0);
		}
	}

	private static void spawnBurstBeam(ServerLevel level, Vec3 start, Vec3 end, int elapsed) {
		Vec3 line = end.subtract(start);
		double length = line.length();

		if (length < 0.01) {
			return;
		}

		Vec3 direction = line.normalize();
		double ringRadius = elapsed == BURST_FIRE_PEAK_TICK ? BURST_LASER_RADIUS * 1.8 : BURST_LASER_RADIUS;

		for (double d = 0.0; d <= length; d += 0.18) {
			Vec3 center = start.add(direction.scale(d));
			level.sendParticles(BURST_LASER_PARTICLE, center.x, center.y, center.z, 3, 0.12, 0.12, 0.12, 0.0);

			for (int i = 0; i < 6; i++) {
				double angle = Math.PI * 2.0 * i / 6.0 + d * 0.35;
				Vec3 pos = center.add(Math.cos(angle) * ringRadius, Math.sin(angle * 0.7) * ringRadius * 0.35, Math.sin(angle) * ringRadius);
				level.sendParticles(BURST_LASER_PARTICLE, pos.x, pos.y, pos.z, 1, 0.05, 0.05, 0.05, 0.0);
			}
		}

		level.sendParticles(BURST_LASER_PARTICLE, end.x, end.y, end.z, 70, 0.8, 0.8, 0.8, 0.0);
	}

	private static void spawnBurstDissipation(ServerLevel level, Vec3 impact, Vec3 direction, int elapsed) {
		double progress = Mth.clamp((double) (elapsed - BURST_CORE_END_TICK) / (BURST_TOTAL_TICKS - BURST_CORE_END_TICK), 0.0, 1.0);

		if (elapsed % 4 == 0) {
			int count = 28 - (int) (progress * 18.0);
			level.sendParticles(BURST_LASER_PARTICLE, impact.x, impact.y, impact.z, Math.max(6, count), 1.2 + progress * 2.2, 0.7, 1.2 + progress * 2.2, 0.0);
		}

		if (elapsed % 3 != 0) {
			return;
		}

		Vec3 up = Math.abs(direction.y) > 0.92 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
		Vec3 side = direction.cross(up).normalize();
		Vec3 verticalSide = direction.cross(side).normalize();
		double radius = 0.9 + progress * 5.6;
		int ringCount = 40;

		for (int i = 0; i < ringCount; i++) {
			double angle = Math.PI * 2.0 * i / ringCount;
			Vec3 pos = impact.add(side.scale(Math.cos(angle) * radius)).add(verticalSide.scale(Math.sin(angle) * radius));
			level.sendParticles(BURST_LASER_PARTICLE, pos.x, pos.y, pos.z, 1, 0.04, 0.04, 0.04, 0.0);
		}
	}

	private static void spawnDodgeParticles(ServerLevel level, Vec3 center) {
		level.sendParticles(ANGRY_LASER_PARTICLE, center.x, center.y, center.z, 26, 0.35, 0.35, 0.35, 0.0);
	}

	private static DustParticleOptions laserParticle(boolean angry) {
		return angry ? ANGRY_LASER_PARTICLE : NORMAL_LASER_PARTICLE;
	}

	private static DustParticleOptions burstChargeParticle(double progress) {
		if (progress < 0.45) {
			return BURST_CHARGE_START_PARTICLE;
		}

		if (progress < 0.75) {
			return BURST_CHARGE_MID_PARTICLE;
		}

		return BURST_LASER_PARTICLE;
	}

	private static double currentHoverHeight(Entity entity, boolean angry) {
		double height = angry ? ANGRY_HOVER_HEIGHT : HOVER_HEIGHT;

		if (isEscapedContainmentDimension(entity)) {
			height += ESCAPED_CONTAINMENT_HOVER_BONUS;
		}

		return height;
	}

	private static void spawnLaser(ServerLevel level, Vec3 start, Vec3 end, double spacing, double jitter, boolean firing, boolean angry) {
		Vec3 line = end.subtract(start);
		double length = line.length();

		if (length < 0.01) {
			return;
		}

		Vec3 direction = line.normalize();
		Vec3 up = Math.abs(direction.y) > 0.92 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
		Vec3 side = direction.cross(up).normalize();
		Vec3 verticalSide = direction.cross(side).normalize();
		DustParticleOptions particle = laserParticle(angry);
		double time = level.getGameTime() * 0.32;

		for (double d = 0.0; d <= length; d += spacing) {
			Vec3 pos = start.add(direction.scale(d));

			if (firing) {
				double spiral = d * 1.85 + time;
				double offset = FIRING_SPIRAL_RADIUS * (0.65 + 0.35 * Math.sin(d * 0.7 + time));
				pos = pos.add(side.scale(Math.cos(spiral) * offset)).add(verticalSide.scale(Math.sin(spiral) * offset));
			}

			level.sendParticles(particle, pos.x, pos.y, pos.z, 1, jitter, jitter, jitter, 0.0);
		}

		if (firing) {
			level.sendParticles(particle, end.x, end.y, end.z, 10, 0.08, 0.08, 0.08, 0.0);
		}
	}

	private static void spawnChargeBeamHum(ServerLevel level, Vec3 start, Vec3 end, double progress, boolean angry) {
		Vec3 line = end.subtract(start);
		double length = line.length();

		if (length < 0.01) {
			return;
		}

		Vec3 direction = line.normalize();
		DustParticleOptions particle = laserParticle(angry);
		double spacing = Mth.lerp(progress, 1.35, 0.34);
		double pulse = level.getGameTime() * 0.18;

		for (double d = 0.0; d <= length; d += spacing) {
			double flicker = 0.5 + 0.5 * Math.sin(d * 1.7 + pulse);
			if (flicker < 0.35 - progress * 0.2) {
				continue;
			}

			Vec3 pos = start.add(direction.scale(d));
			level.sendParticles(particle, pos.x, pos.y, pos.z, 1, 0.018 + progress * 0.025, 0.018 + progress * 0.025, 0.018 + progress * 0.025, 0.0);
		}
	}

	private static void spawnChargeParticles(ServerLevel level, Entity entity, int chargeTicks, boolean angry) {
		Vec3 center = laserStart(entity);
		double progress = Mth.clamp((double) chargeTicks / CHARGE_TICKS, 0.0, 1.0);
		int count = angry ? 5 + (int) (progress * 12.0) : 2 + (int) (progress * 7.0);
		double radius = angry ? 0.75 - progress * 0.36 : 0.55 - progress * 0.28;

		for (int i = 0; i < count; i++) {
			double angle = entity.getRandom().nextDouble() * Math.PI * 2.0;
			double y = (entity.getRandom().nextDouble() - 0.5) * 0.55;
			Vec3 pos = center.add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
			level.sendParticles(laserParticle(angry), pos.x, pos.y, pos.z, 1, 0.02, 0.02, 0.02, 0.0);
		}
	}

	private static void spawnChargeInterruptParticles(ServerLevel level, Entity entity) {
		Vec3 center = laserStart(entity);

		for (int i = 0; i < 22; i++) {
			double angle = Math.PI * 2.0 * i / 22.0;
			double radius = 0.18 + i * 0.012;
			Vec3 pos = center.add(Math.cos(angle) * radius, (entity.getRandom().nextDouble() - 0.5) * 0.35, Math.sin(angle) * radius);
			level.sendParticles(NORMAL_LASER_PARTICLE, pos.x, pos.y, pos.z, 1, 0.04, 0.04, 0.04, 0.0);
		}
	}

	private static void spawnSuppressionPulse(ServerLevel level, Entity entity, int suppressionTicks) {
		if (suppressionTicks % 8 != 0) {
			return;
		}

		Vec3 center = laserStart(entity);
		double radius = 0.22 + (SUPPRESSION_TICKS - suppressionTicks) * 0.004;

		for (int i = 0; i < 14; i++) {
			double angle = Math.PI * 2.0 * i / 14.0;
			Vec3 pos = center.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
			level.sendParticles(laserParticle(isAngry(entity)), pos.x, pos.y, pos.z, 1, 0.018, 0.018, 0.018, 0.0);
		}
	}

	private static void spawnLeashBreakTrail(ServerLevel level, Entity entity) {
		Vec3 center = laserStart(entity);
		Vec3 movement = entity.getDeltaMovement();

		for (int i = 0; i < 5; i++) {
			Vec3 pos = center.subtract(movement.scale(i * 0.7));
			level.sendParticles(laserParticle(isAngry(entity)), pos.x, pos.y, pos.z, 1, 0.06, 0.06, 0.06, 0.0);
		}
	}

	private static void spawnVulnerabilityParticles(ServerLevel level, Entity entity, int vulnerabilityTicks) {
		if (vulnerabilityTicks % 3 != 0) {
			return;
		}

		Vec3 center = laserStart(entity);
		double progress = (double) vulnerabilityTicks / VULNERABILITY_TICKS;
		int count = 3 + (int) (progress * 5.0);

		for (int i = 0; i < count; i++) {
			double angle = entity.getRandom().nextDouble() * Math.PI * 2.0;
			double radius = 0.18 + entity.getRandom().nextDouble() * 0.32;
			double y = (entity.getRandom().nextDouble() - 0.5) * 0.35;
			Vec3 pos = center.add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
			level.sendParticles(laserParticle(isAngry(entity)), pos.x, pos.y, pos.z, 1, 0.035, 0.035, 0.035, 0.0);
		}
	}

	private static void spawnShieldParticles(ServerLevel level, Entity entity) {
		Vec3 center = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
		int count = 28;
		double radius = Math.max(entity.getBbWidth(), entity.getBbHeight()) * 0.65;

		for (int i = 0; i < count; i++) {
			double angle = Math.PI * 2.0 * i / count;
			Vec3 pos = center.add(Math.cos(angle) * radius, Math.sin(angle * 2.0) * 0.18, Math.sin(angle) * radius);
			level.sendParticles(NORMAL_LASER_PARTICLE, pos.x, pos.y, pos.z, 1, 0.025, 0.025, 0.025, 0.0);
		}
	}

	private static void spawnPreFireWarning(ServerLevel level, Entity entity, boolean angry) {
		Vec3 center = laserStart(entity);
		int count = angry ? 42 : 26;
		double radius = angry ? 0.42 : 0.30;

		for (int i = 0; i < count; i++) {
			double angle = Math.PI * 2.0 * i / count;
			Vec3 pos = center.add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
			level.sendParticles(laserParticle(angry), pos.x, pos.y, pos.z, 1, 0.025, 0.025, 0.025, 0.0);
		}

		level.sendParticles(laserParticle(angry), center.x, center.y, center.z, angry ? 18 : 10, 0.08, 0.08, 0.08, 0.0);
	}

	private static void spawnFireStartWarning(ServerLevel level, Entity entity, boolean angry) {
		Vec3 center = laserStart(entity);
		DustParticleOptions particle = laserParticle(angry);
		int count = angry ? 64 : 46;
		double radius = angry ? FIRE_START_RING_RADIUS * 1.18 : FIRE_START_RING_RADIUS;

		for (int i = 0; i < count; i++) {
			double angle = Math.PI * 2.0 * i / count;
			Vec3 outward = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
			Vec3 pos = center.add(outward.scale(radius));
			level.sendParticles(particle, pos.x, pos.y, pos.z, 0, outward.x, 0.0, outward.z, angry ? 0.12 : 0.09);
		}

		level.sendParticles(particle, center.x, center.y, center.z, angry ? 30 : 18, 0.12, 0.12, 0.12, 0.0);
	}

	private static void spawnPlayerLaserImpact(ServerLevel level, Vec3 impact, boolean angry) {
		DustParticleOptions particle = laserParticle(angry);
		level.sendParticles(particle, impact.x, impact.y, impact.z, angry ? 35 : 26, 0.24, 0.24, 0.24, 0.0);
	}

	private static void spawnAngerTransitionParticles(ServerLevel level, Entity entity) {
		Vec3 center = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
		int count = 72;

		for (int i = 0; i < count; i++) {
			double angle = Math.PI * 2.0 * i / count;
			double yWave = Math.sin(angle * 3.0) * 0.24;
			Vec3 outward = new Vec3(Math.cos(angle), yWave, Math.sin(angle)).normalize();
			Vec3 pos = center.add(outward.scale(0.45));
			level.sendParticles(ANGRY_LASER_PARTICLE, pos.x, pos.y, pos.z, 0, outward.x, outward.y, outward.z, 0.16);
		}

		level.sendParticles(ANGRY_LASER_PARTICLE, center.x, center.y, center.z, 34, 0.32, 0.32, 0.32, 0.0);
	}

	private static void spawnDeathBurst(ServerLevel level, Entity entity) {
		Vec3 center = entity.position().add(0.0, entity.getBbHeight() * 0.5, 0.0);
		DustParticleOptions particle = laserParticle(isAngry(entity));
		int count = 96;

		for (int ring = 0; ring < 4; ring++) {
			double radius = 0.55 + ring * 0.34;
			double y = (ring - 1.5) * 0.16;
			double speed = 0.12 + ring * 0.035;

			for (int i = 0; i < count; i += 2) {
				double angle = Math.PI * 2.0 * i / count;
				Vec3 outward = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
				Vec3 pos = center.add(outward.scale(radius)).add(0.0, y, 0.0);
				level.sendParticles(particle, pos.x, pos.y, pos.z, 0, outward.x, 0.015 * (ring - 1.5), outward.z, speed);
			}
		}

		level.sendParticles(particle, center.x, center.y, center.z, 36, 0.28, 0.28, 0.28, 0.0);
	}

	private record LaserHit(Vec3 location, Entity entity, BlockPos blockPos, Direction blockFace) {
	} // 1.21.8
}
