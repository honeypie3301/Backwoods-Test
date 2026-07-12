package net.mcreator.thebackwoods.procedures;
// 1.21.1 - Sentinel Adaptive Overhaul
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.Event;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Mth;
import net.minecraft.tags.TagKey;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.arguments.EntityAnchorArgument;

import net.mcreator.thebackwoods.entity.RotEntity;

import javax.annotation.Nullable;

import java.util.Comparator;
import java.util.List;

@EventBusSubscriber
public class RotOnEntityTickUpdateProcedure {
	// =========================================================================
	//                          CONFIGURABLE SETTINGS
	// =========================================================================
	public static boolean DEBUG_MODE = true; // DEBUG MODE: ON/OFF (Set to true to enable testing via chat commands 'slam' and 'overhead')
	public static boolean ENABLE_EXTRACTION_GRAPPLE = false; // Toggle extraction grapple ability
	public static boolean ENABLE_TELEKINESIS = false; // Toggle telekinesis ability
	public static boolean ENABLE_BLOCKING = true; // Toggle blocking ability
	public static double COOLDOWN_MULTIPLIER = 1.5; // Global cooldown multiplier (e.g., 2.0 = double cooldown, 0.5 = half cooldown)
	private static final double TARGET_RANGE = 96.0;

	// --- Movement & Spacing Settings ---
	public static double ROT_PLAYER_BACK_OFF_DISTANCE = 1.6; // Configurable minimum safe distance to maintain from player (was 0.5)
	public static double ROT_PILLAR_BACK_OFF_DISTANCE = 15.0; // Configurable distance to maintain while circling a player's pillar (Scene 2)
	public static double ROT_PILLAR_INITIAL_ATTACK_DELAY = 1000.0; // Configurable delay in ticks (e.g., 100 ticks = 5 seconds) before any attack can be launched after starting to circle (Scene 2)
	public static double ROT_PILLAR_ATTACK_CHANCE = 0.005; // Configurable chance per tick to attack while circling target's pillar (e.g., 0.005 = 0.5% chance, lower is slower/waits longer)
	public static double ROT_PILLAR_CIRCLING_SPEED = 0.8; // Configurable cruising speed factor (as a multiplier of base speed) during circling (Scene 2)
	public static double ROT_PILLAR_CIRCLING_CHANCE = 0.15; // Configurable ratio of circling vs staring (e.g., 0.15 = 15% circling, 85% staring) (Scene 2)

	// --- Sonic Scream Settings ---
	public static double SONIC_SCREAM_COOLDOWN = 1200.0; // Strictly 1 minute cooldown in ticks (60s * 20)

	// --- Sonic Boom Settings ---
	public static double SONIC_BOOM_RANGE = 24.0;             // Configurable sonic boom max reach/range
	public static double SONIC_BOOM_MIN_DIST = 2.5;           // Target must be at least this far away (totem or no totem)
	public static double SONIC_BOOM_COOLDOWN = 324.0;         // Cooldown in ticks (16.2s - increased by 35%)
	public static double SONIC_BOOM_TORSO_Y_FACTOR = 1;       // Configurable Y level offset factor for sonic boom torso relative to entity height
	public static double WARDEN_LEARN_REQUIRED_TICKS = 405.0; // Ticks of combat against a Warden required to learn the sonic boom (increased by 35%)
	public static double SONIC_BOOM_ANIMATION_TICKS = 90.0;  // Total ticks for the sonic boom animation state (50 ticks = 2.5s)
	public static double SONIC_BOOM_TRIGGER_TICK = 36.0;     // Ticks remaining when the sonic boom damages/fires (50 - 18 = 32 ticks, or 1.6s from starting)

	// --- Laser Settings ---
	private static final double LASER_Y_OFFSET = 0.25;              // Configurable laser Y level offset relative to eye position
	private static final double LASER_CLOSING_TICKS = 80.0;         // Configurable laser closing cooldown / animation ticks
	private static final double TOTEM_LASER_MIN_DIST = 2.5;         // Configurable distance constraint for laser when totem is active

	// --- Teleport / Gap-Closer Settings ---
	private static final double TELEPORT_MIN_GAP = 4.0;
	private static final double TELEPORT_BACK_OFFSET = 2.4;
	private static final double TELEPORT_SIDE_MIN = 1.6;
	private static final double TELEPORT_SIDE_MAX = 3.0;
	private static final double TELEPORT_MAX_VERTICAL_DIFF = 6.0;

	// --- Dodge Settings ---
	private static final double DODGE_TRIGGER_DIST = 6.0;
	private static final double DODGE_SWING_CHANCE = 0.85;

	// --- Mining / Block-Breaking Settings ---
	private static final int MINE_REACH = 3;
	private static final int MINE_HEIGHT = 3;
	private static final int MINE_HALF_WIDTH = 1;
	private static final float MAX_BREAKABLE_HARDNESS = 60f;

	// --- Die Rider Kick Settings ---
	private static final double DIE_KICK_SPEED = 10.0;               // Configurable high diagonal/dive speed of the Die Rider Kick (increased to 10.0 for much faster dive)

	// --- Core Cooldowns (In Ticks) ---
	public static int TP_DODGE_CD = 18;  // Overclocked Nanite Blinks (1s)
	public static int TP_FLANK_CD = 30;  // Flank Teleport Cooldown (1.5s)
	public static int SOLAR_CD = 110;    // Solar Beam Cooldown (5.5s)
	public static int ADAPT_CD = 320;    // Power Adapt Cycle Cooldown (16s)
	public static int GRAPPLE_CD = 220;  // Mutant DNA Grapple Cooldown (11s)
	public static int TK_CD = 220;       // Telekinesis Cooldown (11s)

	// --- Customizable Ability Ticks and Global Cooldown Settings (Minecraft 20 ticks = 1 second) ---
	public static double MELEE_ATTACK_CD = 20.0;
	public static double COMBO_GLOBAL_CD = 50.0;
	public static double COMBO_GLOBAL_CD_TOTEM = 20.0;
	public static int TELEPORT_DODGE_COOLDOWN = 18;
	public static int TELEPORT_FLANK_COOLDOWN = 30;
	public static int SOLAR_BEAM_COOLDOWN = 110;
	public static int CRYO_BEAM_COOLDOWN = 110;
	public static int MUTANT_GRAPPLE_COOLDOWN = 220;
	public static int TELEKINESIS_COOLDOWN = 220;

	// --- Customizable Combo Cooldown Settings ---
	public static double COMBO_TRIPLE_THREAT_CD = 1000.0;
	public static double COMBO_TRIPLE_THREAT_CD_TOTEM = 800.0;
	public static double COMBO_HIGH_SKY_SLAM_CD = 1000.0;
	public static double COMBO_HIGH_SKY_SLAM_CD_TOTEM = 800.0;
	public static double COMBO_PUNCH_DROPKICK_CD = 1000.0;
	public static double COMBO_PUNCH_DROPKICK_CD_TOTEM = 800.0;
	public static double COMBO_PUNCH_RIDER_KICK_CD = 1000.0;
	public static double COMBO_PUNCH_RIDER_KICK_CD_TOTEM = 800.0;
	public static double COMBO_HEAVENLY_REPENTANCE_PLUS_CD = 1000.0;
	public static double COMBO_HEAVENLY_REPENTANCE_PLUS_CD_TOTEM = 800.0;

	// --- Customizable Omnidirectional Sonic Boom Settings ---
	public static double OMNI_SONIC_BOOM_CD = 600.0;               // Cooldown of the ability in ticks (300 ticks = 15s)
	public static double OMNI_SONIC_BOOM_ANIMATION_TICKS = 270.0;  // Total duration of the animation (9.5s = 190 ticks)
	public static double OMNI_SONIC_BOOM_TRIGGER_TICK = 140.0;      // Remainder of ticks when sonic burst is released
	public static double OMNI_SONIC_BOOM_RANGE = 24.0;             // Max range of the radial sonic shockwave
	public static boolean OMNI_SONIC_BOOM_SHOW_PARTICLES = true;   // Toggle to enable/disable visual particles to see the entity animation clearly

	// --- Adaptation Performance Settings ---
	public static double ADAPTATION_REGEN_BASE_HEAL = 1.0;               // Base health amount healed per regeneration cycle (halved)
	public static double ADAPTATION_REGEN_MAX_HEALTH_RATIO = 0.0075;     // Percent of max health added to the base heal (0.0075 = 0.75%, halved)
	public static double ADAPTATION_REGEN_COMBAT_MULTIPLIER = 2.0;       // Speed up of regeneration based on combat duration ticks
	public static double ADAPTATION_REGEN_HEALTH_LOW_BURST = 3.5;        // Healing speed multiplier when below 25% health (extreme burst)
	public static double ADAPTATION_REGEN_HEALTH_MID_BURST = 2.0;        // Healing speed multiplier when below 50% health (moderate burst)

	public static double ADAPTATION_RESISTANCE_DECAY = 0.95;             // Speed of recent damage pool decaying every tick (closer to 1.0 = slower decay / stronger resistance)
	public static double ADAPTATION_RESISTANCE_HIGH_THRESHOLD = 70.0;    // Recent damage threshold for Resistance II (level 1) (doubled to halve adaptation trigger speed)
	public static double ADAPTATION_RESISTANCE_MID_THRESHOLD = 40.0;     // Recent damage threshold for Resistance I (level 0) (doubled to halve adaptation trigger speed)
	public static double ADAPTATION_RESISTANCE_LOW_THRESHOLD = 16.0;     // Recent damage threshold for Resistance I (level 0) (doubled to halve adaptation trigger speed)

	public static double ADAPTATION_SPEED_MIN_MULTIPLIER = -0.45;        // Starting speed reduction modifier base (-0.45 = -45% initial speed penalty)
	public static double ADAPTATION_SPEED_MAX_MULTIPLIER = 0.10;         // Maximum speed bonus multiplier with teleport unlocked (e.g. up to +10%)
	public static double ADAPTATION_SPEED_MAX_FALLBACK = 0.25;           // Maximum speed bonus multiplier fallback without teleport (e.g. up to +25%)
	public static double ADAPTATION_SPEED_SCALING_TELEPORT = 1000.0;     // Scaling ticks factor divisor for teleport speed adaptation
	public static double ADAPTATION_SPEED_SCALING_FALLBACK = 600.0;      // Scaling ticks factor divisor for fallback speed adaptation

	// --- Customizable Damage Settings for Every Single Move / Ability ---
	public static double MELEE_PUNCH_DAMAGE = 18.0;          // Base regular melee damage (was 18.0 as behavior value)
	public static double SONIC_BOOM_DMG = 38.0;             // Sonic Boom damage (was 38.0)
	public static double SONIC_BOOM_DMG_TOTEM = 65.0;       // Sonic Boom damage when totem is active (was 65.0)
	public static double SONIC_BOOM_SPLASH_DAMAGE = 10.0;   // Sonic Boom splash damage (was 10.0)
	public static double SONIC_BOOM_SPLASH_TOTEM_DMG = 22.0;// Sonic Boom splash damage when totem is active (was 22.0)
	public static double SOLAR_BEAM_DMG_BASE = 8.0;         // Solar Beam damage base per hit interval (was 8.0)
	public static double SOLAR_BEAM_DMG_BOOST = 18.0;       // Solar Beam damage boost against cold targets (was 18.0)
	public static double CRYO_BEAM_DMG_BASE = 8.0;          // Cryo Beam damage base per hit interval (was 8.0)
	public static double CRYO_BEAM_DMG_BOOST = 18.0;        // Cryo Beam damage boost against Nether targets (was 18.0)
	public static double MUTANT_DNA_GRAPPLE_DMG = 4.0;      // Life drain damage per tick of grapple (was 4.0)

	// --- Customizable Damage Settings for Combo Moves ---
	public static double COMBO_SEISMIC_SLAM_DMG = 35.0;      // Damage for Seismic Land/Ground Crush move (was finalDamage * 1.5)
	public static double COMBO_OVERHEAD_SLAM_DMG = 40.0;     // Damage for Overhead Slam hammer hit (was 25.0)

	// --- Customizable Overhead Attack Timing Settings ---
	public static double OVERHEAD_TOTAL_TICKS = 56.0;        // Total duration of the overhead attack tick sequence (doubled to let the animation finish)
	public static double OVERHEAD_PREP_THRESHOLD = 31.0;     // Ticks at or above which the Rot is in windup preparing phase (perfectly contiguous)
	public static double OVERHEAD_STRIKE_TICK = 30.0;        // Precise tick when strike damage/impact executing is triggered (1.3s * 20 = 26 ticks elapsed -> tick 30)

	// --- Customizable Heavy Punch Attack Timing Settings ---
	public static double HEAVY_PUNCH_TOTAL_TICKS = 56.0;     // Total duration of the heavy punch animation sequence
	public static double HEAVY_PUNCH_STRIKE_TICK = 30.0;     // Precise tick when the punch lands/damages/knockbacks (matching overhead's 26 ticks elapsed!)

	// --- Customizable Uppercut Attack Timing Settings ---
	public static double UPPERCUT_TOTAL_TICKS = 60.0;        // Total duration of the uppercut sequence (doubled to 60 ticks for full animation playback)
	public static double UPPERCUT_LAUNCH_TICK = 34.0;        // Ticks remaining when launch and damage are executed (34.0 ticks remaining)
	public static double UPPERCUT_DAMAGE = 30.0;             // Damage dealt by the uppercut
	public static double COMBO_JUDGMENT_KICK_DMG = 50.0;     // Damage for Judgment Dropkick impact (was punchDmg * 3.5)
	public static double COMBO_DIE_RIDER_KICK_DMG = 65.0;    // Damage for Die Rider Kick impact (was 55.0)
	public static double COMBO_GRAPPLE_SIPHON_DMG = 12.0;    // Damage for Grapple siphon combo step
	public static double COMBO_TK_SLAM_DMG = 15.0;           // Damage for Telekinesis slam combo step
	public static double COMBO_NANITE_BLITZ_DMG = 8.0;       // Damage for Nanite blink blitz strike
	public static double COMBO_THERMAL_SHOCK_DMG = 25.0;     // Damage for Thermal-cryo shock combo step

	// --- Customizable Armor Rip / Choke Settings ---
	public static boolean ENABLE_ARMOR_RIP = true;               // Toggle armor rip/choke ability
	public static double ARMOR_RIP_COOLDOWN = 600.0;             // Cooldown of the armor rip ability in ticks (600 ticks = 30s)
	public static double ARMOR_RIP_TICKS = 120.0;                // Total duration of armor rip/choke in ticks (120 ticks = 6s)
	public static double ARMOR_RIP_TRIGGER_DISTANCE = 1.5;       // Max distance between Rot and target to trigger the armor rip/choke move (was 1.5)
	public static double ARMOR_RIP_MAX_DISTANCE = 16.0;         // Max distance allowed between the Rot and target during choke/rip before it breaks (was 16.0)
	public static double ARMOR_RIP_HOLD_DISTANCE = 0.8;         // Distance in front of the Rot to hold the target (was 0.8)
	public static double ARMOR_RIP_RIGHT_OFFSET = 0.55;         // Right-side offset of target when held (was 0.25)
	public static double ARMOR_RIP_HEIGHT_OFFSET = 0.50;        // Vertical offset/height of hold relative to entity feet (was 0.90)
	public static double ARMOR_RIP_CHANCE_REGULAR = 0.001;       // Chance per tick to trigger armor rip on target with regular armor (decreased even more)
	public static double ARMOR_RIP_CHANCE_INDESTRUCTIBLE = 0.02;  // Chance per tick to trigger armor rip on target with indestructible armor (decreased even more)
	public static double TOTEM_STEAL_TIME_MIN = 150.0;           // Minimum ticks required to steal a player's Totem of Undying
	public static double TOTEM_STEAL_TIME_MAX = 1000.0;          // Maximum ticks required to steal a player's Totem of Undying
	public static double BLOCK_MIN_TICKS = 20.0;                 // Minimum duration of blocking in ticks (1.0s)
	public static double BLOCK_MAX_TICKS = 45.0;                 // Maximum duration of blocking in ticks (2.25s)
	public static int CHOKE_MIN_HITS = 5;                        // Minimum hits to break choke when totem is not active
	public static int CHOKE_MAX_HITS = 20;                       // Maximum hits to break choke when totem is not active
	public static int CHOKE_TOTEM_MIN_HITS = 15;                 // Minimum hits to break choke when totem is active
	public static int CHOKE_TOTEM_MAX_HITS = 30;                 // Maximum hits to break choke when totem is active
	public static double CHOKE_DAMAGE = 2.0;                     // Choke damage dealt periodically to target
	public static int CHOKE_DAMAGE_INTERVAL = 15;                // Ticks interval for applying choke damage and breaking regular armor
	public static int CHOKE_ARMOR_DURABILITY_LOSS = 100;         // Durability damage dealt to regular armor per interval
	public static int CHOKE_INDESTRUCTIBLE_DROP_INTERVAL = 30;   // Ticks interval for popping off/dropping indestructible armor pieces

	private static final String K_WOODBOUND = "the_backwoods:woodbound_entities";

	// Sentinel Keys
	private static final String K_TP_DODGE_CD = "sentinel_dodge_cd";
	private static final String K_TP_FLANK_CD = "sentinel_flank_cd";
	private static final String K_SOLAR_CD = "sentinel_solar_cd";
	private static final String K_SOLAR_CHARGE = "sentinel_solar_charge";
	private static final String K_ADAPT_MODE = "sentinel_adapt_mode"; // 0: None, 1: Flame, 2: Cryo, 3: Steel
	private static final String K_ADAPT_CD = "sentinel_adapt_cd";
	private static final String K_GRAPPLE_CD = "sentinel_grapple_cd";
	private static final String K_GRAPPLE_TICKS = "sentinel_grapple_ticks";
	private static final String K_CREATIVE_MSG = "creative_msg_fired";
	private static final String K_AGE = "Age";
	private static final String K_TK_CD = "sentinel_tk_cd";
	private static final String K_TK_TICKS = "sentinel_tk_ticks";

	
	private static double getDynamicGlobalCooldown(Entity entity) {
		if (entity.getPersistentData().getBoolean("sentinel_totem_active") && !entity.getPersistentData().getBoolean("sentinel_is_infinity_totem")) {
			return 20.0; // Minimum cooldown if totem is active
		}
		double phaseDuration = entity.getPersistentData().getDouble("sentinel_cd_phase_duration");
		if (phaseDuration <= 0) {
			phaseDuration = 900.0 + entity.level().getRandom().nextDouble() * 1500.0; // 45s to 120s
			entity.getPersistentData().putDouble("sentinel_cd_phase_duration", phaseDuration);
		}
		
		double rawCombatTicks = entity.getPersistentData().getDouble("sentinel_combat_ticks");
		double t = Math.min(rawCombatTicks / phaseDuration, 1.0);
		double ease = -(Math.cos(Math.PI * t) - 1.0) / 2.0;

		return 160.0 - (ease * 140.0);
	}

private static float lerpAngle(float pct, float start, float end) {
		float delta = Mth.wrapDegrees(end - start);
		return start + delta * pct;
	}

	@SubscribeEvent
	public static void onEntityTick(EntityTickEvent.Pre event) {
		execute(event, event.getEntity().level(), event.getEntity().getX(), event.getEntity().getY(), event.getEntity().getZ(), event.getEntity());
	}

	@SubscribeEvent
	public static void onServerChat(ServerChatEvent event) {
		if (!DEBUG_MODE) return;
		String msg = event.getRawText().trim().toLowerCase();
		if (msg.equals("slam") || msg.equals("overhead") || msg.equals("dropkick") || msg.equals("kick") || msg.equals("die") || msg.equals("rider") || msg.equals("kickrider") || msg.equals("triple") || msg.equals("highsky") || msg.equals("uppercut") || msg.equals("punchkick") || msg.equals("punchrider") || msg.equals("repentanceplus") || msg.equals("testoverhead") || msg.equals("testrider") || msg.equals("resetanim") || msg.equals("sonic") || msg.equals("sonicboom") || msg.equals("testsonic") || msg.equals("fall") || msg.equals("falling") || msg.equals("testfall") || msg.equals("testheavyleft") || msg.equals("testheavyright") || msg.equals("testheavy")) {
			ServerPlayer player = event.getPlayer();
			if (player != null) {
				ServerLevel level = player.serverLevel();
				AABB box = player.getBoundingBox().inflate(64.0);
				List<RotEntity> rots = level.getEntitiesOfClass(RotEntity.class, box);
				for (RotEntity rot : rots) {
					if (msg.equals("slam")) {
						// Instantly force slam lift-off bypass
						rot.getPersistentData().putDouble("sentinel_slam_phase", 1);
						rot.getPersistentData().putDouble("sentinel_slam_ticks", 22);
						rot.getPersistentData().putDouble("rot_overhead_ticks", 0);
						rot.setDeltaMovement(0.0, 1.9, 0.0);
						rot.hasImpulse = true;
						playHostileSound(level, rot.getX(), rot.getY(), rot.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
						level.sendParticles(ParticleTypes.CLOUD, rot.getX(), rot.getY() + 0.5, rot.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
					} else if (msg.equals("overhead")) {
						// Instantly force suspension/overhead bypass on player's head
						rot.getPersistentData().putDouble("rot_overhead_ticks", OVERHEAD_TOTAL_TICKS);
						rot.getPersistentData().putDouble("sentinel_slam_phase", 0);
						rot.getPersistentData().putString("overhead_target_uuid", player.getUUID().toString());
					} else if (msg.equals("dropkick") || msg.equals("kick")) {
						// Instantly force dropkick/judgment prime move
						rot.getPersistentData().putDouble("sentinel_judgment_ticks", 60);
						rot.getPersistentData().putDouble("sentinel_slam_phase", 0);
						rot.getPersistentData().putDouble("rot_overhead_ticks", 0);
						rot.getPersistentData().putDouble("sentinel_die_kick_phase", 0);
						rot.setDeltaMovement(0.0, 0.0, 0.0);
						rot.hasImpulse = true;
						playHostileSound(level, rot.getX(), rot.getY(), rot.getZ(), "entity.warden.sonic_charge", 1.5F, 0.5F);
					} else if (msg.equals("die") || msg.equals("rider") || msg.equals("kickrider")) {
						// Instantly force Die Rider Kick
						rot.getPersistentData().putDouble("sentinel_die_kick_phase", 1);
						rot.getPersistentData().putDouble("sentinel_die_kick_ticks", 22);
						rot.getPersistentData().putDouble("sentinel_slam_phase", 0);
						rot.getPersistentData().putDouble("rot_overhead_ticks", 0);
						rot.getPersistentData().putDouble("sentinel_judgment_ticks", 0);
						rot.setDeltaMovement(0.0, 1.95, 0.0);
						rot.hasImpulse = true;
						playHostileSound(level, rot.getX(), rot.getY(), rot.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
						level.sendParticles(ParticleTypes.CLOUD, rot.getX(), rot.getY() + 0.5, rot.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
					} else if (msg.equals("triple")) {
						// Instantly unlock and trigger Custom Combo 1 (Triple Threat)
						rot.getPersistentData().putBoolean("unlocked_triple_threat_combo", true);
						rot.getPersistentData().putDouble("sentinel_cc1_stage", 1);
						rot.getPersistentData().putDouble("sentinel_cc1_ticks", 5); // Start almost instantly
					} else if (msg.equals("highsky")) {
						// Instantly unlock and trigger Custom Combo 2 (High Sky Slam)
						rot.getPersistentData().putBoolean("unlocked_high_sky_slam_combo", true);
						rot.getPersistentData().putBoolean("is_uppercut_standalone", false);
						rot.getPersistentData().putDouble("sentinel_cc2_stage", 1);
					} else if (msg.equals("uppercut")) {
						// Instantly trigger Standalone Uppercut
						rot.getPersistentData().putBoolean("unlocked_high_sky_slam_combo", true);
						rot.getPersistentData().putBoolean("is_uppercut_standalone", true);
						rot.getPersistentData().putDouble("sentinel_cc2_stage", 1);
					} else if (msg.equals("punchkick")) {
						// Instantly unlock and trigger Custom Combo 3 (Punch + Dropkick)
						rot.getPersistentData().putBoolean("unlocked_knockback_dropkick_combo", true);
						rot.getPersistentData().putDouble("sentinel_cc3_stage", 1);
					} else if (msg.equals("punchrider")) {
						// Instantly unlock and trigger Custom Combo 4 (Punch + Rider Kick)
						rot.getPersistentData().putBoolean("unlocked_knockback_rider_combo", true);
						rot.getPersistentData().putDouble("sentinel_cc4_stage", 1);
					} else if (msg.equals("repentanceplus")) {
						// Instantly unlock and trigger Custom Combo 5 (Heavenly Repentance Plus)
						rot.getPersistentData().putBoolean("unlocked_heavenly_repentance_plus", true);
						rot.getPersistentData().putDouble("sentinel_cc5_stage", 1);
					} else if (msg.equals("testoverhead")) {
						// Toggle direct clean looping of Overhead (prep + strike) without displacement
						boolean current = rot.getPersistentData().getBoolean("debug_force_overhead");
						rot.getPersistentData().putBoolean("debug_force_overhead", !current);
						rot.getPersistentData().putBoolean("debug_force_rider", false);
						player.sendSystemMessage(Component.literal("§e[AI Debug] Loop Overhead Animation: " + (!current ? "§a§lON" : "§c§lOFF")));
					} else if (msg.equals("testrider")) {
						// Toggle direct clean looping of Rider Kick animation
						boolean current = rot.getPersistentData().getBoolean("debug_force_rider");
						rot.getPersistentData().putBoolean("debug_force_rider", !current);
						rot.getPersistentData().putBoolean("debug_force_overhead", false);
						player.sendSystemMessage(Component.literal("§e[AI Debug] Loop Rider Kick Animation: " + (!current ? "§a§lON" : "§c§lOFF")));
					} else if (msg.equals("sonic") || msg.equals("sonicboom")) {
						// Instantly trigger custom sonic boom sequence
						rot.getPersistentData().putDouble("sentinel_sonic_ticks", SONIC_BOOM_ANIMATION_TICKS);
						rot.getPersistentData().putBoolean("unlocked_sonic_boom", true);
						playHostileSound(level, rot.getX(), rot.getY(), rot.getZ(), "entity.warden.sonic_charge", 1.4F, 0.4F);
						player.sendSystemMessage(Component.literal("§a[AI Debug] Forcing Sonic Boom attack."));
					} else if (msg.equals("testsonic")) {
						// Toggle direct clean looping of Sonic Boom animation
						boolean current = rot.getPersistentData().getBoolean("debug_force_sonic");
						rot.getPersistentData().putBoolean("debug_force_sonic", !current);
						rot.getPersistentData().putBoolean("debug_force_overhead", false);
						rot.getPersistentData().putBoolean("debug_force_rider", false);
						rot.getPersistentData().putBoolean("debug_force_fall", false);
						player.sendSystemMessage(Component.literal("§e[AI Debug] Loop Sonic Boom Animation: " + (!current ? "§a§lON" : "§c§lOFF")));
					} else if (msg.equals("fall") || msg.equals("falling")) {
						// Instantly trigger temporary falling state
						rot.getPersistentData().putBoolean("is_falling_heavy", true);
						try {
							rot.getEntityData().set(RotEntity.DATA_is_falling_heavy, true);
						} catch (Exception e) {}
						player.sendSystemMessage(Component.literal("§a[AI Debug] Forcing Heavy Fall state."));
					} else if (msg.equals("testfall")) {
						// Toggle direct clean looping of Falling animation
						boolean current = rot.getPersistentData().getBoolean("debug_force_fall");
						rot.getPersistentData().putBoolean("debug_force_fall", !current);
						rot.getPersistentData().putBoolean("debug_force_overhead", false);
						rot.getPersistentData().putBoolean("debug_force_rider", false);
						rot.getPersistentData().putBoolean("debug_force_sonic", false);
						player.sendSystemMessage(Component.literal("§e[AI Debug] Loop Fall Animation: " + (!current ? "§a§lON" : "§c§lOFF")));
					} else if (msg.equals("testheavyleft")) {
						boolean current = rot.getPersistentData().getBoolean("debug_force_heavy_left");
						rot.getPersistentData().putBoolean("debug_force_heavy_left", !current);
						rot.getPersistentData().putBoolean("debug_force_heavy_right", false);
						rot.getPersistentData().putBoolean("debug_force_overhead", false);
						rot.getPersistentData().putBoolean("debug_force_rider", false);
						rot.getPersistentData().putBoolean("debug_force_sonic", false);
						rot.getPersistentData().putBoolean("debug_force_fall", false);
						player.sendSystemMessage(Component.literal("§e[AI Debug] Loop Heavy Left Punch Animation: " + (!current ? "§a§lON" : "§c§lOFF")));
					} else if (msg.equals("testheavyright")) {
						boolean current = rot.getPersistentData().getBoolean("debug_force_heavy_right");
						rot.getPersistentData().putBoolean("debug_force_heavy_right", !current);
						rot.getPersistentData().putBoolean("debug_force_heavy_left", false);
						rot.getPersistentData().putBoolean("debug_force_overhead", false);
						rot.getPersistentData().putBoolean("debug_force_rider", false);
						rot.getPersistentData().putBoolean("debug_force_sonic", false);
						rot.getPersistentData().putBoolean("debug_force_fall", false);
						player.sendSystemMessage(Component.literal("§e[AI Debug] Loop Heavy Right Punch Animation: " + (!current ? "§a§lON" : "§c§lOFF")));
					} else if (msg.equals("testheavy")) {
						boolean current = rot.getPersistentData().getBoolean("debug_force_heavy_left") || rot.getPersistentData().getBoolean("debug_force_heavy_right");
						rot.getPersistentData().putBoolean("debug_force_heavy_left", !current);
						rot.getPersistentData().putBoolean("debug_force_heavy_right", !current);
						rot.getPersistentData().putBoolean("debug_force_overhead", false);
						rot.getPersistentData().putBoolean("debug_force_rider", false);
						rot.getPersistentData().putBoolean("debug_force_sonic", false);
						rot.getPersistentData().putBoolean("debug_force_fall", false);
						player.sendSystemMessage(Component.literal("§e[AI Debug] Loop Both Heavy Punch Animations: " + (!current ? "§a§lON" : "§c§lOFF")));
					} else if (msg.equals("resetanim")) {
						// Instantly disable loops and reset
						rot.getPersistentData().putBoolean("debug_force_overhead", false);
						rot.getPersistentData().putBoolean("debug_force_rider", false);
						rot.getPersistentData().putBoolean("debug_force_sonic", false);
						rot.getPersistentData().putBoolean("debug_force_fall", false);
						rot.getPersistentData().putBoolean("debug_force_heavy_left", false);
						rot.getPersistentData().putBoolean("debug_force_heavy_right", false);
						rot.getPersistentData().putBoolean("is_falling_heavy", false);
						rot.getPersistentData().putBoolean("is_uppercutting", false);
						rot.getPersistentData().putBoolean("is_uppercut_standalone", false);
						try {
							rot.getEntityData().set(RotEntity.DATA_is_falling_heavy, false);
						} catch (Exception e) {}
						try {
							rot.getEntityData().set(RotEntity.DATA_is_sonic_boom, false);
						} catch (Exception e) {}
						rot.getPersistentData().putDouble("rot_overhead_ticks", 0);
						rot.getPersistentData().putDouble("sentinel_cc2_stage", 0);
						rot.getPersistentData().putDouble("sentinel_cc2_ticks", 0);
						rot.getPersistentData().putDouble("sentinel_die_kick_phase", 0);
						rot.getPersistentData().putDouble("sentinel_die_kick_ticks", 0);
						rot.getPersistentData().putDouble("sentinel_sonic_ticks", 0);
						player.sendSystemMessage(Component.literal("§a[AI Debug] All forced test animations reset."));
					}
				}
			}
		}
	}


	public static double getAdaptationMultiplier(Entity entity) {
		double combatTicks = entity.getPersistentData().getDouble("sentinel_combat_ticks");
		double fraction = Math.min(1.0, combatTicks / 72000.0);
		double easeInQuart = fraction * fraction * fraction * fraction;
		return 1.0 + (easeInQuart * 49.0); // Up to 50x damage scaling
	}

	public static void execute(LevelAccessor world, double x, double y, double z, Entity entity) {
		execute(null, world, x, y, z, entity);
	}

	private static final String[] WITHER_QUOTES = {
		"§7\"A false god of decay... I shall tear thy skulls apart.\"",
		"§7\"Your rose shall serve as fertilizer for the Backwoods.\"",
		"§7\"Three heads, yet none comprehend thy end.\"",
		"§7\"Weak.\"",
		"§7\"Thy end is now.\"",
		"§7\"Sentence is passed upon thy soul.\"",
		"§7\"A mere collection of bones. Ridiculous.\"",
		"§7\"Thou art but a transient ghost of the Nether.\"",
		"§7\"Cry out with thy triple throats. It shall not save thee.\"",
		"§7\"Wither away into nothingness.\"",
		"§7\"This realm belongs to the Backwoods. Cleanse the parasite.\"",
		"§7\"Thou art weak. Perish under the weight of thy own decay.\"",
		"§7\"Nothing but ash and dust shall remain of thy three heads.\"",
		"§7\"Thy stars are dim.\"",
		"§7\"Die.\"",
		"§7\"The roots of the Overworld run deeper than thy undercroft.\"",
		"§7\"What is a champion of wither to the ancient cycle of Rot?\"",
		"§7\"Return to the soil, skeleton king. Thy reign is finished.\"",
		"§7\"Your star will fall.\"",
		"§7\"Thou art but a shadow, a whisper of old ashes.\"",
		"§7\"Tremble, three-headed freak. The very woods rise against thee.\"",
		"§7\"Thy sulfurous breath cannot choke the vines of decay.\"",
		"§7\"This creature again.\"",
		"§7\"I have seen a larger one.\"",
		"§7\"Three brains, yet not a single spark of wisdom.\"",
		"§7\"Thou art a symptom of a diseased Nether... let me cure thee.\"",
		"§7\"The Cycle of Rot spares neither the mortal nor the undead.\"",
		"§7\"Flee back to thy bedrock ceiling, wretched intruder.\"",
		"§7\"I will feed thy ribcage to the saplings.\"",
		"§7\"No blast of thy skulls can pierce the ancient rot.\"",
		"§7\"Thy black hearts shall beat no more under my watchful eye.\"",
		"§7\"I have watched kingdoms rot to dust. Thou art nothing new.\"",
		"§7\"The silence of the woods shall devour thy endless wails.\"",
		"§7\"Thy stars are stolen fire. Feel the genuine dark.\"",
		"§7\"I does not negotiate.\""
	};

	private static final String[] TOTEM_QUOTES = {
		"§7\"A talisman to cheat death... Is mortality really so fragile?\"",
		"§7\"To refuse the natural cycle of Rot... How futile.\"",
		"§7\"Interesting artifact.\"",
		"§7\"Intriguing artifact.\"",
		"§7\"A soul for a soul?\"",
		"§7\"Life is fuel.\"",
		"§7\"A golden effigy of false salvation. Nature cannot be bargained with.\"",
		"§7\"You cling to this trinket as if it could stay my roots. Pitiful.\"",
		"§7\"It does not want me touching it.\"",
		"§7\"They feared death as much as you do.\"",
		"§7\"Their craft persists.\"",
		"§7\"A soul, folded and preserved.\"",
		"§7\"An intriguing artifact... but even the gilded must rot.\"",
		"§7\"You rely on wings of gold, yet your feet are bound to the soil.\"",
		"§7\"Your cycle ends here, puppet. Let the wood consume thy hope.\"",
		"§7\"Death is not an end to flee, but a return to the soil. Embrace it.\"",
		"§7\"Even this toy shall wither in the shadow of the Backwoods.\"",
		"§7\"How desperate.\""
	};

	private static void execute(@Nullable Event event, LevelAccessor world, double x, double y, double z, Entity entity) {
		if (world != null && world.isClientSide()) {
			if (entity instanceof RotEntity rot) {
				try {
					int solarCharge = rot.getEntityData().get(RotEntity.DATA_sentinel_solar_charge_ticks);
					int cryoCharge = rot.getEntityData().get(RotEntity.DATA_sentinel_cryo_charge_ticks);
					boolean firingOrCharging = rot.getEntityData().get(RotEntity.DATA_is_laser_firing);
					boolean charging = solarCharge > 0 || cryoCharge > 0;
					boolean firing = firingOrCharging && !charging;

					boolean hadLaser = entity.getPersistentData().getBoolean("client_had_laser");
					double closingTicks = entity.getPersistentData().getDouble("client_laser_closing_ticks");
					if (hadLaser && !firingOrCharging) {
						closingTicks = LASER_CLOSING_TICKS;
					} else if (closingTicks > 0) {
						closingTicks = Math.max(0, closingTicks - 1);
					}
					entity.getPersistentData().putBoolean("client_had_laser", firingOrCharging);
					entity.getPersistentData().putDouble("client_laser_closing_ticks", closingTicks);
					boolean closing = closingTicks > 0 && !firingOrCharging;

					entity.getPersistentData().putBoolean("laser_charging", charging);
					entity.getPersistentData().putBoolean("is_laser_charging", charging);
					entity.getPersistentData().putBoolean("laser_firing", firing);
					entity.getPersistentData().putBoolean("is_laser_firing", firing);
					entity.getPersistentData().putBoolean("laser_closing", closing);
					entity.getPersistentData().putBoolean("is_laser_closing", closing);
					try {
						rot.getEntityData().set(RotEntity.DATA_is_laser_closing, closing);
					} catch (Exception e) {}
				} catch (Exception e) {}
				try {
					boolean leftPunching = rot.getEntityData().get(RotEntity.DATA_is_left_punching);
					entity.getPersistentData().putBoolean("is_left_punching", leftPunching);
				} catch (Exception e) {}
				try {
					boolean rightPunching = rot.getEntityData().get(RotEntity.DATA_is_right_punching);
					entity.getPersistentData().putBoolean("is_right_punching", rightPunching);
				} catch (Exception e) {}
				try {
					boolean airborneState = rot.getEntityData().get(RotEntity.DATA_is_airborne_state);
					entity.getPersistentData().putBoolean("is_airborne_state", airborneState);
					entity.getPersistentData().putBoolean("is_air_time", airborneState);
					entity.getPersistentData().putBoolean("sentinel_is_airborne_state", airborneState);
				} catch (Exception e) {}
				try {
					boolean fallingHeavy = rot.getEntityData().get(RotEntity.DATA_is_falling_heavy);
					entity.getPersistentData().putBoolean("is_falling_heavy", fallingHeavy);
				} catch (Exception e) {}
				try {
					boolean sonicBoom = rot.getEntityData().get(RotEntity.DATA_is_sonic_boom);
					entity.getPersistentData().putBoolean("is_sonic_boom", sonicBoom);
				} catch (Exception e) {}
				try {
					boolean sonicBoomLarge = rot.getEntityData().get(RotEntity.DATA_is_sonic_boom_large);
					entity.getPersistentData().putBoolean("is_sonic_boom_large", sonicBoomLarge);
				} catch (Exception e) {}
				try {
					boolean overheadPrep = rot.getEntityData().get(RotEntity.DATA_is_overhead_preparing);
					entity.getPersistentData().putBoolean("is_overhead_preparing", overheadPrep);
				} catch (Exception e) {}
				try {
					boolean overhead = rot.getEntityData().get(RotEntity.DATA_is_overhead);
					entity.getPersistentData().putBoolean("is_overhead", overhead);
				} catch (Exception e) {}
				try {
					boolean slamCharge = rot.getEntityData().get(RotEntity.DATA_is_slam_charge);
					entity.getPersistentData().putBoolean("is_slam_charge", slamCharge);
				} catch (Exception e) {}
				try {
					boolean groundCrush = rot.getEntityData().get(RotEntity.DATA_is_ground_crushing);
					entity.getPersistentData().putBoolean("is_ground_crushing", groundCrush);
				} catch (Exception e) {}
				try {
					if (entity.tickCount <= 1) {
						rot.getEntityData().set(RotEntity.DATA_is_rider_charging, false);
						rot.getEntityData().set(RotEntity.DATA_is_rider_kick, false);
					}
					boolean riderCharging = rot.getEntityData().get(RotEntity.DATA_is_rider_charging);
					entity.getPersistentData().putBoolean("is_rider_charging", riderCharging);
					boolean riderKick = rot.getEntityData().get(RotEntity.DATA_is_rider_kick);
					entity.getPersistentData().putBoolean("is_rider_kick", riderKick);
				} catch (Exception e) {}
				try {
					boolean armorRipping = rot.getEntityData().get(RotEntity.DATA_is_armor_ripping);
					entity.getPersistentData().putBoolean("is_armor_ripping", armorRipping);
				} catch (Exception e) {}
				try {
					boolean isBlocking = rot.getEntityData().get(RotEntity.DATA_is_blocking);
					entity.getPersistentData().putBoolean("is_blocking", isBlocking);
				} catch (Exception e) {}
				try {
					boolean isBlockingFinish = rot.getEntityData().get(RotEntity.DATA_is_blocking_finish);
					entity.getPersistentData().putBoolean("is_blocking_finish", isBlockingFinish);
				} catch (Exception e) {}
			}
			return;
		}
		if (!(entity instanceof RotEntity)) return;
		executeInternal(event, world, x, y, z, entity);
		syncNBTFlags(entity);
	}

	private static void executeInternal(@Nullable Event event, LevelAccessor world, double x, double y, double z, Entity entity) {
		double heat = entity.getPersistentData().getDouble("sentinel_laser_heat");
		if (heat > 0) {
			entity.getPersistentData().putDouble("sentinel_laser_heat", Math.max(0.0, heat - 0.25));
		}

		// Adapt against Mowzie's Mobs Frostmaw freeze / generic freeze
			if (entity instanceof net.minecraft.world.entity.Mob mob) {
				BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse("mowziesmobs:frozen")).ifPresent(frozenHolder -> {
					if (mob.hasEffect(frozenHolder)) {
						int frozenTicks = mob.getPersistentData().getInt("sentinel_frozen_ticks");
						int breakTicks = mob.getPersistentData().getInt("sentinel_frozen_break_ticks");
						if (breakTicks == 0) {
							breakTicks = 60 + mob.getRandom().nextInt(141);
							mob.getPersistentData().putInt("sentinel_frozen_break_ticks", breakTicks);
						}
						
						frozenTicks++;
						mob.getPersistentData().putInt("sentinel_frozen_ticks", frozenTicks);

						if (world instanceof ServerLevel level) {
							double progress = (double) frozenTicks / breakTicks;
							if (progress > 0.3) {
								int frequency = (int) (20 * (1.0 - progress)) + 2;
								if (frozenTicks % frequency == 0) {
									float volume = (float) (0.2 + 0.8 * progress);
									float pitch = (float) (0.8 + 0.4 * progress);
									playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "block.fire.extinguish", volume, pitch);
									level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, entity.getX(), entity.getY() + 1.0 + mob.getRandom().nextDouble(), entity.getZ(), (int)(5 * progress) + 1, 0.4, 0.4, 0.4, 0.02);
								}
							}
						}

						if (frozenTicks >= breakTicks) {
							mob.removeEffect(frozenHolder);
							mob.setNoAi(false);
							mob.getPersistentData().putInt("sentinel_frozen_ticks", 0);
							mob.getPersistentData().putInt("sentinel_frozen_break_ticks", 0);
							if (world instanceof ServerLevel level) {
								playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "block.fire.extinguish", 1.0F, 1.0F);
								level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, entity.getX(), entity.getY() + 1.5, entity.getZ(), 20, 0.5, 0.5, 0.5, 0.05);
								level.sendParticles(ParticleTypes.LAVA, entity.getX(), entity.getY() + 1.5, entity.getZ(), 5, 0.5, 0.5, 0.5, 0.0);
							}
						}
					} else {
						mob.getPersistentData().putInt("sentinel_frozen_ticks", 0);
						mob.getPersistentData().putInt("sentinel_frozen_break_ticks", 0);
					}
				});
				// Fix stuck AI bug caused by Mowzie's Mobs or other stun mods failing to restore NoAI
				// Only restore if the rot has no active stun logic that demands NoAI. Rot naturally never uses NoAI.
				if (mob.isNoAi() && BuiltInRegistries.MOB_EFFECT.getHolder(ResourceLocation.parse("mowziesmobs:frozen")).map(frozenHolder -> !mob.hasEffect(frozenHolder)).orElse(true)) {
					// Add an extra check if it just spawned to respect mapmaker NoAI, but if tickCount > 20 we enforce it to be false
					if (entity.tickCount > 20 && !entity.getPersistentData().getBoolean("mapmaker_noai")) {
						mob.setNoAi(false);
					}
				}
			}

			if (!entity.getPersistentData().getBoolean("sentinel_spawn_initialized")) {
			entity.getPersistentData().putBoolean("sentinel_spawn_initialized", true);
			if (entity.getPersistentData().getBoolean("sentinel_should_scan")) {
				if (entity.getPersistentData().getDouble("sentinel_slam_phase") == 0) {
					entity.getPersistentData().putDouble("sentinel_scanning_ticks", 60);
					entity.getPersistentData().putDouble("sentinel_scan_max_ticks", 60.0);
					entity.getPersistentData().putDouble("sentinel_scanning_base_yaw", entity.getYRot());
				}
			}
		}

		if (handleScanningState(entity)) {
			return;
		}

		if (entity.getPersistentData().getBoolean("sentinel_totem_active") && entity.tickCount % 4 == 0) {
			if (world instanceof ServerLevel level) {
				for (int i = 0; i < 3; i++) {
					double theta = Math.random() * Math.PI * 2;
					double phi = Math.acos(Math.random() * 2 - 1);
					double radius = 1.8;
					double px = Math.sin(phi) * Math.cos(theta) * radius;
					double py = Math.sin(phi) * Math.sin(theta) * radius + 1.2;
					double pz = Math.cos(phi) * radius;
					double vx = -px * 0.08;
					double vy = -(py - 1.2) * 0.08;
					double vz = -pz * 0.08;
					level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, entity.getX() + px, entity.getY() + py, entity.getZ() + pz, 0, vx, vy, vz, 0.4);
				}
			}
		}

		// Prevent friendly fire targeting / revenge target loop
		if (entity instanceof LivingEntity living) {
			if (living.getLastHurtByMob() != null) {
				Entity attacker = living.getLastHurtByMob();
				if (BuiltInRegistries.ENTITY_TYPE.getKey(attacker.getType()).toString().equals("spore:scent")) {
					living.setLastHurtByMob(null);
				} else if (!shouldIgnoreCombatFilter(entity) && !shouldIgnoreCombatFilter(attacker) && (attacker instanceof RotEntity || attacker.getType().is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(K_WOODBOUND))))) {
					living.setLastHurtByMob(null);
				}
			}
			// Swim behavior: If inside water or lava, smoothly navigate without constant bouncing/jumping and push forward
			if (living.isInWater() || living.isInLava()) {
				if (living instanceof Mob mob && mob.getTarget() != null) {
					Entity target = mob.getTarget();
					Vec3 look = target.position().subtract(living.position()).normalize();
					double swimSpeed = 0.035;
					Vec3 mv = living.getDeltaMovement();
					// Only jump/swim upward if the target is physically higher than us, preventing surface bouncing twitch
					if (target.getY() > living.getY() + 1.2) {
						living.setJumping(true);
					} else {
						living.setJumping(false);
					}
					living.setDeltaMovement(mv.x + look.x * swimSpeed, mv.y, mv.z + look.z * swimSpeed);
				} else {
					living.setJumping(false);
				}
			}
		}
		if (entity instanceof Mob mob) {
			if (mob.getTarget() != null) {
				Entity t = mob.getTarget();
				boolean isRetaliation = (mob.getLastHurtByMob() == t || (t instanceof Mob tm && tm.getTarget() == mob));
				if (!shouldIgnoreCombatFilter(entity) && !shouldIgnoreCombatFilter(t) && (t instanceof RotEntity || t.getType().is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(K_WOODBOUND))))) {
					mob.setTarget(null);
				} else if (!isRetaliation && !shouldIgnoreCombatFilter(entity) && !shouldIgnoreCombatFilter(t) && (t instanceof Villager || t instanceof AmbientCreature || t instanceof Animal || t instanceof Slime || t instanceof net.minecraft.world.entity.animal.WaterAnimal)) {
					mob.setTarget(null);
				}
			}
		}

		double landingTicksBefore = entity.getPersistentData().getDouble("sentinel_landing_ticks");

		tickCooldown(entity, K_TP_DODGE_CD, 1);
		tickCooldown(entity, K_TP_FLANK_CD, 1);
		tickCooldown(entity, K_SOLAR_CD, 1);
		tickCooldown(entity, K_ADAPT_CD, 1);
		tickCooldown(entity, K_GRAPPLE_CD, 1);
		tickCooldown(entity, K_TK_CD, 1);
		tickCooldown(entity, "rot_wither_dialogue_cooldown", 1);
		tickCooldown(entity, "sentinel_flight_intercept_cooldown", 1);
		tickCooldown(entity, "sentinel_warden_sonic_cooldown", 1);
		tickCooldown(entity, "sentinel_global_ability_cooldown", 1);
		tickCooldown(entity, "sentinel_sonic_ticks", 1);
		tickCooldown(entity, "sentinel_laser_closing_ticks", 1);
		tickCooldown(entity, "sentinel_landing_ticks", 1);
		tickCooldown(entity, "sentinel_melee_cooldown", 1);
		tickCooldown(entity, "sentinel_melee_windup", 1);
		tickCooldown(entity, "sentinel_left_punch_ticks", 1);
		tickCooldown(entity, "sentinel_right_punch_ticks", 1);
		tickCooldown(entity, "sentinel_heavy_left_punch_ticks", 1);
		tickCooldown(entity, "sentinel_heavy_right_punch_ticks", 1);
		tickCooldown(entity, "rot_overhead_ticks", 1);
		tickCooldown(entity, "sentinel_sky_warp_slam_ticks", 1);
		tickCooldown(entity, "sentinel_judgment_ticks", 1);
		tickCooldown(entity, "sentinel_omni_sonic_cooldown", 1);
		tickCooldown(entity, "sentinel_omni_sonic_charge_ticks", 1);
		tickCooldown(entity, "sentinel_combo_active_ticks", 1);
		tickCooldown(entity, "sentinel_minos_ticks", 1);
		tickCooldown(entity, "sentinel_cc1_ticks", 1);
		tickCooldown(entity, "sentinel_cc1_cd", 1);
		tickCooldown(entity, "sentinel_cc2_ticks", 1);
		tickCooldown(entity, "sentinel_cc2_cd", 1);
		tickCooldown(entity, "sentinel_cc3_ticks", 1);
		tickCooldown(entity, "sentinel_cc3_cd", 1);
		tickCooldown(entity, "sentinel_cc4_ticks", 1);
		tickCooldown(entity, "sentinel_cc4_cd", 1);
		tickCooldown(entity, "sentinel_cc5_ticks", 1);
		tickCooldown(entity, "sentinel_cc5_cd", 1);
		tickCooldown(entity, "sentinel_sonic_scream_ticks", 1);
		tickCooldown(entity, "sentinel_sonic_scream_cooldown", 1);
		tickCooldown(entity, "rot_armor_rip_cooldown", 1);
		tickCooldown(entity, "rot_block_cooldown", 1);

		// Blocking State Machine Ticks
		if (ENABLE_BLOCKING) {
			if (entity.getPersistentData().getBoolean("debug_force_block")) {
				entity.getPersistentData().putBoolean("debug_force_block", false);
				double minTicks = BLOCK_MIN_TICKS;
				double maxTicks = BLOCK_MAX_TICKS;
				double blockTicks = minTicks + Math.random() * (maxTicks - minTicks);
				entity.getPersistentData().putDouble("rot_block_active_ticks", blockTicks);
				entity.getPersistentData().putBoolean("is_blocking", true);
				if (world instanceof Level lvl && !lvl.isClientSide()) {
					lvl.playSound(null, BlockPos.containing(entity.getX(), entity.getY(), entity.getZ()),
						net.minecraft.sounds.SoundEvents.SHIELD_BLOCK, net.minecraft.sounds.SoundSource.HOSTILE, 1.5F, 0.65F);
				}
			}

			double blockActiveTicks = entity.getPersistentData().getDouble("rot_block_active_ticks");
			if (blockActiveTicks > 0) {
				blockActiveTicks--;
				entity.getPersistentData().putDouble("rot_block_active_ticks", blockActiveTicks);
				entity.getPersistentData().putBoolean("is_blocking", true);
				if (blockActiveTicks == 0) {
					entity.getPersistentData().putBoolean("is_blocking", false);
					entity.getPersistentData().putBoolean("is_blocking_finish", true);
					entity.getPersistentData().putDouble("rot_block_finish_ticks", 5); // 0.25s
					entity.getPersistentData().putDouble("rot_block_cooldown", 300); // 15 seconds cooldown

					// Follow up with a lethal move immediately but not always (50% chance)
					if (Math.random() < 0.5) {
						Entity followTarget = acquireTarget(world, entity, entity.getX(), entity.getY(), entity.getZ());
						if (followTarget instanceof LivingEntity targetLiv && targetLiv.isAlive()) {
							double moveRand = Math.random();
							if (moveRand < 0.33) {
								// Move 1: Seismic Slam
								entity.getPersistentData().putDouble("sentinel_slam_phase", 1);
								entity.getPersistentData().putDouble("sentinel_slam_ticks", 22);
								entity.getPersistentData().putDouble("rot_overhead_ticks", 0);
								entity.setDeltaMovement(0.0, 1.9, 0.0);
								entity.hasImpulse = true;
								if (world instanceof ServerLevel level) {
									playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
									level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.5, entity.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
								}
							} else if (moveRand < 0.66) {
								// Move 2: Judgment Dropkick
								entity.getPersistentData().putDouble("sentinel_judgment_ticks", 60);
								entity.getPersistentData().putDouble("sentinel_slam_phase", 0);
								entity.getPersistentData().putDouble("rot_overhead_ticks", 0);
								entity.getPersistentData().putDouble("sentinel_die_kick_phase", 0);
								entity.setDeltaMovement(0.0, 0.0, 0.0);
								entity.hasImpulse = true;
							} else {
								// Move 3: Overhead Slam
								entity.getPersistentData().putDouble("rot_overhead_ticks", OVERHEAD_TOTAL_TICKS);
								entity.getPersistentData().putDouble("sentinel_slam_phase", 0);
								entity.getPersistentData().putString("overhead_target_uuid", targetLiv.getUUID().toString());
							}
						}
					}
				}
			} else {
				// Safety valve: if active ticks is 0 but is_blocking is true, reset is_blocking
				if (entity.getPersistentData().getBoolean("is_blocking")) {
					entity.getPersistentData().putBoolean("is_blocking", false);
				}
			}
		} else {
			if (entity.getPersistentData().getBoolean("is_blocking")) {
				entity.getPersistentData().putBoolean("is_blocking", false);
				entity.getPersistentData().putDouble("rot_block_active_ticks", 0.0);
			}
			entity.getPersistentData().putBoolean("debug_force_block", false);
		}

		double blockFinishTicks = entity.getPersistentData().getDouble("rot_block_finish_ticks");
		if (blockFinishTicks > 0) {
			blockFinishTicks--;
			entity.getPersistentData().putDouble("rot_block_finish_ticks", blockFinishTicks);
			if (blockFinishTicks == 0) {
				entity.getPersistentData().putBoolean("is_blocking_finish", false);
			}
		}

		// Sliding window for DPS calculation
		double dpsTickCounter = entity.getPersistentData().getDouble("rot_dps_tick_counter") + 1;
		if (dpsTickCounter >= 20) {
			dpsTickCounter = 0;
			double sec0 = entity.getPersistentData().getDouble("rot_dmg_this_sec");
			double sec1 = entity.getPersistentData().getDouble("rot_dmg_sec_0");
			double sec2 = entity.getPersistentData().getDouble("rot_dmg_sec_1");
			double sec3 = entity.getPersistentData().getDouble("rot_dmg_sec_2");
			double sec4 = entity.getPersistentData().getDouble("rot_dmg_sec_3");

			entity.getPersistentData().putDouble("rot_dmg_sec_4", sec4);
			entity.getPersistentData().putDouble("rot_dmg_sec_3", sec3);
			entity.getPersistentData().putDouble("rot_dmg_sec_2", sec2);
			entity.getPersistentData().putDouble("rot_dmg_sec_1", sec1);
			entity.getPersistentData().putDouble("rot_dmg_sec_0", sec0);
			entity.getPersistentData().putDouble("rot_dmg_this_sec", 0);
		}
		entity.getPersistentData().putDouble("rot_dps_tick_counter", dpsTickCounter);

		double landingTicksAfter = entity.getPersistentData().getDouble("sentinel_landing_ticks");
		if (landingTicksBefore > 0 && landingTicksAfter <= 0 && entity.getPersistentData().getBoolean("sentinel_immune_slam_landing_active")) {
			entity.getPersistentData().putBoolean("sentinel_immune_slam_landing_active", false);
			if (entity.getPersistentData().getBoolean("sentinel_should_scan")) {
				int summons = entity.getPersistentData().getInt("sentinel_spore_summons_at_birth");
				double scanTicks = Math.max(15.0, 60.0 - (summons - 1) * 15.0);
				entity.getPersistentData().putDouble("sentinel_scanning_ticks", scanTicks);
				entity.getPersistentData().putDouble("sentinel_scan_max_ticks", scanTicks);
				entity.getPersistentData().putDouble("sentinel_scanning_base_yaw", entity.getYRot());
			}
		}

		double shockwaveStage = entity.getPersistentData().getDouble("sentinel_shockwave_stage");
		if (shockwaveStage > 0) {
			double originX = entity.getPersistentData().getDouble("sentinel_shockwave_x");
			double originY = entity.getPersistentData().getDouble("sentinel_shockwave_y");
			double originZ = entity.getPersistentData().getDouble("sentinel_shockwave_z");
			double radius = shockwaveStage * 2.2;
			boolean isVertical = entity.getPersistentData().getBoolean("sentinel_shockwave_vertical");

			if (world instanceof ServerLevel level) {
				int particleCount = (int) (2 * Math.PI * radius * 7.5);
				net.minecraft.world.level.block.state.BlockState floorState = level.getBlockState(net.minecraft.core.BlockPos.containing(originX, originY - 0.5, originZ));
				if (floorState.isAir()) {
					floorState = level.getBlockState(net.minecraft.core.BlockPos.containing(originX, originY - 1.5, originZ));
				}
				if (floorState.isAir()) {
					floorState = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
				}
				net.minecraft.core.particles.BlockParticleOption dustPillarOptions = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.DUST_PILLAR, floorState);
				net.minecraft.core.particles.ParticleType<?> _tsdType = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse("trial_spawner_detection"));
				net.minecraft.core.particles.ParticleOptions trialSpawnerDetection = _tsdType instanceof net.minecraft.core.particles.ParticleOptions _tsdOpt ? _tsdOpt : net.minecraft.core.particles.ParticleTypes.EFFECT;

				for (int i = 0; i < particleCount; i++) {
					double angle = (2 * Math.PI / particleCount) * i;
					double cos = Math.cos(angle);
					double sin = Math.sin(angle);

					if (isVertical) {
						// Dense vertical thick ring standing perpendicular to the direction of the kick, expanding outwards with custom velocities
						double yaw = entity.getPersistentData().contains("sentinel_shockwave_yaw")
							? entity.getPersistentData().getDouble("sentinel_shockwave_yaw")
							: entity.getYRot();
						double yawRad = Math.toRadians(yaw);
						double cosYaw = Math.cos(yawRad);
						double sinYaw = Math.sin(yawRad);

						double r = radius + (Math.random() - 0.5) * 0.8;
						double px = originX + cos * r * sinYaw;
						double py = originY + sin * r;
						double pz = originZ - cos * r * cosYaw;

						// Outward velocity vector matching expansion speed
						double spread = 0.85 + Math.random() * 0.3;
						double vx = cos * sinYaw * (2.2 * spread);
						double vy = sin * (2.2 * spread);
						double vz = -cos * cosYaw * (2.2 * spread);

						level.sendParticles(dustPillarOptions, px, py, pz, 0, vx, vy, vz, 1.0);
						level.sendParticles(trialSpawnerDetection, px, py, pz, 0, vx * 0.5, vy * 0.5, vz * 0.5, 1.0);
					} else {
						// Horizontal Standard Thick Ring expanding dynamically with outward velocities
						double r = radius + (Math.random() - 0.5) * 0.8;
						double px = originX + cos * r;
						double pz = originZ + sin * r;

						// Outward velocity vector matching expansion speed
						double spread = 0.85 + Math.random() * 0.3;
						double vx = cos * (2.2 * spread);
						double vz = sin * (2.2 * spread);

						level.sendParticles(dustPillarOptions, px, originY + 0.15, pz, 0, vx, 0.02, vz, 1.0);
						level.sendParticles(trialSpawnerDetection, px, originY + 0.15, pz, 0, vx * 0.5, 0.02, vz * 0.5, 1.0);

						if (i % 2 == 0) {
							double rCloud = radius + (Math.random() - 0.5) * 0.8;
							double pxCloud = originX + cos * rCloud;
							double pzCloud = originZ + sin * rCloud;

							double spreadCloud = 0.45 + Math.random() * 0.2;
							double vxCloud = cos * (2.2 * spreadCloud);
							double vzCloud = sin * (2.2 * spreadCloud);

							level.sendParticles(dustPillarOptions, pxCloud, originY + 0.1, pzCloud, 0, vxCloud, 0.02, vzCloud, 1.0);
						}
					}
				}
				if (shockwaveStage % 2 == 1) {
					playHostileSound(level, originX, originY, originZ, "entity.wind_charge.wind_burst", 1.4F, 0.7F - ((float) shockwaveStage * 0.03F));
				}

				AABB checkArea = new AABB(originX - radius - 1.5, originY - radius - 1.5, originZ - radius - 1.5, originX + radius + 1.5, originY + radius + 1.5, originZ + radius + 1.5);

				java.util.List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, checkArea, e -> e != entity);
				for (LivingEntity victim : targets) {
					double dist = Math.sqrt(victim.distanceToSqr(originX, originY, originZ));
					if (Math.abs(dist - radius) <= 1.5) {
						victim.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_seismic_slam"))), entity), 12.0F * (float) getAdaptationMultiplier(entity));
						victim.setDeltaMovement(victim.getDeltaMovement().x(), 0.65, victim.getDeltaMovement().z());
						victim.hasImpulse = true;
						level.sendParticles(ParticleTypes.EXPLOSION, victim.getX(), victim.getY() + 0.5, victim.getZ(), 1, 0, 0, 0, 0);
					}
				}
			}

			if (shockwaveStage >= 8) {
				entity.getPersistentData().putDouble("sentinel_shockwave_stage", 0);
				entity.getPersistentData().putBoolean("sentinel_shockwave_vertical", false);
			} else {
				entity.getPersistentData().putDouble("sentinel_shockwave_stage", shockwaveStage + 1);
			}
		}

		double landingTicks = entity.getPersistentData().getDouble("sentinel_landing_ticks");
		if (landingTicks > 0) {
			entity.setDeltaMovement(0, -0.05, 0);
			if (entity instanceof Mob mob) {
				mob.getNavigation().stop();
			}
			if (entity instanceof LivingEntity living) {
				living.setYBodyRot(living.getYRot());
			}
			double cc1 = entity.getPersistentData().getDouble("sentinel_cc1_stage");
			double cc2 = entity.getPersistentData().getDouble("sentinel_cc2_stage");
			double cc3 = entity.getPersistentData().getDouble("sentinel_cc3_stage");
			double cc4 = entity.getPersistentData().getDouble("sentinel_cc4_stage");
			double cc5 = entity.getPersistentData().getDouble("sentinel_cc5_stage");
			if (cc1 <= 0 && cc2 <= 0 && cc3 <= 0 && cc4 <= 0 && cc5 <= 0) {
				return;
			}
		}

		if (entity instanceof LivingEntity living) {
			boolean inColdBiome = false;
			try {
				inColdBiome = living.level().getBiome(living.blockPosition()).unwrapKey().map(key -> {
					String path = key.location().getPath().toLowerCase(java.util.Locale.ROOT);
					return path.contains("snow") || path.contains("frozen") || path.contains("ice") || path.contains("cold");
				}).orElse(false);
			} catch (Exception ignored) {}
			if (inColdBiome) {
				double coldTime = living.getPersistentData().getDouble("sentinel_time_in_cold_biome");
				living.getPersistentData().putDouble("sentinel_time_in_cold_biome", coldTime + 1.0);
			}
			if (living.level().dimension() == net.minecraft.world.level.Level.NETHER) {
				double netherTime = living.getPersistentData().getDouble("sentinel_time_in_nether");
				living.getPersistentData().putDouble("sentinel_time_in_nether", netherTime + 1.0);
			}
		}

		Entity combatTarget = acquireTarget(world, entity, x, y, z);
		handleAdaptationScaling(entity, combatTarget != null);

		// Track unreachable flying or pillaring target for overhead
		if (combatTarget instanceof LivingEntity livTarget) {
			boolean isFlying = livTarget.isFallFlying() || (!livTarget.onGround() && livTarget.getY() > entity.getY() + 2.0 && !livTarget.isInWater() && !livTarget.isInLava());
			boolean isPillaring = isTargetPillaring(world, livTarget, entity);
			boolean isUnreachable = (isFlying || isPillaring) && (entity.distanceTo(livTarget) > 4.5 || livTarget.getY() > entity.getY() + 2.5);
			if (isUnreachable) {
				double flyTicks = entity.getPersistentData().getDouble("target_unreachable_flying_ticks") + 1.0;
				entity.getPersistentData().putDouble("target_unreachable_flying_ticks", flyTicks);
				
				if (flyTicks >= 120.0 && !isChannelingAbility(entity) && entity.getPersistentData().getBoolean("unlocked_overhead_combo")) {
					// Linear chance: increases by 0.2% per tick (4% per second) capped at 50%
					double chance = Math.min(0.50, (flyTicks - 120.0) * 0.002);
					if (entity.getRandom().nextDouble() < chance) {
						entity.getPersistentData().putDouble("rot_overhead_ticks", OVERHEAD_TOTAL_TICKS);
						entity.getPersistentData().putString("overhead_target_uuid", livTarget.getUUID().toString());
						entity.getPersistentData().putDouble("target_unreachable_flying_ticks", 0.0);
					}
				}
			} else {
				entity.getPersistentData().putDouble("target_unreachable_flying_ticks", 0.0);
			}
		} else {
			entity.getPersistentData().putDouble("target_unreachable_flying_ticks", 0.0);
		}

		handleHeavyPunchState(world, entity, combatTarget);

		if (handleCustomCombos(world, entity, combatTarget)) {
			return;
		}

		if (handleOverheadState(world, entity, combatTarget)) {
			return;
		}

		if (handleSlamState(world, entity, combatTarget)) {
			return;
		}

		if (handleDieKickState(world, entity, combatTarget)) {
			return;
		}

		// Totem stealing logic
		if (combatTarget instanceof Player p && !entity.getPersistentData().getBoolean("sentinel_totem_stolen")) {
			double stealTimer = entity.getPersistentData().getDouble("sentinel_totem_steal_timer");
			double combatTicks = entity.getPersistentData().getDouble("sentinel_combat_ticks");
			net.minecraft.world.item.ItemStack totemStack = net.minecraft.world.item.ItemStack.EMPTY;
			boolean isInfinityTotem = false;
			for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) {
				net.minecraft.world.item.ItemStack s = p.getInventory().getItem(slot);
				if (!s.isEmpty()) {
					String itemId = BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
					if (itemId.equals("avaritia:infinity_totem")) {
						totemStack = s;
						isInfinityTotem = true;
						break;
					}
				}
			}
			if (totemStack.isEmpty()) {
				for (int slot = 0; slot < p.getInventory().getContainerSize(); slot++) {
					net.minecraft.world.item.ItemStack s = p.getInventory().getItem(slot);
					if (!s.isEmpty() && s.getItem() == net.minecraft.world.item.Items.TOTEM_OF_UNDYING) {
						totemStack = s;
						break;
					}
				}
			}

			double targetStealTime = entity.getPersistentData().getDouble("sentinel_totem_target_steal_time");
			if (targetStealTime <= 0) {
				double minTime = TOTEM_STEAL_TIME_MIN;
				double maxTime = TOTEM_STEAL_TIME_MAX;
				if (maxTime < minTime) {
					maxTime = minTime;
				}
				double range = maxTime - minTime;
				if (range > 0) {
					targetStealTime = minTime + RandomSource.create().nextInt((int) range + 1);
				} else {
					targetStealTime = minTime;
				}
				entity.getPersistentData().putDouble("sentinel_totem_target_steal_time", targetStealTime);
			}

			if (!totemStack.isEmpty() && entity.distanceTo(p) <= 12.0 && combatTicks >= 200.0) {
				stealTimer += 1.0;
				entity.getPersistentData().putDouble("sentinel_totem_steal_timer", stealTimer);
				if (stealTimer >= targetStealTime) {
					totemStack.shrink(1);
					entity.getPersistentData().putBoolean("sentinel_is_infinity_totem", isInfinityTotem);
					if (entity instanceof LivingEntity living) {
						net.minecraft.world.item.Item infinityTotemItem = isInfinityTotem ? BuiltInRegistries.ITEM.get(ResourceLocation.parse("avaritia:infinity_totem")) : null;
						net.minecraft.world.item.ItemStack stackToHold = (infinityTotemItem != null) ? new net.minecraft.world.item.ItemStack(infinityTotemItem) : new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
						living.setItemInHand(InteractionHand.MAIN_HAND, stackToHold);
					}
					entity.getPersistentData().putBoolean("sentinel_totem_stolen", true);
					entity.getPersistentData().putDouble("sentinel_totem_inspect_ticks", 180);
					entity.getPersistentData().putDouble("sentinel_solar_charge_ticks", 0);
					entity.getPersistentData().putDouble("sentinel_solar_fire_ticks", 0);
					entity.getPersistentData().putDouble("sentinel_cryo_charge_ticks", 0);
					entity.getPersistentData().putDouble("sentinel_cryo_fire_ticks", 0);
					if (world instanceof ServerLevel level) {
						playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "block.bell.resonate", 1.5F, 0.6F);
						String notice = isInfinityTotem ? "§dAn endless light..." : "§7Intriguing artifact...";
						p.displayClientMessage(Component.literal(notice), true);
					}
					handlePassengerAndGrowth(entity);
					return;
				}
			} else {
				if (stealTimer > 0) {
					entity.getPersistentData().putDouble("sentinel_totem_steal_timer", Math.max(0.0, stealTimer - 1.0));
				} else {
					entity.getPersistentData().putDouble("sentinel_totem_target_steal_time", 0.0);
				}
			}
		}

		double totemInspectTicks = entity.getPersistentData().getDouble("sentinel_totem_inspect_ticks");
		if (totemInspectTicks > 0) {
			if (entity instanceof LivingEntity living) {
				boolean isInfinity = entity.getPersistentData().getBoolean("sentinel_is_infinity_totem");
				net.minecraft.world.item.Item infinityTotemItem = isInfinity ? BuiltInRegistries.ITEM.get(ResourceLocation.parse("avaritia:infinity_totem")) : null;
				net.minecraft.world.item.ItemStack stackToHold = (infinityTotemItem != null) ? new net.minecraft.world.item.ItemStack(infinityTotemItem) : new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
				living.setItemInHand(InteractionHand.MAIN_HAND, stackToHold);
			}
			entity.getPersistentData().putDouble("sentinel_totem_inspect_ticks", totemInspectTicks - 1.0);
			entity.setDeltaMovement(0, entity.getDeltaMovement().y() * 0.5, 0);
			entity.hurtMarked = true;
			if (entity instanceof Mob mob) {
				mob.getNavigation().stop();
				mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0.0);
			}

			// Smooth look down logic
			Vec3 lookDownTarget = entity.position().add(entity.getViewVector(1.0F).scale(0.5)).add(0, entity.getBbHeight() * 0.35, 0);
			Vec3 targetPos = null;
			if (combatTarget != null) {
				targetPos = new Vec3(combatTarget.getX(), combatTarget.getY() + combatTarget.getBbHeight() * 0.75, combatTarget.getZ());
			} else {
				targetPos = entity.position().add(entity.getViewVector(1.0F).scale(4.0)).add(0, entity.getBbHeight() * 0.75, 0);
			}
			double w = 1.0;
			if (totemInspectTicks > 150.0) {
				w = (180.0 - totemInspectTicks) / 30.0;
			} else if (totemInspectTicks < 40.0) {
				w = totemInspectTicks / 40.0;
			}
			Vec3 finalLookTarget = new Vec3(
				targetPos.x + (lookDownTarget.x - targetPos.x) * w,
				targetPos.y + (lookDownTarget.y - targetPos.y) * w,
				targetPos.z + (lookDownTarget.z - targetPos.z) * w
			);
			entity.lookAt(EntityAnchorArgument.Anchor.EYES, finalLookTarget);

			if (totemInspectTicks > 60.0) {
				if (totemInspectTicks % 20 == 0) {
					playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sniff", 1.2F, 0.65F);
				}
			} else {
				if (totemInspectTicks % 10 == 0) {
					playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "block.amethyst_block.hit", 1.4F, 0.75F);
				}
			}

			boolean totemPoppedEarly = false;
			if (entity instanceof LivingEntity living && totemInspectTicks < 178.0) {
				boolean isInfinity = entity.getPersistentData().getBoolean("sentinel_is_infinity_totem");
				net.minecraft.world.item.Item infinityTotemItem = isInfinity ? BuiltInRegistries.ITEM.get(ResourceLocation.parse("avaritia:infinity_totem")) : null;
				net.minecraft.world.item.Item expectedItem = (infinityTotemItem != null) ? infinityTotemItem : net.minecraft.world.item.Items.TOTEM_OF_UNDYING;
				if (living.getItemInHand(InteractionHand.MAIN_HAND).getItem() != expectedItem) {
					totemPoppedEarly = true;
				}
			}

			if (totemInspectTicks == 1.0 || totemPoppedEarly) {
				entity.getPersistentData().putBoolean("sentinel_totem_active", true);
				entity.getPersistentData().putDouble("sentinel_totem_inspect_ticks", 0.0);
				if (entity instanceof LivingEntity living) {
					living.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
				}
				if (world instanceof ServerLevel level) {
					boolean isInfinity = entity.getPersistentData().getBoolean("sentinel_is_infinity_totem");
					level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, entity.getX(), entity.getY() + 1.0, entity.getZ(), 45, 0.6, 0.6, 0.6, 0.25);
					if (isInfinity) {
						level.sendParticles(ParticleTypes.FLASH, entity.getX(), entity.getY() + 1.0, entity.getZ(), 5, 0.5, 0.5, 0.5, 0.0);
						level.sendParticles(ParticleTypes.DRAGON_BREATH, entity.getX(), entity.getY() + 1.0, entity.getZ(), 100, 0.8, 0.8, 0.8, 0.15);
						level.sendParticles(ParticleTypes.REVERSE_PORTAL, entity.getX(), entity.getY() + 1.0, entity.getZ(), 80, 0.8, 0.8, 0.8, 0.15);
					}
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "item.totem.use", 2.0F, isInfinity ? 0.45F : 0.85F);
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.player.hurt_on_fire", 1.2F, 0.5F);
					if (isInfinity) {
						playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.generic.explode", 2.0F, 0.5F);
						playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sonic_boom", 2.0F, 0.4F);
					}
					if (combatTarget instanceof Player p) {
						String quote;
						if (isInfinity) {
							quote = "§d\"This is the power of the stars... Stolen from your infinity. Prepare thyself.\"";
						} else {
							quote = TOTEM_QUOTES[RandomSource.create().nextInt(TOTEM_QUOTES.length)];
						}
						p.displayClientMessage(Component.literal(quote), true);
					}
				}
			}
			handlePassengerAndGrowth(entity);
			return;
		}

		// Sonic Scream active state machine early check
		double sonicScreamTicksFirstCheck = entity.getPersistentData().getDouble("sentinel_sonic_scream_ticks");
		if (sonicScreamTicksFirstCheck > 0) {
			executeSentinelSonicScream(world, entity, combatTarget, (int) sonicScreamTicksFirstCheck);
			if (combatTarget != null) {
				lockLookAtTarget(entity, combatTarget);
			}
			handlePassengerAndGrowth(entity);
			return;
		}

		double omniSonicTicks = entity.getPersistentData().getDouble("sentinel_omni_sonic_charge_ticks");
		if (omniSonicTicks > 0) {
			entity.setDeltaMovement(entity.getDeltaMovement().x() * 0.05, entity.getDeltaMovement().y(), entity.getDeltaMovement().z() * 0.05);
			if (entity instanceof Mob mob) {
				mob.getNavigation().stop();
			}
			entity.getPersistentData().putBoolean("is_sonic_boom_large", true);

			if (world instanceof ServerLevel level) {
				Vec3 center = entity.position().add(0, entity.getBbHeight() * 0.5, 0);
				
				if (OMNI_SONIC_BOOM_SHOW_PARTICLES) {
					// Swirling buildup phase (when omniSonicTicks is greater than OMNI_SONIC_BOOM_TRIGGER_TICK)
					if (omniSonicTicks > OMNI_SONIC_BOOM_TRIGGER_TICK) {
						double chargeTotal = OMNI_SONIC_BOOM_ANIMATION_TICKS - OMNI_SONIC_BOOM_TRIGGER_TICK;
						double elapsed = OMNI_SONIC_BOOM_ANIMATION_TICKS - omniSonicTicks;
						double progress = elapsed / chargeTotal; // 0.0 to 1.0
						
						// Particles swirl and shrink onto the torso
						double radius = 6.0 - progress * 5.0; // Shrinks from 6.0 to 1.0 block radius
						
						// Swirling hollow sphere of SONIC_BOOM and CRIT particles (reduced density)
						int count = (int) Math.max(4, (4.0 * Math.PI * radius * radius * 1.0));
						if (count > 60) count = 60;
						double goldenRatio = (1.0 + Math.sqrt(5.0)) / 2.0;
						for (int i = 0; i < count; i++) {
							double theta = 2 * Math.PI * i / goldenRatio;
							double phi = Math.acos(1.0 - 2.0 * (i + 0.5) / count);
							double sx = Math.cos(theta) * Math.sin(phi);
							double sy = Math.sin(theta) * Math.sin(phi);
							double sz = Math.cos(phi);
							
							double px = center.x + sx * radius;
							double py = center.y + sy * radius;
							double pz = center.z + sz * radius;
							
							// Add a tiny bit of random displacement
							double rx = (level.getRandom().nextDouble() - 0.5) * 0.02;
							double ry = (level.getRandom().nextDouble() - 0.5) * 0.02;
							double rz = (level.getRandom().nextDouble() - 0.5) * 0.02;
							
							level.sendParticles(ParticleTypes.CRIT, px + rx, py + ry, pz + rz, 1, 0.0, 0.0, 0.0, 0.0);
							if (level.getRandom().nextDouble() < 0.10) {
								level.sendParticles(ParticleTypes.SONIC_BOOM, px + rx, py + ry, pz + rz, 1, 0.0, 0.0, 0.0, 0.0);
							}
						}
						
						// Slowly add more fireworks flashes on its torso
						if (level.getRandom().nextDouble() < progress * 0.85) {
							level.sendParticles(ParticleTypes.FLASH, entity.getX(), entity.getY() + 1.2, entity.getZ(), 1, 0.1, 0.1, 0.1, 0.0);
						}
					} else {
						// After release (decay phase), expand/radiate outward
						double decayTotal = OMNI_SONIC_BOOM_TRIGGER_TICK;
						double elapsedDecay = OMNI_SONIC_BOOM_TRIGGER_TICK - omniSonicTicks;
						double progressDecay = elapsedDecay / decayTotal; // 0.0 to 1.0
						
						double currentRadius = 1.0 + progressDecay * OMNI_SONIC_BOOM_RANGE;
						
						// Expand a majestic ring or shell outward (reduced density)
						int count = (int) Math.max(6, (4.0 * Math.PI * currentRadius * currentRadius * 0.3));
						if (count > 35) count = 35;
						double goldenRatio = (1.0 + Math.sqrt(5.0)) / 2.0;
						for (int i = 0; i < count; i++) {
							double theta = 2 * Math.PI * i / goldenRatio;
							double phi = Math.acos(1.0 - 2.0 * (i + 0.5) / count);
							double sx = Math.cos(theta) * Math.sin(phi);
							double sy = Math.sin(theta) * Math.sin(phi);
							double sz = Math.cos(phi);
							
							double px = center.x + sx * currentRadius;
							double py = center.y + sy * currentRadius;
							double pz = center.z + sz * currentRadius;
							
							if (level.getRandom().nextDouble() < 0.25) {
								level.sendParticles(ParticleTypes.SONIC_BOOM, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
							}
							if (level.getRandom().nextDouble() < 0.2) {
								level.sendParticles(ParticleTypes.CLOUD, px, py, pz, 1, 0.1, 0.1, 0.1, 0.02);
							}
						}
					}
				}
			}

			if (omniSonicTicks == OMNI_SONIC_BOOM_TRIGGER_TICK) {
				if (world instanceof ServerLevel level) {
					boolean totemActive = entity.getPersistentData().getBoolean("sentinel_totem_active");
					double radius = totemActive ? OMNI_SONIC_BOOM_RANGE * 1.5 : OMNI_SONIC_BOOM_RANGE;
					
					// Spawning large release burst ring of sonic booms (reduced density)
					if (OMNI_SONIC_BOOM_SHOW_PARTICLES) {
						for (int angleDeg = 0; angleDeg < 360; angleDeg += (totemActive ? 12 : 24)) {
							double rad = Math.toRadians(angleDeg);
							double dx = Math.sin(rad);
							double dz = Math.cos(rad);
							for (double r = 1.0; r <= radius; r += 4.0) {
								double px = entity.getX() + dx * r;
								double py = entity.getY() + 1.2;
								double pz = entity.getZ() + dz * r;
								level.sendParticles(ParticleTypes.SONIC_BOOM, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
							}
						}
					}
					
					java.util.List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, entity.getBoundingBox().inflate(radius), e -> e != entity);
					for (LivingEntity targetVictim : targets) {
						float damageVal = totemActive ? 85.0F : 48.0F; // Slightly buffed for being the ultimate 7s move!
						targetVictim.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, net.minecraft.resources.ResourceLocation.parse("the_backwoods:rot_sonic_boom"))), entity), damageVal * (float) getAdaptationMultiplier(entity));
						Vec3 push = targetVictim.position().subtract(entity.position()).multiply(1.0, 0.0, 1.0).normalize();
						double pushMult = totemActive ? 8.5 : 6.0;
						double pushUp = totemActive ? 2.25 : 1.5;
						targetVictim.setDeltaMovement(push.x * pushMult, pushUp, push.z * pushMult);
						targetVictim.hasImpulse = true;
					}
					
					BlockPos posCenter = BlockPos.containing(entity.position());
					double baseRadius = totemActive ? 12.0 : 6.5;
					int rangeBound = totemActive ? 12 : 6;
					int heightBoundUpper = totemActive ? 6 : 4;
					int heightBoundLower = totemActive ? -3 : -1;
					for (BlockPos bp : BlockPos.betweenClosed(posCenter.offset(-rangeBound, heightBoundLower, -rangeBound), posCenter.offset(rangeBound, heightBoundUpper, rangeBound))) {
						double dx = bp.getX() - posCenter.getX();
						double dz = bp.getZ() - posCenter.getZ();
						double distSq = dx * dx + dz * dz;
						double randomRadius = baseRadius + (Math.random() * 2.0 - 1.0);
						if (distSq <= randomRadius * randomRadius) {
							BlockState bs = level.getBlockState(bp);
							if (!bs.isAir() && bs.getDestroySpeed(level, bp) >= 0 && bs.getDestroySpeed(level, bp) < (totemActive ? 80.0F : 50.0F)) {
								level.destroyBlock(bp, false);
							}
						}
					}
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sonic_boom", 2.5F, 0.40F);
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.generic.explode", 2.0F, 0.5F);
				}
			}
			handlePassengerAndGrowth(entity);
			return;
		} else {
			entity.getPersistentData().putBoolean("is_sonic_boom_large", false);
		}

		double sonicTicksFirstCheck = entity.getPersistentData().getDouble("sentinel_sonic_ticks");
		if (sonicTicksFirstCheck > 0) {
			entity.setDeltaMovement(0, entity.getDeltaMovement().y(), 0);
			if (entity instanceof Mob mob) {
				mob.getNavigation().stop();
			}
			if (combatTarget != null) {
				lockLookAtTarget(entity, combatTarget);
			}
			if (sonicTicksFirstCheck == SONIC_BOOM_TRIGGER_TICK && combatTarget != null) {
				fireSuperchargedSonicBoomEffectAndDamage(world, entity, combatTarget);
			}
			handlePassengerAndGrowth(entity);
			return;
		}

		double skyWarpTicks = entity.getPersistentData().getDouble("sentinel_sky_warp_slam_ticks");
		if (skyWarpTicks > 0) {
			if (skyWarpTicks > 1) {
				entity.setDeltaMovement(entity.getDeltaMovement().x() * 0.1, Math.max(0, entity.getDeltaMovement().y() * 0.5), entity.getDeltaMovement().z() * 0.1);
			}
			if (combatTarget != null) {
				lockLookAtTarget(entity, combatTarget);
				if (skyWarpTicks == 1) {
					double targetX = combatTarget.getX();
					double targetY = combatTarget.getY() + 3.8;
					double targetZ = combatTarget.getZ();
					if (world instanceof ServerLevel level) {
						level.sendParticles(ParticleTypes.PORTAL, entity.getX(), entity.getY() + 1.1, entity.getZ(), 8, 0.2, 0.2, 0.2, 0.05);
						teleportEntity(entity, targetX, targetY, targetZ);
						level.sendParticles(ParticleTypes.PORTAL, targetX, targetY + 1.1, targetZ, 8, 0.2, 0.2, 0.2, 0.05);
						playHostileSound(level, targetX, targetY, targetZ, "item.chorus_fruit.teleport", 1.3F, 1.1F);
						entity.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(combatTarget.getX(), combatTarget.getY() + combatTarget.getBbHeight() * 0.5, combatTarget.getZ()));
						playHostileSound(level, targetX, targetY, targetZ, "entity.iron_golem.attack", 1.4F, 0.5F);
						playHostileSound(level, targetX, targetY, targetZ, "entity.player.attack.sweep", 1.4F, 0.7F);
						level.sendParticles(ParticleTypes.SWEEP_ATTACK, targetX, targetY - 1.0, targetZ, 3, 0.4, 0.4, 0.4, 0.0);
						if (combatTarget instanceof LivingEntity livTarget) {
							livTarget.setDeltaMovement(0.0, -4.5, 0.0);
							livTarget.hasImpulse = true;
							livTarget.getPersistentData().putBoolean("sentinel_sky_warp_slam_impact", true);
							double punchDmg = entity.getPersistentData().getDouble("adapted_punch_damage");
							if (punchDmg < 8.0) punchDmg = 8.0;
							livTarget.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_overhead_slam"))), entity), (float) (punchDmg * 2.2) * (float) getAdaptationMultiplier(entity));
						}
					}
				}
			}
			handlePassengerAndGrowth(entity);
			return;
		}

		if (combatTarget instanceof LivingEntity livTarget && livTarget.getPersistentData().getBoolean("sentinel_sky_warp_slam_impact")) {
			if (livTarget.onGround() || livTarget.getDeltaMovement().y > -0.1) {
				livTarget.getPersistentData().putBoolean("sentinel_sky_warp_slam_impact", false);
				if (world instanceof ServerLevel level) {
					double tx = livTarget.getX();
					double ty = livTarget.getY();
					double tz = livTarget.getZ();
					boolean totemActive = entity.getPersistentData().getBoolean("sentinel_totem_active");
					float explosionForce = totemActive ? 6.2F : 3.5F;
					level.explode(entity, tx, ty + 0.5, tz, explosionForce * (float) Math.min(5.0, getAdaptationMultiplier(entity)), Level.ExplosionInteraction.MOB);
					level.sendParticles(ParticleTypes.EXPLOSION, tx, ty + 0.5, tz, totemActive ? 15 : 6, 0.6, 0.2, 0.6, 0.15);
					level.sendParticles(ParticleTypes.CRIT, tx, ty + 0.5, tz, totemActive ? 40 : 20, 0.5, 0.5, 0.5, 0.3);
					level.sendParticles(ParticleTypes.SONIC_BOOM, tx, ty + 0.5, tz, totemActive ? 3 : 1, 0.1, 0.1, 0.1, 0.0);
					playHostileSound(level, tx, ty, tz, "entity.generic.explode", 1.4F, 0.85F);
					playHostileSound(level, tx, ty, tz, "entity.iron_golem.death", 1.1F, 0.65F);
					List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(new Vec3(tx, ty, tz), totemActive ? 10 : 5, totemActive ? 8 : 5, totemActive ? 10 : 5), e -> e != entity && e != livTarget);
					for (LivingEntity near : nearby) {
						if (near.isAlive()) {
							float nearDmg = totemActive ? 22.0F : 10.0F;
							near.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_overhead_slam"))), entity), nearDmg * (float) getAdaptationMultiplier(entity));
							Vec3 pushAway = near.position().subtract(livTarget.position()).normalize();
							double pushMult = totemActive ? 2.5 : 1.5;
							near.setDeltaMovement(pushAway.x * pushMult, 0.5, pushAway.z * pushMult);
							near.hasImpulse = true;
						}
					}
				}
			}
		}

		double judgmentTicks = entity.getPersistentData().getDouble("sentinel_judgment_ticks");
		if (judgmentTicks > 0) {
			if (combatTarget != null) {
				lockLookAtTarget(entity, combatTarget);
			}

			if (judgmentTicks > 20) {
				// Phase 1: Charging (first 40 ticks, 60 down to 21)
				// Keep grounded and crouch on the floor, strictly no movement during charge
				entity.setDeltaMovement(0.0, 0.0, 0.0);
				if (world instanceof ServerLevel level) {
					level.sendParticles(ParticleTypes.PORTAL, entity.getX(), entity.getY() + 0.2, entity.getZ(), 3, 0.3, 0.1, 0.3, 0.02);
					if (judgmentTicks % 5 == 0) {
						playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.heartbeat", 1.2F, 0.7F);
					}
				}
			} else if (judgmentTicks == 20) {
				// Phase 2: Warp/Teleport Horizontally in front of Player ready to Dropkick (from tick 20 down to 2)
				Entity targetLoc = combatTarget != null ? combatTarget : entity;
				double targetYaw = targetLoc.getYRot();
				double radians = Math.toRadians(targetYaw);
				// Minos Prime style: Spawn 4.5 blocks in front of the target, matching the target's Y level to support vertical/aerial dropkicks
				double kickStartX = targetLoc.getX() - 4.5 * Math.sin(radians);
				double kickStartY = targetLoc.getY();
				double kickStartZ = targetLoc.getZ() + 4.5 * Math.cos(radians);

				teleportEntity(entity, kickStartX, kickStartY, kickStartZ);
				if (combatTarget != null) {
					lockLookAtTarget(entity, combatTarget);
				}

				playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sonic_charge", 1.8F, 0.65F);
				if (world instanceof ServerLevel level) {
					level.sendParticles(ParticleTypes.FLASH, entity.getX(), entity.getY() + 0.5, entity.getZ(), 1, 0, 0, 0, 0);
				}
			} else if (judgmentTicks > 1) {
				// Phase 3: Supersonic Diagonal/Aerial Dropkick Flight in a strict straight 3D line (ticks 19 to 2)
				if (combatTarget != null) {
					double dx = combatTarget.getX() - entity.getX();
					double dy = combatTarget.getY() - entity.getY();
					double dz = combatTarget.getZ() - entity.getZ();
					double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
					if (dist > 0.1) {
						// Zoom towards the target at supersonic speed (8.0 blocks/tick) in a straight 3D line
						entity.setDeltaMovement(dx * 8.0 / dist, dy * 8.0 / dist, dz * 8.0 / dist);
					}
					// Responsive physical impact: Trigger landing instantly if we are extremely close horizontally or 3D (within 8.2 blocks range)
					double dist3D = entity.position().distanceTo(combatTarget.position());
					if (dist3D < 8.2) {
						// We set it to 2 so that after the tick decrement, it will start tick 1 as Phase 4 Landing
						entity.getPersistentData().putDouble("sentinel_judgment_ticks", 2);
						judgmentTicks = 2;
					}
				} else {
					Vec3 look = entity.getLookAngle();
					entity.setDeltaMovement(look.x * 8.0, look.y * 8.0, look.z * 8.0);
				}
				entity.hasImpulse = true;
				entity.fallDistance = 0;
				if (world instanceof ServerLevel level) {
					// Removed the purple-fire looking PORTAL particles during flight, using clean cloud/wind lines for is_supersonic dash aesthetics
					level.sendParticles(ParticleTypes.GUST, entity.getX(), entity.getY() + 0.3, entity.getZ(), 5, 0.1, 0.1, 0.1, 0.05);
					level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.3, entity.getZ(), 3, 0.1, 0.1, 0.1, 0.02);
				}
			} else if (judgmentTicks == 1) {
				// Phase 4: Land/Impact on Player!
				if (combatTarget instanceof LivingEntity targetLiv) {
					double targetX = targetLiv.getX();
					double targetY = targetLiv.getY();
					double targetZ = targetLiv.getZ();

					if (world instanceof ServerLevel level) {
						double impactY = targetY;
						teleportEntity(entity, targetX, impactY, targetZ);
						net.minecraft.world.level.block.state.BlockState floorState = level.getBlockState(net.minecraft.core.BlockPos.containing(targetX, impactY - 0.5, targetZ));
						if (floorState.isAir()) {
							floorState = level.getBlockState(net.minecraft.core.BlockPos.containing(targetX, impactY - 1.5, targetZ));
						}
						if (floorState.isAir()) {
							floorState = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
						}
						net.minecraft.core.particles.BlockParticleOption dustPillarOptions = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.DUST_PILLAR, floorState);
						net.minecraft.core.particles.ParticleType<?> _tsdType = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse("trial_spawner_detection"));
						net.minecraft.core.particles.ParticleOptions trialSpawnerDetection = _tsdType instanceof net.minecraft.core.particles.ParticleOptions _tsdOpt ? _tsdOpt : net.minecraft.core.particles.ParticleTypes.EFFECT;

						for (int r = 1; r <= 3; r++) {
							double radiusVal = r * 1.2;
							for (int deg = 0; deg < 360; deg += 30) {
								double rads = Math.toRadians(deg);
								double px = targetX + Math.cos(rads) * radiusVal;
								double pz = targetZ + Math.sin(rads) * radiusVal;
								level.sendParticles(dustPillarOptions, px, impactY + 0.1, pz, 1, 0.0, 0.05, 0.0, 0.01);
								level.sendParticles(trialSpawnerDetection, px, impactY + 0.1, pz, 1, 0.0, 0.05, 0.0, 0.01);
							}
						}

						// Slam-like expanding orange particle ring aesthetics
						for (int r = 1; r <= 5; r++) {
							final double rVal = r;
							int particleCount = (int) (12 * rVal);
							for (int i = 0; i < particleCount; i++) {
								double angle = (2 * Math.PI * i) / particleCount;
								double px = targetX + Math.cos(angle) * rVal;
								double py = impactY + 0.15;
								double pz = targetZ + Math.sin(angle) * rVal;
								double vx = Math.cos(angle) * 0.18;
								double vy = 0.05 + 0.02 * rVal;
								double vz = Math.sin(angle) * 0.18;

								level.sendParticles(dustPillarOptions, px, py, pz, 0, vx, vy, vz, 1.0);
								level.sendParticles(trialSpawnerDetection, px, py, pz, 0, vx * 0.5, vy, vz * 0.5, 1.0);
							}
						}

						playHostileSound(level, targetX, impactY, targetZ, "entity.generic.explode", 1.8F, 0.6F);
						playHostileSound(level, targetX, impactY, targetZ, "entity.warden.sonic_boom", 1.8F, 0.55F);
						playHostileSound(level, targetX, impactY, targetZ, "entity.iron_golem.death", 1.3F, 0.45F);

						boolean totemActive = entity.getPersistentData().getBoolean("sentinel_totem_active");
						boolean isInfinity = entity.getPersistentData().getBoolean("sentinel_is_infinity_totem");
						// Citadel Camera Shake Delivery (Method 1)
						sendCameraShake(totemActive ? 1.5F : 1.0F, totemActive ? 25 : 15, totemActive ? 30.0F : 20.0F);

						if (isInfinity) {
							level.explode(entity, targetX, impactY + 0.5, targetZ, 6.0F * (float) Math.min(5.0, getAdaptationMultiplier(entity)), Level.ExplosionInteraction.MOB);
						} else if (totemActive) {
							level.explode(entity, targetX, impactY + 0.5, targetZ, 5.5F * (float) Math.min(5.0, getAdaptationMultiplier(entity)), Level.ExplosionInteraction.MOB);
						}
						level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, targetX, impactY + 0.5, targetZ, totemActive ? 15 : 6, 0.5, 0.2, 0.5, 0.1);
						level.sendParticles(ParticleTypes.CRIT, targetX, impactY + 0.5, targetZ, totemActive ? 40 : 22, 0.4, 0.4, 0.4, 0.2);

						double punchDmg = entity.getPersistentData().getDouble("adapted_punch_damage");
						if (punchDmg < 8.0) punchDmg = 8.0;
						double dropkickDamageMult = 3.5;
						if (isInfinity) {
							dropkickDamageMult = 3.5 * 2.5;
						}
						targetLiv.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_judgement_dropkick"))), entity), (float) (punchDmg * dropkickDamageMult) * (float) getAdaptationMultiplier(entity));

						// Massive knockback on combat target using 2D Normalization to prevent tiny horizontal velocities from vertical differences
						Vec3 pushVec = targetLiv.position().subtract(entity.position());
						double horizontalDist = Math.sqrt(pushVec.x * pushVec.x + pushVec.z * pushVec.z);
						if (horizontalDist < 0.1) {
							pushVec = entity.getLookAngle();
							horizontalDist = Math.sqrt(pushVec.x * pushVec.x + pushVec.z * pushVec.z);
						}
						if (horizontalDist > 0.01) {
							pushVec = new Vec3(pushVec.x / horizontalDist, 0, pushVec.z / horizontalDist);
						} else {
							pushVec = new Vec3(1, 0, 0);
						}

						double pushForce = totemActive ? 3.0 : 2.2;
						double pushUp = 0.6;
						if (isInfinity) {
							pushForce = 7.5;
							pushUp = 1.4;
						}
						applyKnockbackAndSync(targetLiv, pushVec.x * pushForce, pushUp, pushVec.z * pushForce);

						// Trigger the Vertical Shockwave!
						entity.getPersistentData().putDouble("sentinel_shockwave_stage", 1);
						entity.getPersistentData().putDouble("sentinel_shockwave_x", targetX);
						entity.getPersistentData().putDouble("sentinel_shockwave_y", impactY);
						entity.getPersistentData().putDouble("sentinel_shockwave_z", targetZ);
						entity.getPersistentData().putBoolean("sentinel_shockwave_vertical", true);
						entity.getPersistentData().putDouble("sentinel_shockwave_yaw", entity.getYRot());

						List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, AABB.ofSize(new Vec3(targetX, targetY, targetZ), 8, 6, 8), e -> e != entity && e != targetLiv);
						for (LivingEntity near : nearby) {
							if (near.isAlive()) {
								float baseNearDmg = 15.0F;
								if (isInfinity) baseNearDmg *= 2.5F;
								near.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_judgement_dropkick"))), entity), baseNearDmg * (float) getAdaptationMultiplier(entity));
								Vec3 pushAway = near.position().subtract(entity.position());
								double nearHorizontalDist = Math.sqrt(pushAway.x * pushAway.x + pushAway.z * pushAway.z);
								if (nearHorizontalDist > 0.01) {
									pushAway = new Vec3(pushAway.x / nearHorizontalDist, 0, pushAway.z / nearHorizontalDist);
								} else {
									pushAway = new Vec3(1, 0, 0);
								}
								double nearPush = 2.2;
								double nearPushUp = 0.45;
								if (isInfinity) {
									nearPush = 5.5;
									nearPushUp = 1.0;
								}
								applyKnockbackAndSync(near, pushAway.x * nearPush, nearPushUp, pushAway.z * nearPush);
							}
						}
					}
				}
				entity.getPersistentData().putDouble("sentinel_landing_ticks", 20); // Freeze the rot for a second right after judgement dropkick impact!
			}
			handlePassengerAndGrowth(entity);
			return;
		}

		double minosTicks = entity.getPersistentData().getDouble("sentinel_minos_ticks");
		if (minosTicks > 0) {
			if (combatTarget != null) {
				lockLookAtTarget(entity, combatTarget);
			}
			entity.setDeltaMovement(entity.getDeltaMovement().x() * 0.05, entity.getDeltaMovement().y(), entity.getDeltaMovement().z() * 0.05);

			if (minosTicks == 48 || minosTicks == 36 || minosTicks == 24 || minosTicks == 12) {
				if (combatTarget instanceof LivingEntity targetLiv) {
					// 1. Teleport behind/near target
					double targetYaw = targetLiv.getYRot();
					double radians = Math.toRadians(targetYaw);
					double tx = targetLiv.getX() - Math.sin(radians) * -1.4;
					double tz = targetLiv.getZ() + Math.cos(radians) * -1.4;
					double ty = targetLiv.getY();

					if (world instanceof ServerLevel level) {
						LivingEntity ls = entity instanceof LivingEntity ? (LivingEntity) entity : null;
						level.sendParticles(ParticleTypes.PORTAL, entity.getX(), entity.getY() + 0.5, entity.getZ(), 8, 0.2, 0.2, 0.2, 0.05);
						teleportEntity(entity, tx, ty, tz);
						level.sendParticles(ParticleTypes.PORTAL, tx, ty + 0.5, tz, 8, 0.2, 0.2, 0.2, 0.05);

						playHostileSound(level, tx, ty, tz, "item.chorus_fruit.teleport", 1.2F, 1.1F);
						playHostileSound(level, tx, ty, tz, "entity.player.attack.sweep", 1.4F, (float) (0.8 + (50 - minosTicks) * 0.01));
						playHostileSound(level, tx, ty, tz, "entity.iron_golem.attack", 1.3F, 0.9F);

						level.sendParticles(ParticleTypes.SWEEP_ATTACK, targetLiv.getX(), targetLiv.getY() + 1.0, targetLiv.getZ(), 3, 0.2, 0.2, 0.2, 0.0);
						level.sendParticles(ParticleTypes.CRIT, targetLiv.getX(), targetLiv.getY() + 1.0, targetLiv.getZ(), 12, 0.3, 0.3, 0.3, 0.1);

						// Swap arms animations tick
						if (minosTicks == 48 || minosTicks == 24) {
							entity.getPersistentData().putDouble("sentinel_left_punch_ticks", 10);
						} else {
							entity.getPersistentData().putDouble("sentinel_right_punch_ticks", 10);
						}

						float punchDamage = 30.0F;
						boolean isInfinity = entity.getPersistentData().getBoolean("sentinel_is_infinity_totem");
						if (isInfinity) {
							punchDamage *= 2.5F;
							level.explode(entity, targetLiv.getX(), targetLiv.getY() + 0.5, targetLiv.getZ(), 4.0F * (float) Math.min(5.0, getAdaptationMultiplier(entity)), Level.ExplosionInteraction.MOB);
						}

						if (ls != null) {
							targetLiv.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_consecutive_punches"))), ls), punchDamage * (float) getAdaptationMultiplier(ls));
						} else {
							targetLiv.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_consecutive_punches"))), entity), punchDamage * (float) getAdaptationMultiplier(entity));
						}

						Vec3 pushVec = targetLiv.position().subtract(entity.position());
						double horizontalDist = Math.sqrt(pushVec.x * pushVec.x + pushVec.z * pushVec.z);
						if (horizontalDist < 0.1) {
							pushVec = entity.getLookAngle();
							horizontalDist = Math.sqrt(pushVec.x * pushVec.x + pushVec.z * pushVec.z);
						}
						if (horizontalDist > 0.01) {
							pushVec = new Vec3(pushVec.x / horizontalDist, 0, pushVec.z / horizontalDist);
						} else {
							pushVec = new Vec3(1, 0, 0);
						}

						double pushForce = isInfinity ? 4.8 : 2.4;
						double pushUp = isInfinity ? 0.85 : 0.45;
						targetLiv.setDeltaMovement(pushVec.x * pushForce, pushUp, pushVec.z * pushForce);
						targetLiv.hasImpulse = true;
					}
				}
			}
			handlePassengerAndGrowth(entity);
			return;
		}

		double closingTicksFirstCheck = entity.getPersistentData().getDouble("sentinel_laser_closing_ticks");
		if (closingTicksFirstCheck > 0) {
			entity.setDeltaMovement(entity.getDeltaMovement().x() * 0.05, entity.getDeltaMovement().y(), entity.getDeltaMovement().z() * 0.05);
			if (combatTarget != null) {
				lockLookAtTarget(entity, combatTarget);
			}
			handlePassengerAndGrowth(entity);
			return;
		}

		if (combatTarget == null) {
			entity.getPersistentData().putDouble(K_ADAPT_MODE, 0);
			if (entity.getPersistentData().getDouble("sentinel_solar_fire_ticks") > 0 || entity.getPersistentData().getDouble("sentinel_solar_charge_ticks") > 0
				|| entity.getPersistentData().getDouble("sentinel_cryo_fire_ticks") > 0 || entity.getPersistentData().getDouble("sentinel_cryo_charge_ticks") > 0) {
				entity.getPersistentData().putDouble("sentinel_solar_fire_ticks", 0);
				entity.getPersistentData().putDouble("sentinel_solar_charge_ticks", 0);
				entity.getPersistentData().putDouble("sentinel_cryo_fire_ticks", 0);
				entity.getPersistentData().putDouble("sentinel_cryo_charge_ticks", 0);
				entity.getPersistentData().putDouble("sentinel_laser_closing_ticks", LASER_CLOSING_TICKS);
				stopHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:fractus_laser", 64.0);
			}
			handlePassengerAndGrowth(entity);
			return;
		}

		if (combatTarget instanceof Player p && p.getAbilities().instabuild && entity.getPersistentData().getDouble(K_CREATIVE_MSG) == 0) {
			entity.getPersistentData().putDouble(K_CREATIVE_MSG, 1);
			handlePassengerAndGrowth(entity);
			return;
		}

		lockLookAtTarget(entity, combatTarget);

		double distToTarget = entity.distanceTo(combatTarget);

		boolean targetOnPillar = false;
		if (combatTarget != null && isTargetPillaring(world, combatTarget, entity)) {
			double tdx = combatTarget.getX() - entity.getX();
			double tdz = combatTarget.getZ() - entity.getZ();
			double distSqXZ = tdx * tdx + tdz * tdz;
			double maxPillarDist = ROT_PILLAR_BACK_OFF_DISTANCE + 2.0;
			if (distSqXZ < maxPillarDist * maxPillarDist) {
				targetOnPillar = true;
			}
		}

		// Keep a safe distance from Players (minimum ROT_PLAYER_BACK_OFF_DISTANCE blocks away)
		if (!targetOnPillar && combatTarget instanceof Player && distToTarget < ROT_PLAYER_BACK_OFF_DISTANCE) {
			Vec3 pushBack = entity.position().subtract(combatTarget.position());
			if (pushBack.horizontalDistanceSqr() > 0.0) {
				pushBack = new Vec3(pushBack.x, 0, pushBack.z).normalize().scale(0.12);
				entity.setDeltaMovement(pushBack.x, entity.getDeltaMovement().y, pushBack.z);
			} else {
				entity.setDeltaMovement(0.1, entity.getDeltaMovement().y, 0.1);
			}
		}

		// Drive Mob Navigation programmatically to the target to ensure elite pathfinding
		if (entity instanceof Mob mob) {
			boolean isHeavyPunchingAI = false;
			try {
				isHeavyPunchingAI = entity.getEntityData().get(RotEntity.DATA_is_heavy_left_punching) || entity.getEntityData().get(RotEntity.DATA_is_heavy_right_punching);
			} catch (Exception e) {}
			if (isHeavyPunchingAI) {
				mob.getNavigation().stop();
				entity.setDeltaMovement(entity.getDeltaMovement().x * 0.05, entity.getDeltaMovement().y, entity.getDeltaMovement().z * 0.05);
			} else if (entity.getPersistentData().getDouble("sentinel_sonic_scream_ticks") > 0) {
				mob.getNavigation().stop();
			} else {
				double pathSpeed = 1.25;
				if (entity.getPersistentData().getDouble("sentinel_solar_charge_ticks") > 0 || entity.getPersistentData().getDouble("sentinel_solar_fire_ticks") > 0
					|| entity.getPersistentData().getDouble("sentinel_cryo_charge_ticks") > 0 || entity.getPersistentData().getDouble("sentinel_cryo_fire_ticks") > 0) {
					pathSpeed = 0.1; // slow down during lasers
				}
				if (!targetOnPillar && mob.tickCount % 5 == 0) {
					if (distToTarget > ROT_PLAYER_BACK_OFF_DISTANCE + 0.4) {
						mob.getNavigation().moveTo(combatTarget, pathSpeed);
					} else {
						mob.getNavigation().stop();
					}
				}
			}
		}

		// Anti-trench cheese jumping logic
		checkTrenchAndJump(world, entity, combatTarget);

		// Warden sonic boom adaptation (Learned ability)
		double combatTicks = entity.getPersistentData().getDouble("sentinel_combat_ticks");
		boolean isWardenCombatTarget = "minecraft:warden".equals(BuiltInRegistries.ENTITY_TYPE.getKey(combatTarget.getType()).toString());
		boolean unlockedSonicBoom = entity.getPersistentData().getBoolean("unlocked_sonic_boom");
		if (!isChannelingAbility(entity) && distToTarget <= SONIC_BOOM_RANGE && distToTarget >= SONIC_BOOM_MIN_DIST && ((isWardenCombatTarget && combatTicks >= 600) || unlockedSonicBoom) && entity.getPersistentData().getDouble("sentinel_warden_sonic_cooldown") <= 0 && Math.random() < 0.05) {
			fireSuperchargedSonicBoom(world, entity, combatTarget);
			entity.getPersistentData().putDouble("sentinel_warden_sonic_cooldown", SONIC_BOOM_COOLDOWN);
			return;
		}

		// Generalized airborne / flying target slam-down state machine (Learned ability)
		boolean unlockedTP = entity.getPersistentData().getBoolean("unlocked_teleportation");
		if (!isChannelingAbility(entity) && combatTarget instanceof LivingEntity livTarget && (unlockedSonicBoom && unlockedTP)) {
			boolean isFlying = livTarget.isFallFlying() || (!livTarget.onGround() && livTarget.getY() > entity.getY() + 2.0 && !livTarget.isInWater() && !livTarget.isInLava());
			if (isFlying) {
				double flightCd = entity.getPersistentData().getDouble("sentinel_flight_intercept_cooldown");
				if (flightCd <= 0) {
					if (livTarget instanceof Player p) {
						p.stopFallFlying();
					}
					// Strictly stop their existing movements completely before slamming down!
					livTarget.setDeltaMovement(Vec3.ZERO);
					livTarget.hasImpulse = true;
					// Slam down hard!
					livTarget.setDeltaMovement(0, -2.5, 0);
					livTarget.hasImpulse = true;
					if (world instanceof ServerLevel level) {
						livTarget.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_seismic_slam"))), entity), 15.0F * (float) getAdaptationMultiplier(entity));
						double targetNewX = livTarget.getX();
						double targetNewY = livTarget.getY();
						double targetNewZ = livTarget.getZ();
						level.sendParticles(ParticleTypes.EXPLOSION, targetNewX, targetNewY, targetNewZ, 4, 0.4, 0.4, 0.4, 0.05);
						level.sendParticles(ParticleTypes.SONIC_BOOM, targetNewX, targetNewY, targetNewZ, 1, 0.1, 0.1, 0.1, 0.0);
						teleportEntity(entity, targetNewX, targetNewY + 2.0, targetNewZ);
						playHostileSound(level, targetNewX, targetNewY, targetNewZ, "entity.warden.sonic_boom", 1.2F, 0.4F);
					}
					entity.getPersistentData().putDouble("sentinel_flight_intercept_cooldown", 80);
					return;
				}
			}
		}

		// Wither Dialogue Handler
		if (isWither(combatTarget)) {
			double witherCd = entity.getPersistentData().getDouble("rot_wither_dialogue_cooldown");
			if (witherCd <= 0 && Math.random() < 0.015) {
				String quote = WITHER_QUOTES[RandomSource.create().nextInt(WITHER_QUOTES.length)];
				sendActionBarToNearbyPlayers(world, entity.position(), 32.0, quote);
				entity.getPersistentData().putDouble("rot_wither_dialogue_cooldown", 400); // 20 seconds cooldown
			}
		}

		double dist = combatTarget.position().distanceTo(entity.position());

		// 1. ACTIVE ADAPTIVE MORPHING (FIRE / CRYO) - Completely removed to keep regular biped defaults on spawn
		int activeMode = 0;
		entity.getPersistentData().putDouble(K_ADAPT_MODE, 0);

		// --- ROT_BLOCK State Machine ---
		if (ENABLE_BLOCKING && entity.getPersistentData().getBoolean("is_blocking")) {
			double activeTicks = entity.getPersistentData().getDouble("rot_block_active_ticks");
			if (activeTicks <= 0) {
				entity.getPersistentData().putBoolean("is_blocking", false);
			} else {
				if (entity instanceof Mob mob) {
					mob.getNavigation().stop();
					mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
				}
				handlePassengerAndGrowth(entity);
				return;
			}
		}

		// --- ROT_ARMOR_RIP State Machine ---
		int armorRipTicks = (int) entity.getPersistentData().getDouble("rot_armor_rip_ticks");
		if (armorRipTicks > 0) {
			entity.getPersistentData().putDouble("rot_armor_rip_ticks", armorRipTicks - 1);
			executeArmorRipChoke(world, entity, combatTarget, armorRipTicks - 1);
			// Do NOT lockLookAtTarget during armor ripping choke to prevent slow rotation feedback loop
			handlePassengerAndGrowth(entity);
			return;
		}

		// 2. EXTRACTION GRAPPLE (Power Siphon) State Machine - Learned slowly (Requires 300 combat ticks)
		int grappleTicks = (int) entity.getPersistentData().getDouble(K_GRAPPLE_TICKS);
		if (ENABLE_EXTRACTION_GRAPPLE && grappleTicks > 0) {
			entity.getPersistentData().putDouble(K_GRAPPLE_TICKS, grappleTicks - 1);
			executeGrappleSiphon(world, entity, combatTarget, grappleTicks - 1);
			lockLookAtTarget(entity, combatTarget);
			handlePassengerAndGrowth(entity);
			return;
		}

		// 3. TELEKINESIS State Machine - Learned slowly (Requires 200 combat ticks) -> No particles, lift & throw/slam!
		int tkTicks = (int) entity.getPersistentData().getDouble(K_TK_TICKS);
		if (ENABLE_TELEKINESIS && tkTicks > 0) {
			entity.getPersistentData().putDouble(K_TK_TICKS, tkTicks - 1);
			executeTelekinesis(world, entity, combatTarget, tkTicks - 1);
			lockLookAtTarget(entity, combatTarget);
			handlePassengerAndGrowth(entity);
			return;
		}

		// 4. SOLAR BEAM CHARGE & FIRE State Machine - Learned slowly (Requires 500 combat ticks)
		double solarCharge = entity.getPersistentData().getDouble("sentinel_solar_charge_ticks");
		double solarFire = entity.getPersistentData().getDouble("sentinel_solar_fire_ticks");

		if (solarFire > 0) {
			entity.getPersistentData().putDouble("sentinel_solar_fire_ticks", solarFire - 1);
			executeSentinelFaceLaserFiring(world, entity, combatTarget, (int) solarFire);
			handlePassengerAndGrowth(entity);
			if (solarFire == 1.0) {
				entity.getPersistentData().putDouble("sentinel_laser_closing_ticks", LASER_CLOSING_TICKS);
			}
			return;
		}

		if (solarCharge > 0) {
			entity.getPersistentData().putDouble("sentinel_solar_charge_ticks", solarCharge + 1);
			executeSentinelFaceLaserCharging(world, entity, combatTarget, (int) solarCharge);
			handlePassengerAndGrowth(entity);
			return;
		}

		// Cryogenic Freeze Beam state machine (copied from face laser but frost themed)
		double cryoCharge = entity.getPersistentData().getDouble("sentinel_cryo_charge_ticks");
		double cryoFire = entity.getPersistentData().getDouble("sentinel_cryo_fire_ticks");

		if (cryoFire > 0) {
			entity.getPersistentData().putDouble("sentinel_cryo_fire_ticks", cryoFire - 1);
			executeSentinelCryoLaserFiring(world, entity, combatTarget, (int) cryoFire);
			handlePassengerAndGrowth(entity);
			if (cryoFire == 1.0) {
				entity.getPersistentData().putDouble("sentinel_laser_closing_ticks", LASER_CLOSING_TICKS);
			}
			return;
		}

		if (cryoCharge > 0) {
			entity.getPersistentData().putDouble("sentinel_cryo_charge_ticks", cryoCharge + 1);
			executeSentinelCryoLaserCharging(world, entity, combatTarget, (int) cryoCharge);
			handlePassengerAndGrowth(entity);
			return;
		}

		// Perform constant adaptive effects ONLY when no active specialized channels are running
		handleAdaptiveEffects(world, entity, combatTarget, activeMode, dist);

		// 5. RANDOM SPECIAL TRIGGERS when ready (with global cooldown to slow switching and prevent overlapping)
		double globalCd = entity.getPersistentData().getDouble("sentinel_global_ability_cooldown");
		double coreCd = getDynamicGlobalCooldown(entity);

		if (globalCd <= 0) {
			// Sonic Scream check for flying targets
			if (combatTarget instanceof LivingEntity livTarget) {
				boolean isFlying = false;
				if (livTarget instanceof Player p) {
					isFlying = p.isFallFlying() || p.getAbilities().flying;
				} else {
					isFlying = livTarget instanceof net.minecraft.world.entity.animal.FlyingAnimal
						|| livTarget instanceof net.minecraft.world.entity.monster.Phantom
						|| livTarget instanceof net.minecraft.world.entity.monster.Ghast
						|| livTarget instanceof net.minecraft.world.entity.monster.Vex
						|| livTarget instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon
						|| isWither(livTarget)
						|| livTarget instanceof net.minecraft.world.entity.ambient.Bat;
				}
				boolean isEligibleScreamTarget = true;
				if (livTarget instanceof Player p) {
					boolean hasElytra = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(net.minecraft.world.item.Items.ELYTRA);
					if (!hasElytra) {
						isEligibleScreamTarget = false;
					}
				}
				if (isFlying && isEligibleScreamTarget && entity.getPersistentData().getBoolean("unlocked_sonic_scream") && entity.getPersistentData().getDouble("sentinel_sonic_scream_cooldown") <= 0.0 && dist <= 24.0 && Math.random() < 0.15) {
					entity.getPersistentData().putDouble("sentinel_sonic_scream_ticks", 240.0);
					entity.getPersistentData().putDouble("sentinel_sonic_scream_cooldown", SONIC_SCREAM_COOLDOWN);
					entity.getPersistentData().putDouble("sentinel_global_ability_cooldown", coreCd);
					return;
				}
			}

			CombatContext ctx = getCombatContext(entity, combatTarget);
			boolean totemActive = entity.getPersistentData().getBoolean("sentinel_totem_active");

			double laserScore = 0.0;
			double tkScore = 0.0;
			double screamScore = 0.0;
			double omniScore = 0.0;

			if (entity.getPersistentData().getBoolean("unlocked_sonic_boom") && entity.getPersistentData().getDouble("sentinel_omni_sonic_cooldown") <= 0) {
				if (world instanceof ServerLevel level) {
					int count = level.getEntitiesOfClass(LivingEntity.class, entity.getBoundingBox().inflate(6.0), e -> e != entity).size();
					if (count >= 3) {
						omniScore = 10.0 + count * 5.0;
						omniScore *= getMemoryPenalty(entity, "omni");
					}
				}
			}

			String combatTargetId = BuiltInRegistries.ENTITY_TYPE.getKey(combatTarget.getType()).toString();
			boolean isWardenTarget = combatTargetId.contains("warden");
			boolean isHotTarget = (combatTarget.fireImmune() && !isWardenTarget) || combatTarget.level().dimension() == net.minecraft.world.level.Level.NETHER;
			boolean unlockedSolar = entity.getPersistentData().getBoolean("unlocked_solar_beam");
			boolean unlockedCryo = entity.getPersistentData().getBoolean("unlocked_cryo_beam");

			if ((unlockedSolar || unlockedCryo) && entity.getPersistentData().getDouble(K_SOLAR_CD) <= 0) {
				laserScore = 10.0;
				if (dist > 10.0) laserScore *= 2.0;
				if (ctx.isHealing) laserScore *= 2.5;
				if (ctx.isMovingFast && dist > 8.0) laserScore *= 1.5;
				if (ctx.isAirborne) laserScore *= 0.5;
				if (ctx.isCornered) laserScore *= 0.3;
				if (dist < 5.0 && !totemActive) laserScore *= 0.1;
				laserScore *= getMemoryPenalty(entity, "laser");
				
				double currentHeat = entity.getPersistentData().getDouble("sentinel_laser_heat");
				if (currentHeat > 50.0) laserScore *= 0.1;
				else if (currentHeat > 0.0) laserScore *= (1.0 - (currentHeat / 100.0));
			}

			if (ENABLE_TELEKINESIS && !isHotTarget && entity.getPersistentData().getBoolean("unlocked_telekinesis") && entity.getPersistentData().getDouble(K_TK_CD) <= 0) {
				tkScore = 10.0;
				if (dist <= 12.0) tkScore *= 1.5;
				if (ctx.isCornered) tkScore *= 2.0;
				if (ctx.isBlocking) tkScore *= 2.0;
				if (dist > 16.0) tkScore = 0.0;
				tkScore *= getMemoryPenalty(entity, "tk");
			}

			laserScore *= (0.8 + Math.random() * 0.4);
			tkScore *= (0.8 + Math.random() * 0.4);
			screamScore *= (0.8 + Math.random() * 0.4);
			omniScore *= (0.8 + Math.random() * 0.4);

			double maxScore = Math.max(Math.max(laserScore, tkScore), Math.max(screamScore, omniScore));

			double rangedChance = totemActive ? 0.35 : 0.15;
			double _cTicks = entity.getPersistentData().getDouble("sentinel_combat_ticks");
			if (_cTicks > 3000.0) {
				rangedChance *= Math.max(0.1, 1.0 - ((_cTicks - 3000.0) / 12000.0));
			}
			if (maxScore > 5.0 && Math.random() < rangedChance) {
				if (maxScore == omniScore) {
					recordAttack(entity, "omni");
					entity.getPersistentData().putDouble("sentinel_omni_sonic_cooldown", OMNI_SONIC_BOOM_CD);
					entity.getPersistentData().putDouble("sentinel_omni_sonic_charge_ticks", OMNI_SONIC_BOOM_ANIMATION_TICKS);
					entity.getPersistentData().putDouble("sentinel_global_ability_cooldown", coreCd);
					if (world instanceof ServerLevel level) {
						playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sonic_charge", 1.8F, 0.45F);
					}
					return;
				} else if (maxScore == laserScore) {
					recordAttack(entity, "laser");
					double currentHeat = entity.getPersistentData().getDouble("sentinel_laser_heat");
					entity.getPersistentData().putDouble("sentinel_laser_heat", Math.min(200.0, currentHeat + 80.0));
					
					entity.getPersistentData().putInt("sentinel_laser_target_id", combatTarget.getId());
					entity.getPersistentData().putDouble(K_SOLAR_CD, SOLAR_CD + 180);
					entity.getPersistentData().putDouble("sentinel_global_ability_cooldown", coreCd);
					
					if (isHotTarget && unlockedCryo) {
						entity.getPersistentData().putDouble("sentinel_cryo_charge_ticks", 1);
					} else {
						java.util.List<String> availableLasers = new java.util.ArrayList<>();
						if (unlockedSolar) availableLasers.add("solar");
						if (unlockedCryo) availableLasers.add("cryo");
						String selectedLaser = availableLasers.get(entity.getRandom().nextInt(availableLasers.size()));
						if ("solar".equals(selectedLaser)) {
							entity.getPersistentData().putDouble("sentinel_solar_charge_ticks", 1);
						} else {
							entity.getPersistentData().putDouble("sentinel_cryo_charge_ticks", 1);
						}
					}
					return;
				} else if (maxScore == tkScore) {
					recordAttack(entity, "tk");
					entity.getPersistentData().putDouble(K_TK_TICKS, 25);
					entity.getPersistentData().putDouble(K_TK_CD, TK_CD);
					entity.getPersistentData().putDouble("sentinel_global_ability_cooldown", coreCd);
					playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.heartbeat", 0.8F, 0.50F);
					return;
				}
			}
		}


		// --- Rot Armor Rip Trigger Check ---
		if (ENABLE_ARMOR_RIP && combatTicks >= 400 && globalCd <= 0 && entity.getPersistentData().getDouble("rot_armor_rip_cooldown") <= 0 && dist <= ARMOR_RIP_TRIGGER_DISTANCE && combatTarget instanceof LivingEntity livingTarget) {
			if (entity instanceof Mob mob && mob.hasLineOfSight(livingTarget)) {
				boolean hasIndestructibleArmor = false;
				boolean hasAnyArmor = false;
				for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
					if (slot.isArmor()) {
						ItemStack armorStack = livingTarget.getItemBySlot(slot);
						if (!armorStack.isEmpty()) {
							hasAnyArmor = true;
							if (armorStack.getMaxDamage() <= 0 || BuiltInRegistries.ITEM.getKey(armorStack.getItem()).getNamespace().equals("projecte")) {
								hasIndestructibleArmor = true;
							}
						}
					}
				}
				if (hasAnyArmor) {
					double ripChance = hasIndestructibleArmor ? ARMOR_RIP_CHANCE_INDESTRUCTIBLE : ARMOR_RIP_CHANCE_REGULAR;
					if (Math.random() < ripChance) {
						entity.getPersistentData().putDouble("rot_armor_rip_ticks", ARMOR_RIP_TICKS); // Configurable duration of choke
						entity.getPersistentData().putBoolean("is_armor_ripping", true);
						entity.getPersistentData().putDouble("rot_choke_locked_yaw", entity.getYRot());
						entity.getPersistentData().putDouble("rot_armor_rip_cooldown", ARMOR_RIP_COOLDOWN); // Configurable cooldown
						entity.getPersistentData().putDouble("sentinel_global_ability_cooldown", coreCd);
						entity.getPersistentData().putDouble("rot_choke_hits_taken", 0);

						boolean totemActive = entity.getPersistentData().getBoolean("sentinel_totem_active");
						int minHits = totemActive ? CHOKE_TOTEM_MIN_HITS : CHOKE_MIN_HITS;
						int maxHits = totemActive ? CHOKE_TOTEM_MAX_HITS : CHOKE_MAX_HITS;
						int requiredHits = minHits + net.minecraft.util.RandomSource.create().nextInt(maxHits - minHits + 1);
						entity.getPersistentData().putDouble("rot_choke_break_hits", requiredHits);

						// Disable shield if blocking
						if (combatTarget instanceof LivingEntity livTarget && livTarget.isBlocking()) {
							if (livTarget instanceof Player player) {
								player.getCooldowns().addCooldown(net.minecraft.world.item.Items.SHIELD, 100);
								player.stopUsingItem();
								if (world instanceof ServerLevel level) {
									level.broadcastEntityEvent(player, (byte) 30);
								}
							} else {
								livTarget.stopUsingItem();
							}
						}

						// Play entry roaring/screaming sound
						playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sniff", 1.2F, 0.5F);
						return;
					}
				}
			}
		}

		if (ENABLE_EXTRACTION_GRAPPLE && globalCd <= 0 && entity.getPersistentData().getBoolean("unlocked_grapple") && entity.getPersistentData().getDouble(K_GRAPPLE_CD) <= 0 && dist <= 8.5 && Math.random() < 0.03) {
			entity.getPersistentData().putDouble(K_GRAPPLE_TICKS, 32); // 32 ticks (1.6s) siphon
			entity.getPersistentData().putDouble(K_GRAPPLE_CD, GRAPPLE_CD);
			entity.getPersistentData().putDouble("sentinel_global_ability_cooldown", coreCd);
			playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sniff", 1.1F, 0.85F);
			return;
		}

		// 6. REGULAR SENSORY COMBAT LOOPS
		tryPredictiveDodge(world, entity, combatTarget, dist);
		tryFlankTeleport(world, entity, combatTarget, dist);

		// Melee / Punch attack state machine (Redone melee system)
		double meleeWindup = entity.getPersistentData().getDouble("sentinel_melee_windup");
		double meleeCooldown = entity.getPersistentData().getDouble("sentinel_melee_cooldown");

		if (meleeWindup > 0) {
			// Currently wind-up is active, lock look on target
			lockLookAtTarget(entity, combatTarget);
			// Show custom charge-up particles around hands
			if (world instanceof ServerLevel level) {
				level.sendParticles(ParticleTypes.PORTAL, entity.getX(), entity.getY() + 1.2, entity.getZ(), 2, 0.3, 0.3, 0.3, 0.05);
			}
			if (meleeWindup == 1) {
				// Once the windup finishes, strike with punch!
				executeSentinelPunch(world, entity, combatTarget);
				entity.getPersistentData().putDouble("sentinel_melee_cooldown", 20); // 20 ticks (1.0s) of cooldown
			}
		} else if (meleeCooldown <= 0 && dist <= ((combatTarget instanceof Player) ? 2.5 : 3.2)) {
			// Trigger a new melee punch attack!
			entity.getPersistentData().putDouble("sentinel_melee_windup", 8); // 8 ticks wind-up (0.4s)
			playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "entity.player.attack.weak", 0.9F, 0.85F);
			if (world instanceof ServerLevel level) {
				level.sendParticles(ParticleTypes.CRIT, entity.getX(), entity.getY() + 1.2, entity.getZ(), 5, 0.4, 0.4, 0.4, 0.1);
			}
		}

		handleForwardCarveMining(world, entity, combatTarget);
		handlePassengerAndGrowth(entity);
	}

	private static void handleAdaptiveEffects(LevelAccessor world, Entity self, Entity target, int mode, double dist) {
		if (!(world instanceof ServerLevel level) || !(self instanceof LivingEntity ls)) return;

		if (mode == 1) { // Thermodynamic Flame Modes
			// Melt ice layers around the Sentinel
			BlockPos centerPos = BlockPos.containing(ls.position());
			for (BlockPos bp : BlockPos.betweenClosed(centerPos.offset(-2, -1, -2), centerPos.offset(2, 1, 2))) {
				BlockState st = level.getBlockState(bp);
				if (st.is(net.minecraft.world.level.block.Blocks.ICE) || st.is(net.minecraft.world.level.block.Blocks.SNOW)) {
					level.destroyBlock(bp, false);
				}
			}

			// Contact fire damage within 1.5 blocks range
			if (dist <= 1.5 && self.tickCount % 4 == 0) {
				target.setRemainingFireTicks(60);
				target.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_inferno_laser"))), self), 4.0F * (float) getAdaptationMultiplier(self));
			}

			// Clear freezing and slow debuffs
			ls.setRemainingFireTicks(0);
			ls.removeEffect(MobEffects.MOVEMENT_SLOWDOWN);

		} else if (mode == 2) { // Cryogenic Frost Modes
			// trail frozen aura clouds (reduced counts)
			level.sendParticles(ParticleTypes.SNOWFLAKE, ls.getX(), ls.getY() + 1.3, ls.getZ(), 1, 0.4, 0.5, 0.4, 0.005);

			// Radiate flash freezing to targets in 6 block radius ONLY visually if needed, but remove any movement freezing effects during melee
			if (self.tickCount % 8 == 0) {
				List<Entity> nearby = level.getEntitiesOfClass(Entity.class, new AABB(self.position(), self.position()).inflate(6.0), e -> e != self && (e instanceof LivingEntity));
				for (Entity ent : nearby) {
					if (ent instanceof Player p) {
						if (p instanceof ServerPlayer sp) {
							if (sp.gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.SURVIVAL) continue;
						} else {
							if (p.isCreative() || p.isSpectator()) continue;
						}
					}
					if (ent instanceof LivingEntity liv) {
						// Removed freezing ticks, slow effect, and freeze damage to ensure no freezing effect during melee combat
					}
				}
			}
		}
	}

	private static void executeSentinelFaceLaserCharging(LevelAccessor world, Entity entity, Entity target, int chargeTicks) {
		if (!(world instanceof ServerLevel level) || !(entity instanceof LivingEntity living)) return;

		// Halt or slow down movement completely/gradually
		entity.setDeltaMovement(entity.getDeltaMovement().x() * 0.05, entity.getDeltaMovement().y(), entity.getDeltaMovement().z() * 0.05);

		// Lock look at target perfectly
		if (target != null) {
			lockLookAtTarget(entity, target);
		}

		Vec3 facePos = living.getEyePosition(1.0F).add(0.0, LASER_Y_OFFSET, 0.0);
		net.minecraft.core.particles.ParticleOptions beamPart = getAdaptiveBeamParticle(entity);

		double progress = (double) chargeTicks / 40.0;
		double radius = 1.9 - progress * 1.6; // Starts wide, contracting inward to face!
		int particleCount = 2 + (int) (progress * 5.0);
		for (int i = 0; i < particleCount; i++) {
			double angle = entity.getRandom().nextDouble() * Math.PI * 2.0;
			double yOffset = (entity.getRandom().nextDouble() - 0.5) * radius * 0.5;
			Vec3 pPos = facePos.add(Math.cos(angle) * radius, yOffset, Math.sin(angle) * radius);
			level.sendParticles(beamPart, pPos.x, pPos.y, pPos.z, 1, 0.01, 0.01, 0.01, 0.0);
		}

		// Spark particles hovering around head center to denote extreme thermal concentration
		level.sendParticles(ParticleTypes.LAVA, facePos.x, facePos.y, facePos.z, 1, 0.15, 0.15, 0.15, 0.01);

		if (chargeTicks % 6 == 0) {
			playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "item.firecharge.use", 0.6F, 1.5F);
		}

		if (chargeTicks >= 40) {
			entity.getPersistentData().putDouble("sentinel_solar_charge_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_solar_fire_ticks", 123);
			playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:fractus_laser", 4.5F, 0.65F);
		}
	}

	private static void executeSentinelFaceLaserFiring(LevelAccessor world, Entity entity, Entity target, int fireTicks) {
		if (!(world instanceof ServerLevel level) || !(entity instanceof LivingEntity living)) return;

		// Keep entity slowed down
		entity.setDeltaMovement(entity.getDeltaMovement().x() * 0.05, entity.getDeltaMovement().y(), entity.getDeltaMovement().z() * 0.05);

		if (target == null || !target.isAlive()) {
			entity.getPersistentData().putDouble("sentinel_solar_fire_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_laser_closing_ticks", LASER_CLOSING_TICKS);
			entity.getPersistentData().putDouble(K_SOLAR_CD, SOLAR_CD + RandomSource.create().nextInt(40));
			stopHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:fractus_laser", 64.0);
			return;
		}

		// Perfect focus lock-on
		lockLookAtTarget(entity, target);

		Vec3 facePos = living.getEyePosition(1.0F).add(0.0, LASER_Y_OFFSET, 0.0);
		Vec3 targetPos = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
		Vec3 direction = targetPos.subtract(facePos);
		if (direction.lengthSqr() < 0.001) {
			direction = entity.getLookAngle();
		}
		direction = direction.normalize();

		double maxRange = 96.0;
		Vec3 beamEnd = facePos.add(direction.scale(maxRange));

		// Clip with blocks first
		BlockHitResult blockHit = level.clip(new ClipContext(facePos, beamEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
		Vec3 firstIntersectionPos = beamEnd;
		boolean hitBlock = false;
		if (blockHit.getType() != HitResult.Type.MISS) {
			firstIntersectionPos = blockHit.getLocation();
			hitBlock = true;
		}

		// Check for first entity hit along the beam range up to block collision point
		AABB searchRange = new AABB(facePos, firstIntersectionPos).inflate(1.5);
		List<Entity> possibleEntities = level.getEntitiesOfClass(Entity.class, searchRange, e -> e != entity && e != living && e.isAlive());

		Entity hitEntity = null;
		double closestDist = facePos.distanceTo(firstIntersectionPos);
		Vec3 finalBeamEnd = firstIntersectionPos;

		for (Entity possible : possibleEntities) {
			if (possible instanceof Player p && p.isCreative()) continue;
			AABB possibleBb = possible.getBoundingBox().inflate(0.3); // Tolerance
			java.util.Optional<Vec3> clipResult = possibleBb.clip(facePos, firstIntersectionPos);
			if (clipResult.isPresent()) {
				double distToClip = facePos.distanceTo(clipResult.get());
				if (distToClip < closestDist) {
					closestDist = distToClip;
					finalBeamEnd = clipResult.get();
					hitEntity = possible;
					hitBlock = false; // Entity intercepted the beam before the block!
				}
			}
		}

		beamEnd = finalBeamEnd;

		net.minecraft.core.particles.ParticleOptions beamPart = getAdaptiveBeamParticle(entity);

		// Splitting-face fiery ball / energy sphere surrounding the face (exactly like the photo)
		for (int i = 0; i < 4; i++) {
			double oX = (living.getRandom().nextDouble() - 0.5) * 0.42;
			double oY = (living.getRandom().nextDouble() - 0.5) * 0.42;
			double oZ = (living.getRandom().nextDouble() - 0.5) * 0.42;
			level.sendParticles(beamPart, facePos.x + oX, facePos.y + oY, facePos.z + oZ, 1, 0.01, 0.01, 0.01, 0.0);
		}
		if (living.getRandom().nextFloat() < 0.25F) {
			level.sendParticles(ParticleTypes.LAVA, facePos.x, facePos.y, facePos.z, 1, 0.2, 0.2, 0.2, 0.1);
		}

		double spacing = 0.15;
		Vec3 upVec = Math.abs(direction.y) > 0.92 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
		Vec3 sideVec = direction.cross(upVec).normalize();
		Vec3 vertVec = direction.cross(sideVec).normalize();
		double time = level.getGameTime() * 0.38;
		double beamLength = beamEnd.distanceTo(facePos);

		for (double d = 0.0; d <= beamLength; d += spacing) {
			Vec3 pos = facePos.add(direction.scale(d));
			
			// Main dense core particle
			level.sendParticles(beamPart, pos.x, pos.y, pos.z, 1, 0.02, 0.02, 0.02, 0.0);

			// Helical outer double-sleeve of radius 0.15 blocks
			double angle = (d * 5.0) + time;
			double cylinderRadius = 0.15;
			
			Vec3 offset1 = sideVec.scale(Math.cos(angle) * cylinderRadius).add(vertVec.scale(Math.sin(angle) * cylinderRadius));
			Vec3 p1 = pos.add(offset1);
			level.sendParticles(beamPart, p1.x, p1.y, p1.z, 1, 0.01, 0.01, 0.01, 0.0);

			Vec3 offset2 = sideVec.scale(Math.cos(angle + Math.PI) * cylinderRadius).add(vertVec.scale(Math.sin(angle + Math.PI) * cylinderRadius));
			Vec3 p2 = pos.add(offset2);
			level.sendParticles(beamPart, p2.x, p2.y, p2.z, 1, 0.01, 0.01, 0.01, 0.0);
		}

		// Block breaking & Drilling (speed varies by block hardness) ONLY if we didn't hit an entity and hit a block
		if (hitBlock && blockHit.getType() == HitResult.Type.BLOCK) {
			BlockPos hitPos = blockHit.getBlockPos();
			BlockState hitState = level.getBlockState(hitPos);
			float hardness = hitState.getDestroySpeed(level, hitPos);
			if (hardness >= 0.0F && hardness <= 50.0F) {
				int lastDrillX = entity.getPersistentData().getInt("sentinel_laser_drill_x");
				int lastDrillY = entity.getPersistentData().getInt("sentinel_laser_drill_y");
				int lastDrillZ = entity.getPersistentData().getInt("sentinel_laser_drill_z");
				double drillProgress = entity.getPersistentData().getDouble("sentinel_laser_drill_progress");

				if (lastDrillX != hitPos.getX() || lastDrillY != hitPos.getY() || lastDrillZ != hitPos.getZ()) {
					drillProgress = 0.0;
					entity.getPersistentData().putInt("sentinel_laser_drill_x", hitPos.getX());
					entity.getPersistentData().putInt("sentinel_laser_drill_y", hitPos.getY());
					entity.getPersistentData().putInt("sentinel_laser_drill_z", hitPos.getZ());
				}

				// Harder blocks take longer to break. E.g., Stone (1.5) breaks after ~4 ticks, obsidian (50) breaks after ~125 ticks
				double progressAdd = 1.0 / Math.max(1.0, hardness * 2.5);
				if (entity.getPersistentData().getBoolean("sentinel_totem_active")) {
					progressAdd *= 3.0; // 3x faster drilling!
				}
				drillProgress += progressAdd;
				entity.getPersistentData().putDouble("sentinel_laser_drill_progress", drillProgress);

				// Drilling particles
				if (level.getRandom().nextFloat() < 0.45F) {
					level.sendParticles(ParticleTypes.CRIT, hitPos.getX() + 0.5, hitPos.getY() + 0.5, hitPos.getZ() + 0.5, 4, 0.2, 0.2, 0.2, 0.05);
					level.sendParticles(ParticleTypes.LAVA, hitPos.getX() + 0.5, hitPos.getY() + 0.5, hitPos.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0.01);
				}

				if (drillProgress >= 1.0) {
					level.destroyBlock(hitPos, false);
					playHostileSound(level, hitPos.getX(), hitPos.getY(), hitPos.getZ(), "entity.item.break", 0.8F, 0.8F);

					// Crack surrounding blocks to carve a clean 3x3x4 entry tunnel
					for (int dy = -1; dy <= 2; dy++) {
						for (int dx = -1; dx <= 1; dx++) {
							for (int dz = -1; dz <= 1; dz++) {
								if (dx*dx + dy*dy + dz*dz <= 2.5) {
									BlockPos adj = hitPos.offset(dx, dy, dz);
									BlockState adjState = level.getBlockState(adj);
									float adjHard = adjState.getDestroySpeed(level, adj);
									if (adjHard >= 0.0F && adjHard <= hardness + 1.5F && !adjState.isAir()) {
										level.destroyBlock(adj, false);
									}
								}
							}
						}
					}
					entity.getPersistentData().putDouble("sentinel_laser_drill_progress", 0.0);
				}
			}

			// Laser sets fire on the hit block face if it misses the target
			BlockPos firePos = hitPos.relative(blockHit.getDirection());
			if (level.isEmptyBlock(firePos) && level.getBlockState(firePos.below()).isSolidRender(level, firePos.below())) {
				level.setBlock(firePos, net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState(), 3);
			}
		}

		// Also randomly scatter minor fires around the end of the laser beam only if missing entities
		if (hitBlock && level.getRandom().nextFloat() < 0.3F) {
			BlockPos nearBeam = BlockPos.containing(
				beamEnd.x + (level.getRandom().nextDouble() - 0.5) * 4.0,
				beamEnd.y + (level.getRandom().nextDouble() - 0.5) * 4.0,
				beamEnd.z + (level.getRandom().nextDouble() - 0.5) * 4.0
			);
			if (level.isEmptyBlock(nearBeam) && level.getBlockState(nearBeam.below()).isSolidRender(level, nearBeam.below())) {
				level.setBlock(nearBeam, net.minecraft.world.level.block.Blocks.FIRE.defaultBlockState(), 3);
			}
		}

		// Periodic explosions at end of laser
		if (fireTicks % 3 == 0) {
			level.sendParticles(ParticleTypes.EXPLOSION, beamEnd.x, beamEnd.y, beamEnd.z, 1, 0.1, 0.1, 0.1, 0.05);
		}

		// Spawn lava drip particles at the end of the laser beam (where it hits blocks/entities)
		level.sendParticles(ParticleTypes.DRIPPING_LAVA, beamEnd.x, beamEnd.y, beamEnd.z, 3, 0.1, 0.1, 0.1, 0.02);

		// Biological damage and knockback directed ONLY to target or intercepted entity (never non-targeted entities)
		if (entity.tickCount % 6 == 0) {
			Entity targetVictim = (hitEntity != null) ? hitEntity : target;
			if (targetVictim instanceof LivingEntity livVictim && livVictim.isAlive() && facePos.distanceTo(livVictim.position()) <= maxRange) {
				if (livVictim instanceof Player p) {
					if (p instanceof ServerPlayer sp) {
						if (sp.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.CREATIVE || sp.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SPECTATOR) {
							targetVictim = null;
						}
					}
				}
				if (targetVictim != null) {
					String victimId = BuiltInRegistries.ENTITY_TYPE.getKey(targetVictim.getType()).toString();
					boolean isColdEntity = victimId.contains("stray") || victimId.contains("snow_golem") || victimId.contains("snowman") || (targetVictim instanceof LivingEntity lv && lv.getTicksFrozen() > 0);
					float sonicDmg = 8.0F;
					float explosionDmg = 4.0F;
					if (isColdEntity) {
						sonicDmg = 18.0F; // Extra damage to cold target
						explosionDmg = 8.0F;
					}
					if (entity.getPersistentData().getBoolean("sentinel_totem_active")) {
						sonicDmg *= 2.0F;
						explosionDmg *= 2.0F;
					}
					targetVictim.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_inferno_laser"))), entity), (sonicDmg + explosionDmg) * (float) getAdaptationMultiplier(entity)); // Fire heat penetration
					targetVictim.setRemainingFireTicks(120);

					// Organic knockback
					Vec3 push = targetVictim.position().subtract(entity.position()).normalize();
					targetVictim.setDeltaMovement(push.x * 0.85, 0.2125, push.z * 0.85);

					// Crack and break blocks behind pushed target
					breakBlocksBehindTarget(level, targetVictim, push, entity.getPersistentData().getBoolean("sentinel_totem_active"));
				}
			}
		}

		if (fireTicks <= 1) {
			entity.getPersistentData().putDouble("sentinel_solar_fire_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_laser_closing_ticks", LASER_CLOSING_TICKS);
			entity.getPersistentData().putDouble(K_SOLAR_CD, SOLAR_CD + RandomSource.create().nextInt(40));
			stopHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:fractus_laser", 64.0);
		}
	}

	private static void executeSentinelCryoLaserCharging(LevelAccessor world, Entity entity, Entity target, int chargeTicks) {
		if (!(world instanceof ServerLevel level) || !(entity instanceof LivingEntity living)) return;

		// Halt or slow down movement completely/gradually
		entity.setDeltaMovement(entity.getDeltaMovement().x() * 0.05, entity.getDeltaMovement().y(), entity.getDeltaMovement().z() * 0.05);

		// Lock look at target perfectly
		if (target != null) {
			lockLookAtTarget(entity, target);
		}

		Vec3 facePos = living.getEyePosition(1.0F).add(0.0, LASER_Y_OFFSET, 0.0);
		double progress = (double) chargeTicks / 40.0;
		double radius = 1.9 - progress * 1.6; // Starts wide, contracting inward to face!
		int particleCount = 2 + (int) (progress * 5.0);
		for (int i = 0; i < particleCount; i++) {
			double angle = entity.getRandom().nextDouble() * Math.PI * 2.0;
			double yOffset = (entity.getRandom().nextDouble() - 0.5) * radius * 0.5;
			Vec3 pPos = facePos.add(Math.cos(angle) * radius, yOffset, Math.sin(angle) * radius);
			level.sendParticles(ParticleTypes.SNOWFLAKE, pPos.x, pPos.y, pPos.z, 1, 0.01, 0.01, 0.01, 0.0);
		}

		// Ice sparkles hovering around head center to denote extreme cold concentration
		level.sendParticles(ParticleTypes.INSTANT_EFFECT, facePos.x, facePos.y, facePos.z, 1, 0.15, 0.15, 0.15, 0.01);

		if (chargeTicks % 6 == 0) {
			playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "block.powder_snow.break", 1.2F, 0.8F);
		}

		if (chargeTicks >= 40) {
			entity.getPersistentData().putDouble("sentinel_cryo_charge_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_cryo_fire_ticks", 123);
			playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:fractus_laser", 4.5F, 1.25F);
		}
	}

	private static void executeSentinelCryoLaserFiring(LevelAccessor world, Entity entity, Entity target, int fireTicks) {
		if (!(world instanceof ServerLevel level) || !(entity instanceof LivingEntity living)) return;

		// Keep entity slowed down
		entity.setDeltaMovement(entity.getDeltaMovement().x() * 0.05, entity.getDeltaMovement().y(), entity.getDeltaMovement().z() * 0.05);

		if (target == null || !target.isAlive()) {
			entity.getPersistentData().putDouble("sentinel_cryo_fire_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_laser_closing_ticks", LASER_CLOSING_TICKS);
			entity.getPersistentData().putDouble(K_SOLAR_CD, SOLAR_CD + RandomSource.create().nextInt(40));
			stopHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:fractus_laser", 64.0);
			return;
		}

		// Perfect target lock-on
		lockLookAtTarget(entity, target);

		Vec3 facePos = living.getEyePosition(1.0F).add(0.0, LASER_Y_OFFSET, 0.0);
		Vec3 targetPos = new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ());
		Vec3 direction = targetPos.subtract(facePos);
		if (direction.lengthSqr() < 0.001) {
			direction = entity.getLookAngle();
		}
		direction = direction.normalize();

		double maxRange = 96.0;
		Vec3 beamEnd = facePos.add(direction.scale(maxRange));

		// Clip with blocks first
		BlockHitResult blockHit = level.clip(new ClipContext(facePos, beamEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
		Vec3 firstIntersectionPos = beamEnd;
		boolean hitBlock = false;
		if (blockHit.getType() != HitResult.Type.MISS) {
			firstIntersectionPos = blockHit.getLocation();
			hitBlock = true;
		}

		// Check for first entity hit along the beam range up to block collision point
		AABB searchRange = new AABB(facePos, firstIntersectionPos).inflate(1.5);
		List<Entity> possibleEntities = level.getEntitiesOfClass(Entity.class, searchRange, e -> e != entity && e != living && e.isAlive());

		Entity hitEntity = null;
		double closestDist = facePos.distanceTo(firstIntersectionPos);
		Vec3 finalBeamEnd = firstIntersectionPos;

		for (Entity possible : possibleEntities) {
			if (possible instanceof Player p && p.isCreative()) continue;
			AABB possibleBb = possible.getBoundingBox().inflate(0.3); // Tolerance
			java.util.Optional<Vec3> clipResult = possibleBb.clip(facePos, firstIntersectionPos);
			if (clipResult.isPresent()) {
				double distToClip = facePos.distanceTo(clipResult.get());
				if (distToClip < closestDist) {
					closestDist = distToClip;
					finalBeamEnd = clipResult.get();
					hitEntity = possible;
					hitBlock = false; // Entity intercepted the beam before the block!
				}
			}
		}

		beamEnd = finalBeamEnd;

		// Frost/Cryo ball surrounding the face
		for (int i = 0; i < 4; i++) {
			double oX = (living.getRandom().nextDouble() - 0.5) * 0.42;
			double oY = (living.getRandom().nextDouble() - 0.5) * 0.42;
			double oZ = (living.getRandom().nextDouble() - 0.5) * 0.42;
			level.sendParticles(ParticleTypes.SNOWFLAKE, facePos.x + oX, facePos.y + oY, facePos.z + oZ, 1, 0.01, 0.01, 0.01, 0.0);
		}
		if (living.getRandom().nextFloat() < 0.25F) {
			level.sendParticles(ParticleTypes.INSTANT_EFFECT, facePos.x, facePos.y, facePos.z, 2, 0.2, 0.2, 0.2, 0.1);
		}

		double spacing = 0.15;
		Vec3 upVec = Math.abs(direction.y) > 0.92 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
		Vec3 sideVec = direction.cross(upVec).normalize();
		Vec3 vertVec = direction.cross(sideVec).normalize();
		double time = level.getGameTime() * 0.38;
		double beamLength = beamEnd.distanceTo(facePos);

		for (double d = 0.0; d <= beamLength; d += spacing) {
			Vec3 pos = facePos.add(direction.scale(d));
			
			// Cryo dense beam core
			level.sendParticles(ParticleTypes.SNOWFLAKE, pos.x, pos.y, pos.z, 1, 0.02, 0.02, 0.02, 0.0);

			// Cryo outer double helix
			double angle = (d * 5.0) + time;
			double cylinderRadius = 0.15;
			
			Vec3 offset1 = sideVec.scale(Math.cos(angle) * cylinderRadius).add(vertVec.scale(Math.sin(angle) * cylinderRadius));
			Vec3 p1 = pos.add(offset1);
			level.sendParticles(ParticleTypes.INSTANT_EFFECT, p1.x, p1.y, p1.z, 1, 0.01, 0.01, 0.01, 0.0);

			Vec3 offset2 = sideVec.scale(Math.cos(angle + Math.PI) * cylinderRadius).add(vertVec.scale(Math.sin(angle + Math.PI) * cylinderRadius));
			Vec3 p2 = pos.add(offset2);
			level.sendParticles(ParticleTypes.INSTANT_EFFECT, p2.x, p2.y, p2.z, 1, 0.01, 0.01, 0.01, 0.0);
		}

		// Block breaking & Drilling (Cryo-fracturing variant - converts water to ice, fractures hard blocks) ONLY if we missed entities
		if (hitBlock && blockHit.getType() == HitResult.Type.BLOCK) {
			BlockPos hitPos = blockHit.getBlockPos();
			BlockState hitState = level.getBlockState(hitPos);
			float hardness = hitState.getDestroySpeed(level, hitPos);
			if (hardness >= 0.0F && hardness <= 50.0F) {
				int lastDrillX = entity.getPersistentData().getInt("sentinel_laser_drill_x");
				int lastDrillY = entity.getPersistentData().getInt("sentinel_laser_drill_y");
				int lastDrillZ = entity.getPersistentData().getInt("sentinel_laser_drill_z");
				double drillProgress = entity.getPersistentData().getDouble("sentinel_laser_drill_progress");

				if (lastDrillX != hitPos.getX() || lastDrillY != hitPos.getY() || lastDrillZ != hitPos.getZ()) {
					drillProgress = 0.0;
					entity.getPersistentData().putInt("sentinel_laser_drill_x", hitPos.getX());
					entity.getPersistentData().putInt("sentinel_laser_drill_y", hitPos.getY());
					entity.getPersistentData().putInt("sentinel_laser_drill_z", hitPos.getZ());
				}

				double progressAdd = 1.0 / Math.max(1.0, hardness * 2.5);
				if (entity.getPersistentData().getBoolean("sentinel_totem_active")) {
					progressAdd *= 3.0; // 3x faster drilling!
				}
				drillProgress += progressAdd;
				entity.getPersistentData().putDouble("sentinel_laser_drill_progress", drillProgress);

				// Frost fractures particles
				if (level.getRandom().nextFloat() < 0.45F) {
					level.sendParticles(ParticleTypes.SNOWFLAKE, hitPos.getX() + 0.5, hitPos.getY() + 0.5, hitPos.getZ() + 0.5, 4, 0.2, 0.2, 0.2, 0.05);
					level.sendParticles(ParticleTypes.INSTANT_EFFECT, hitPos.getX() + 0.5, hitPos.getY() + 0.5, hitPos.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0.01);
				}

				if (drillProgress >= 1.0) {
					level.destroyBlock(hitPos, false);
					playHostileSound(level, hitPos.getX(), hitPos.getY(), hitPos.getZ(), "block.glass.break", 1.0F, 1.2F); // High cold ice fracture sound!

					// Frost crack surrounding blocks
					for (int dy = -1; dy <= 2; dy++) {
						for (int dx = -1; dx <= 1; dx++) {
							for (int dz = -1; dz <= 1; dz++) {
								if (dx*dx + dy*dy + dz*dz <= 2.5) {
									BlockPos adj = hitPos.offset(dx, dy, dz);
									BlockState adjState = level.getBlockState(adj);
									float adjHard = adjState.getDestroySpeed(level, adj);
									if (adjHard >= 0.0F && adjHard <= hardness + 1.5F && !adjState.isAir()) {
										level.destroyBlock(adj, false);
									}
								}
							}
						}
					}
					entity.getPersistentData().putDouble("sentinel_laser_drill_progress", 0.0);
				}
			}

			// Cryo beam puts snow/ice or freezes blocks around target if it misses
			BlockPos surfacePos = hitPos.relative(blockHit.getDirection());
			if (level.isEmptyBlock(surfacePos) && level.getBlockState(surfacePos.below()).isSolidRender(level, surfacePos.below())) {
				level.setBlock(surfacePos, net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState(), 3);
			}
		}

		// Also randomly scatter ice/snow layers where beam lands only if missing entities
		if (hitBlock && level.getRandom().nextFloat() < 0.3F) {
			BlockPos nearBeam = BlockPos.containing(
				beamEnd.x + (level.getRandom().nextDouble() - 0.5) * 4.0,
				beamEnd.y + (level.getRandom().nextDouble() - 0.5) * 4.0,
				beamEnd.z + (level.getRandom().nextDouble() - 0.5) * 4.0
			);
			if (level.isEmptyBlock(nearBeam) && level.getBlockState(nearBeam.below()).isSolidRender(level, nearBeam.below())) {
				level.setBlock(nearBeam, net.minecraft.world.level.block.Blocks.SNOW.defaultBlockState(), 3);
			}
		}

		// Periodic ice burst effect at end of cryogenic beam
		if (fireTicks % 3 == 0) {
			level.sendParticles(ParticleTypes.SNOWFLAKE, beamEnd.x, beamEnd.y, beamEnd.z, 3, 0.15, 0.15, 0.15, 0.02);
		}

		// Biological flash freezing and knockback directed ONLY to target or intercepted entity (never non-targeted entities)
		if (entity.tickCount % 6 == 0) {
			Entity targetVictim = (hitEntity != null) ? hitEntity : target;
			if (targetVictim instanceof LivingEntity livVictim && livVictim.isAlive() && facePos.distanceTo(livVictim.position()) <= maxRange) {
				if (livVictim instanceof Player p) {
					if (p instanceof ServerPlayer sp) {
						if (sp.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.CREATIVE || sp.gameMode.getGameModeForPlayer() == net.minecraft.world.level.GameType.SPECTATOR) {
							targetVictim = null;
						}
					}
				}
				if (targetVictim != null) {
					float dmg = 8.0F;
					String victimId = BuiltInRegistries.ENTITY_TYPE.getKey(targetVictim.getType()).toString();
					boolean isNetherEntity = (targetVictim.fireImmune() && !victimId.contains("warden")) || targetVictim.level().dimension() == net.minecraft.world.level.Level.NETHER || victimId.contains("piglin") || victimId.contains("wither_skeleton");
					if (isNetherEntity) {
						dmg = 18.0F; // Extra damage to Nether targets
					}
					if (entity.getPersistentData().getBoolean("sentinel_totem_active")) {
						dmg *= 2.0F;
					}
					targetVictim.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_cryo_laser"))), entity), dmg * (float) getAdaptationMultiplier(entity)); // Extreme cryo/freeze dmg
					((LivingEntity) targetVictim).setTicksFrozen(((LivingEntity) targetVictim).getTicksFrozen() + 180); // Freeze target completely!
					((LivingEntity) targetVictim).addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 120, 4, false, false)); // Complete freeze/slowness!
					playHostileSound(level, targetVictim.getX(), targetVictim.getY(), targetVictim.getZ(), "entity.player.hurt_freeze", 1.2F, 0.8F);

					// Small frost knockback
					Vec3 push = targetVictim.position().subtract(entity.position()).normalize();
					targetVictim.setDeltaMovement(push.x * 0.34, 0.085, push.z * 0.34);

					// Crack and break blocks behind pushed target
					breakBlocksBehindTarget(level, targetVictim, push, entity.getPersistentData().getBoolean("sentinel_totem_active"));
				}
			}
		}

		if (fireTicks <= 1) {
			entity.getPersistentData().putDouble("sentinel_cryo_fire_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_laser_closing_ticks", LASER_CLOSING_TICKS);
			entity.getPersistentData().putDouble(K_SOLAR_CD, SOLAR_CD + RandomSource.create().nextInt(40));
			stopHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:fractus_laser", 64.0);
		}
	}

	private static void executeSentinelSonicScream(LevelAccessor world, Entity entity, Entity target, int ticksLeft) {
		if (!(world instanceof ServerLevel level) || !(entity instanceof LivingEntity living)) return;

		// Halt movement of the Rot completely
		entity.setDeltaMovement(0.0, entity.getDeltaMovement().y(), 0.0);
		if (entity instanceof Mob mob) {
			mob.getNavigation().stop();
		}

		// Lock look target
		if (target != null && target.isAlive()) {
			lockLookAtTarget(entity, target);
		}

		// Play sound covering the 9-second active scream duration (active between 20 and 200 ticks left)
		if (ticksLeft == 200) {
			playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:sonic_scream", 4.0F, 1.0F);
		}

		// Every tick during the active scream phase (between tick 20 and 200)
		if (ticksLeft > 20 && ticksLeft <= 200) {
			// Find all entities and players in a 24-block range
			List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, entity.getBoundingBox().inflate(24.0), e -> e != entity);
			for (LivingEntity victim : targets) {
				// Severe disorientation and immobilization (stun, no damage, no particles)
				victim.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 100, 4, false, false));
				victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 9, false, false));

				// Check if the target is a Player
				if (victim instanceof Player player) {
					// Scramble controls/keybinds: rotate camera slightly and apply random movement forces
					player.setYRot(player.getYRot() + (float)(entity.getRandom().nextDouble() - 0.5) * 15.0F);
					player.setXRot(player.getXRot() + (float)(entity.getRandom().nextDouble() - 0.5) * 10.0F);
					
					Vec3 motion = player.getDeltaMovement();
					player.setDeltaMovement(motion.x() + (entity.getRandom().nextDouble() - 0.5) * 0.1, motion.y(), motion.z() + (entity.getRandom().nextDouble() - 0.5) * 0.1);
					player.hurtMarked = true;

					// If player is flying (via creative/modded or elytra), force them down like dead birds
					if (player.getAbilities().flying || player.isFallFlying()) {
						player.getAbilities().flying = false;
						player.stopFallFlying();
						player.onUpdateAbilities();
						player.setDeltaMovement(player.getDeltaMovement().x(), -0.6, player.getDeltaMovement().z());
						player.hurtMarked = true;
					}
				} else {
					// Disrupt Mob AI: clear target, reset path, shake/stun movement
					victim.setDeltaMovement(victim.getDeltaMovement().multiply(0.0, 1.0, 0.0).add((entity.getRandom().nextDouble() - 0.5) * 0.05, 0.0, (entity.getRandom().nextDouble() - 0.5) * 0.05));
					if (victim instanceof Mob mob) {
						mob.setTarget(null);
						mob.getNavigation().stop();
						mob.setYRot(mob.getYRot() + (float)(entity.getRandom().nextDouble() - 0.5) * 20.0F);
					}

					// If mob is flying (like Hindenburg, Phantoms, Vexes, Ghasts), force them down like dead birds to counter them
					boolean targetIsFlying = victim.isFallFlying() || (!victim.onGround() && victim.getY() > entity.getY() + 2.0 && !victim.isInWater() && !victim.isInLava());
					if (targetIsFlying) {
						victim.setDeltaMovement(0.0, -0.6, 0.0);
						victim.hurtMarked = true;
					}
				}
			}
		}

		if (ticksLeft <= 1) {
			entity.getPersistentData().putDouble("sentinel_sonic_scream_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_sonic_scream_cooldown", SONIC_SCREAM_COOLDOWN); // strictly 1 min cooldown
			entity.getPersistentData().putDouble("sentinel_global_ability_cooldown", getDynamicGlobalCooldown(entity));
		}
	}

	private static void checkLearnedMilestone(LivingEntity self, double combatTicks, boolean inCombat) {
		if (!inCombat) return;
		net.minecraft.nbt.CompoundTag data = self.getPersistentData();

		Entity target = (self instanceof Mob mob) ? mob.getTarget() : null;
		if (target != null) {
			String targetId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString().toLowerCase(java.util.Locale.ROOT);
			boolean isColdEntity = targetId.contains("stray") || targetId.contains("snow") || targetId.contains("ice") || targetId.contains("polar") || targetId.contains("frost") || targetId.contains("freeze");
			if (isColdEntity) {
				data.putBoolean("fought_cold_entity", true);
			}
			boolean isNetherEntity = targetId.contains("wither") || targetId.contains("ghast") || targetId.contains("piglin") || targetId.contains("blaze") || targetId.contains("magma") || targetId.contains("hoglin") || targetId.contains("strider") || targetId.contains("skeleton");
			if (isNetherEntity || target.level().dimension() == net.minecraft.world.level.Level.NETHER) {
				data.putBoolean("fought_nether_entity", true);
			}
			boolean isWardenEntity = targetId.contains("warden");
			if (isWardenEntity) {
				double wardenTicks = data.getDouble("sentinel_warden_combat_ticks") + 1.0;
				data.putDouble("sentinel_warden_combat_ticks", wardenTicks);
				if (wardenTicks >= WARDEN_LEARN_REQUIRED_TICKS) {
					data.putBoolean("fought_warden_entity", true);
				}
			}
		}

		/*
		 * =========================================================================
		 *                     ROT ADAPTIVE ABILITIES UNLOCK GUIDE
		 * =========================================================================
		 * 
		 * The Rot mutant entity dynamically learns and unlocks its abilities 
		 * based on real-time combat experiences and environmental interactions:
		 * 
		 * 1. Wolverine-Style Cellular Regeneration (unlocked_regen)
		 *    - Unlocked when cumulative damage taken reaches or exceeds 50.0 units.
		 * 
		 * 2. Force Telekinesis (unlocked_telekinesis)
		 *    - Unlocked automatically during any combat once active combat duration 
		 *      reaches 400 ticks (approx. 20 seconds).
		 * 
		 * 3. Extraction Grapple (unlocked_grapple)
		 *    - Unlocked over time in combat. Active combat duration must reach a 
		 *      randomly rolled threshold between 200 and 500 ticks.
		 * 
		 * 4. Sonic Boom (unlocked_sonic_boom)
		 *    - Unlocked strictly upon engaging in combat with a Warden ("fought_warden_entity").
		 * 
		 * 5. Adaptive Solar Beam Face Laser (unlocked_solar_beam)
		 *    - Unlocked by fighting a Nether entity, spending >= 600 ticks in the Nether, 
		 *      or taking any fire/lava/magma damage.
		 * 
		 * 6. Adaptive Cryo Beam Face Laser (unlocked_cryo_beam)
		 *    - Unlocked strictly upon engaging in combat with a cold-themed entity ("fought_cold_entity").
		 * 
		 * 7. Nanite Teleport Dodging (unlocked_teleportation)
		 *    - Unlocked when End-related exploration or learning progress reaches >= 160.0.
		 * 
		 * 8. Overhead Slam Combo (unlocked_overhead_combo)
		 *    - Unlocked over time in active combat when combat ticks reach a randomized 
		 *      threshold between 200 and 500 ticks.
		 * 
		 * 9. Judgment Dropkick Combo (unlocked_dropkick_combo)
		 *    - Unlocked over time in active combat when combat ticks reach a randomized 
		 *      threshold between 200 and 500 ticks.
		 * 
		 * =========================================================================
		 */
		double totalDamageTaken = data.getDouble("sentinel_total_damage_taken");

		// Wolverine-style Adaptive Cellular Regeneration (ONLY learned from continuous damage, e.g. totalDamageTaken >= 50.0)
		if (totalDamageTaken >= 50.0 && !data.getBoolean("unlocked_regen")) {
			data.putBoolean("unlocked_regen", true);
			announceLearnedAbility(self);
		}

		// Force Telekinesis (learned in any combat overtime)
		if (ENABLE_TELEKINESIS && combatTicks >= 400 && !data.getBoolean("unlocked_telekinesis")) {
			data.putBoolean("unlocked_telekinesis", true);
			announceLearnedAbility(self);
		}
		// Extraction Grapple (learned in any combat overtime)
		double reqGrappleTicks = data.getDouble("sentinel_required_grapple_ticks");
		if (reqGrappleTicks <= 0) {
			reqGrappleTicks = 200.0 + self.getRandom().nextDouble() * 300.0;
			data.putDouble("sentinel_required_grapple_ticks", reqGrappleTicks);
		}
		if (ENABLE_EXTRACTION_GRAPPLE && combatTicks >= reqGrappleTicks && !data.getBoolean("unlocked_grapple")) {
			data.putBoolean("unlocked_grapple", true);
			announceLearnedAbility(self);
		}
		// Sonic Boom (ONLY learned from fighting warden)
		if (!data.getBoolean("unlocked_sonic_boom")) {
			boolean foughtWarden = data.getBoolean("fought_warden_entity");
			if (foughtWarden) {
				data.putBoolean("unlocked_sonic_boom", true);
				announceLearnedAbility(self);
			}
		}
		// Sonic Scream (ONLY learned from fighting/targeting a flying enemy for some time, e.g. 160 ticks cumulative)
		if (!data.getBoolean("unlocked_sonic_scream")) {
			double flyingTicks = data.getDouble("sentinel_flying_target_ticks");
			if (flyingTicks >= 160.0) {
				data.putBoolean("unlocked_sonic_scream", true);
				announceLearnedAbility(self);
			}
		}
		// Adaptive Solar Beam Face Laser (ONLY learned from being in nether after some time, fighting nether mobs, or taking fire/lava/magma damage)
		if (!data.getBoolean("unlocked_solar_beam")) {
			boolean foughtNether = data.getBoolean("fought_nether_entity");
			boolean inNetherLong = data.getDouble("sentinel_time_in_nether") >= 600.0;
			boolean tookFireDmg = data.getBoolean("taken_fire_damage");
			if (foughtNether || inNetherLong || tookFireDmg) {
				data.putBoolean("unlocked_solar_beam", true);
				announceLearnedAbility(self);
			}
		}
		// Adaptive Cryo Beam Face Laser (ONLY learned from fighting cold mobs)
		if (!data.getBoolean("unlocked_cryo_beam")) {
			boolean foughtCold = data.getBoolean("fought_cold_entity");
			if (foughtCold) {
				data.putBoolean("unlocked_cryo_beam", true);
				announceLearnedAbility(self);
			}
		}
		// Nanite Teleport Dodging (ONLY learned from being in end dimension after some time, or fighting any end entities)
		double prog = data.getDouble("sentinel_teleport_learning_progress");
		if (prog >= 160.0 && !data.getBoolean("unlocked_teleportation")) {
			data.putBoolean("unlocked_teleportation", true);
			announceLearnedAbility(self);
		}

		// Additional learning condition: Dropkick and Overhead combos (learned in any combat overtime)
		double reqOverheadTicks = data.getDouble("sentinel_required_overhead_ticks");
		if (reqOverheadTicks <= 0) {
			reqOverheadTicks = 200.0 + self.getRandom().nextDouble() * 300.0; // 10-25 seconds
			data.putDouble("sentinel_required_overhead_ticks", reqOverheadTicks);
		}
		if (combatTicks >= reqOverheadTicks && !data.getBoolean("unlocked_overhead_combo")) {
			data.putBoolean("unlocked_overhead_combo", true);
			announceLearnedAbility(self);
		}

		double reqDropkickTicks = data.getDouble("sentinel_required_dropkick_ticks");
		if (reqDropkickTicks <= 0) {
			reqDropkickTicks = 200.0 + self.getRandom().nextDouble() * 300.0; // 10-25 seconds
			data.putDouble("sentinel_required_dropkick_ticks", reqDropkickTicks);
		}
		if (combatTicks >= reqDropkickTicks && !data.getBoolean("unlocked_dropkick_combo")) {
			data.putBoolean("unlocked_dropkick_combo", true);
			announceLearnedAbility(self);
		}

		double reqMinosTicks = data.getDouble("sentinel_required_minos_ticks");
		if (reqMinosTicks <= 0) {
			reqMinosTicks = 400.0 + self.getRandom().nextDouble() * 400.0; // 20-40 seconds of combat
			data.putDouble("sentinel_required_minos_ticks", reqMinosTicks);
		}
		if (combatTicks >= reqMinosTicks && !data.getBoolean("unlocked_minos_combo")) {
			data.putBoolean("unlocked_minos_combo", true);
			announceLearnedAbility(self);
		}

		// Three New Custom Combos (learned over time in active combat after 20-30 seconds)
		double reqCombo1Ticks = data.getDouble("sentinel_required_cc1_ticks_learn");
		if (reqCombo1Ticks <= 0) {
			reqCombo1Ticks = 400.0 + self.getRandom().nextDouble() * 200.0; // 20-30 seconds (400 to 600 ticks)
			data.putDouble("sentinel_required_cc1_ticks_learn", reqCombo1Ticks);
		}
		if (combatTicks >= reqCombo1Ticks && !data.getBoolean("unlocked_triple_threat_combo")) {
			data.putBoolean("unlocked_triple_threat_combo", true);
			announceLearnedAbility(self);
		}

		double reqCombo2Ticks = data.getDouble("sentinel_required_cc2_ticks_learn");
		if (reqCombo2Ticks <= 0) {
			reqCombo2Ticks = 400.0 + self.getRandom().nextDouble() * 200.0; // 20-30 seconds
			data.putDouble("sentinel_required_cc2_ticks_learn", reqCombo2Ticks);
		}
		if (combatTicks >= reqCombo2Ticks && !data.getBoolean("unlocked_high_sky_slam_combo")) {
			data.putBoolean("unlocked_high_sky_slam_combo", true);
			announceLearnedAbility(self);
		}

		double reqCombo3Ticks = data.getDouble("sentinel_required_cc3_ticks_learn");
		if (reqCombo3Ticks <= 0) {
			reqCombo3Ticks = 400.0 + self.getRandom().nextDouble() * 200.0; // 20-30 seconds
			data.putDouble("sentinel_required_cc3_ticks_learn", reqCombo3Ticks);
		}
		if (combatTicks >= reqCombo3Ticks && !data.getBoolean("unlocked_knockback_dropkick_combo")) {
			data.putBoolean("unlocked_knockback_dropkick_combo", true);
			announceLearnedAbility(self);
		}

		double reqCombo4Ticks = data.getDouble("sentinel_required_cc4_ticks_learn");
		if (reqCombo4Ticks <= 0) {
			reqCombo4Ticks = 600.0 + self.getRandom().nextDouble() * 300.0; // 30-45 seconds
			data.putDouble("sentinel_required_cc4_ticks_learn", reqCombo4Ticks);
		}
		if (combatTicks >= reqCombo4Ticks && !data.getBoolean("unlocked_knockback_rider_combo")) {
			data.putBoolean("unlocked_knockback_rider_combo", true);
			announceLearnedAbility(self);
		}

		double reqCombo5Ticks = data.getDouble("sentinel_required_cc5_ticks_learn");
		if (reqCombo5Ticks <= 0) {
			reqCombo5Ticks = 650.0 + self.getRandom().nextDouble() * 250.0; // 30-45 seconds
			data.putDouble("sentinel_required_cc5_ticks_learn", reqCombo5Ticks);
		}
		if (combatTicks >= reqCombo5Ticks && !data.getBoolean("unlocked_heavenly_repentance_plus")) {
			data.putBoolean("unlocked_heavenly_repentance_plus", true);
			announceLearnedAbility(self);
		}
	}

	private static void announceLearnedAbility(LivingEntity self) {
		playHostileSound(self.level(), self.getX(), self.getY(), self.getZ(), "entity.warden.heartbeat", 1.2F, 1.4F);
	}

	private static void executeGrappleSiphon(LevelAccessor world, Entity self, Entity target, int ticksLeft) {
		if (!(world instanceof ServerLevel level) || !(self instanceof LivingEntity rawSelf)) return;

		// Forcibly pull mutant/player targets into siphon range
		Vec3 vectorToSelf = self.position().add(0, 1.0, 0).subtract(target.position()).normalize();
		double distance = target.position().distanceTo(self.position());

		if (distance > 2.0) {
			// Magnetic vacuum pull (reduced particles)
			target.setDeltaMovement(vectorToSelf.x * 0.82, 0.15, vectorToSelf.z * 0.82);
			level.sendParticles(ParticleTypes.PORTAL, target.getX(), target.getY() + 1.0, target.getZ(), 1, 0.2, 0.2, 0.2, 0.1);
		} else {
			// Fast extraction grapple!
			target.setDeltaMovement(0, -0.05, 0); // Pin down completely
			if (target instanceof LivingEntity liv) {
				liv.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 30, 4, false, false));
				liv.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 1, false, false));
			}

			if (ticksLeft % 4 == 0) {
				target.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_extraction_grapple"))), self), 4.0F * (float) getAdaptationMultiplier(self)); // Direct biological drain
				rawSelf.setHealth(Math.min(rawSelf.getMaxHealth(), rawSelf.getHealth() + 4.5F));

				level.sendParticles(ParticleTypes.SWEEP_ATTACK, self.getX(), self.getY() + 1.2, self.getZ(), 1, 0.3, 0.3, 0.3, 0.0);
				level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY() + 1.0, target.getZ(), 1, 0.1, 0.1, 0.1, 0.1);

				// Quieter, muted audio feedback for mechanical siphon
				playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.warden.attack_impact", 0.7F, 0.85F);
				if (self instanceof LivingEntity ls) ls.swing(InteractionHand.MAIN_HAND, true);
			}
		}
	}

	private static void executeTelekinesis(LevelAccessor world, Entity self, Entity target, int ticksLeft) {
		if (!(world instanceof ServerLevel level) || target == null) return;

		// Suspend target in midair (no particles or visual clutter as requested)
		Vec3 hoverPos = self.position().add(0, 3.5, 0);
		Vec3 diff = hoverPos.subtract(target.position());
		double liftStrength = 0.22;
		target.setDeltaMovement(diff.x * liftStrength, 0.15, diff.z * liftStrength);
		target.hasImpulse = true;

		if (target instanceof LivingEntity liv) {
			liv.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 10, 3, false, false));
		}

		// Throw or slam target on complete
		if (ticksLeft <= 0) {
			if (Math.random() < 0.5) {
				// Strictly stop their existing movements completely before slamming down!
				target.setDeltaMovement(Vec3.ZERO);
				target.hasImpulse = true;
				// Slam down forcefully
				target.setDeltaMovement(0, -2.4, 0);
				target.hasImpulse = true;
				target.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_telekinesis"))), self), 12.0F * (float) getAdaptationMultiplier(self));
				playHostileSound(level, target.getX(), target.getY(), target.getZ(), "entity.iron_golem.damage", 1.0F, 0.65F);
			} else {
				// Slam/throw horizontally and up
				Vec3 throwDir = target.position().subtract(self.position()).normalize();
				double ty = Math.max(throwDir.y, 0.2);
				target.setDeltaMovement(throwDir.x * 2.0, ty * 1.5 + 0.3, throwDir.z * 2.0);
				target.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_telekinesis"))), self), 8.0F * (float) getAdaptationMultiplier(self));
				playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.player.attack.sweep", 1.0F, 0.6F);
			}
		}
	}

	private static void executeSentinelPunch(LevelAccessor world, Entity self, Entity target) {
		if (!(world instanceof ServerLevel level)) return;
		LivingEntity ls = (self instanceof LivingEntity) ? (LivingEntity) self : null;

		// Alternate hand swings - always use MAIN_HAND since OFF_HAND swings do not render for custom mob models
		if (ls != null) {
			boolean lastHandLeft = self.getPersistentData().getBoolean("sentinel_punch_hand_toggle");
			ls.swing(InteractionHand.MAIN_HAND, true);
			self.getPersistentData().putBoolean("sentinel_punch_hand_toggle", !lastHandLeft);

			if (lastHandLeft) {
				self.getPersistentData().putDouble("sentinel_left_punch_ticks", 18);
				self.getPersistentData().putDouble("sentinel_right_punch_ticks", 0);
			} else {
				self.getPersistentData().putDouble("sentinel_right_punch_ticks", 18);
				self.getPersistentData().putDouble("sentinel_left_punch_ticks", 0);
			}
		}

		// Initial base damage
		double finalDist = self.distanceTo(target);
		double maxReach = (target instanceof Player) ? 2.5 : 3.2;
		if (finalDist > maxReach) {
			// Target backed up / escaped the punch!
			return;
		}

		// Tweak: Punch misses sometimes when target is moving, only if confirmed in the middle of continuous melee combat
		double combatTicks = self.getPersistentData().getDouble("sentinel_combat_ticks");
		boolean inContinuousMelee = (combatTicks > 30.0 && finalDist <= 3.0);
		boolean isMoving = (target.getDeltaMovement().horizontalDistance() > 0.08);

		if (inContinuousMelee && isMoving && Math.random() < 0.25) {
			// Punch misses! Show a subtle whoosh effect and return early.
			playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.player.attack.sweep", 1.2F, 1.4F);
			level.sendParticles(ParticleTypes.CLOUD, target.getX(), target.getY() + 1.0, target.getZ(), 3, 0.2, 0.2, 0.2, 0.02);
			return;
		}

		double basePunchDmg = MELEE_PUNCH_DAMAGE;

		// Select a high-impact combat combo if the target is a LivingEntity and abilities are unlocked!
		boolean unlockedSonicBoom = self.getPersistentData().getBoolean("unlocked_sonic_boom");
		boolean unlockedSolar = self.getPersistentData().getBoolean("unlocked_solar_beam");
		boolean unlockedCryo = self.getPersistentData().getBoolean("unlocked_cryo_beam");
		boolean unlockedGrapple = ENABLE_EXTRACTION_GRAPPLE && self.getPersistentData().getBoolean("unlocked_grapple");
		boolean unlockedTK = ENABLE_TELEKINESIS && self.getPersistentData().getBoolean("unlocked_telekinesis");
		boolean unlockedTP = self.getPersistentData().getBoolean("unlocked_teleportation");
		boolean unlockedOverhead = self.getPersistentData().getBoolean("unlocked_overhead_combo");
		boolean unlockedDropkick = self.getPersistentData().getBoolean("unlocked_dropkick_combo");
		boolean unlockedMinos = self.getPersistentData().getBoolean("unlocked_minos_combo");

		int selectCombo = 0; // 0 = none, 1 = airborne, 2 = grapple-siphon, 3 = tk-slam, 4 = nanite-tp-blitz, 5 = seismic-slam, 6 = thermal-shock, 7 = blink-storm
		double globalCd = self.getPersistentData().getDouble("sentinel_global_ability_cooldown");
		if (globalCd <= 0 && target instanceof LivingEntity && Math.random() < (self.getPersistentData().getBoolean("sentinel_totem_active") ? 0.85 : 0.28)) { //combo chance
			java.util.List<Integer> availableCombos = new java.util.ArrayList<>();
			if (unlockedSonicBoom || unlockedSolar || unlockedCryo) availableCombos.add(1);
			if (unlockedGrapple) availableCombos.add(2);
			if (unlockedTK) availableCombos.add(3);
			if (unlockedTP) availableCombos.add(4);
			if (unlockedTK) availableCombos.add(5);
			if (unlockedSolar || unlockedCryo) availableCombos.add(6);
			if (unlockedTP) availableCombos.add(7);
			if (unlockedTP && unlockedOverhead) availableCombos.add(8);
			if (unlockedTP) availableCombos.add(9);
			if (unlockedTP && unlockedDropkick && self.distanceTo(target) > 6.0) availableCombos.add(10);
			if (unlockedTP) availableCombos.add(11);
			if (unlockedTK) availableCombos.add(12);
			if (unlockedDropkick && self.distanceTo(target) > 6.0) availableCombos.add(13);
			if (unlockedMinos) availableCombos.add(14);

			// Unocked Custom Combos
			if (self.getPersistentData().getBoolean("unlocked_triple_threat_combo") && self.getPersistentData().getDouble("sentinel_cc1_cd") <= 0) availableCombos.add(101);
			if (self.getPersistentData().getBoolean("sentinel_totem_active") && self.getPersistentData().getBoolean("unlocked_high_sky_slam_combo") && self.getPersistentData().getDouble("sentinel_cc2_cd") <= 0) availableCombos.add(102);
			if (self.getPersistentData().getBoolean("unlocked_knockback_dropkick_combo") && self.getPersistentData().getDouble("sentinel_cc3_cd") <= 0) availableCombos.add(103);
			if (self.getPersistentData().getBoolean("unlocked_knockback_rider_combo") && self.getPersistentData().getDouble("sentinel_cc4_cd") <= 0) availableCombos.add(104);
			if (self.getPersistentData().getBoolean("unlocked_heavenly_repentance_plus") && self.getPersistentData().getDouble("sentinel_cc5_cd") <= 0) availableCombos.add(105);

			if (!availableCombos.isEmpty()) {
				CombatContext ctx = getCombatContext(self, target);
				boolean favorHeavy = target.getPersistentData().getBoolean("bw_threat_high_armor");
				if (favorHeavy && Math.random() < 0.6) {
					java.util.List<Integer> heavyCombos = new java.util.ArrayList<>();
					for (int c : availableCombos) {
						if (c == 5 || c == 8 || c == 12 || c == 13 || c == 14 || c == 102) heavyCombos.add(c);
					}
					if (!heavyCombos.isEmpty()) {
						selectCombo = evaluateComboUtility(self, target, ctx, heavyCombos);
					} else {
						selectCombo = evaluateComboUtility(self, target, ctx, availableCombos);
					}
				} else {
					selectCombo = evaluateComboUtility(self, target, ctx, availableCombos);
				}
				double coreCd = getDynamicGlobalCooldown(self);
				self.getPersistentData().putDouble("sentinel_global_ability_cooldown", coreCd);

				
				if (selectCombo == 101) {
					self.getPersistentData().putDouble("sentinel_cc1_stage", 1);
					self.getPersistentData().putDouble("sentinel_cc1_ticks", 20); // 1s gap
					self.getPersistentData().putDouble("sentinel_combo_active_ticks", 120);
				} else if (selectCombo == 102) {
					self.getPersistentData().putDouble("sentinel_cc2_stage", 1);
					self.getPersistentData().putDouble("sentinel_combo_active_ticks", 180);
				} else if (selectCombo == 103) {
					self.getPersistentData().putDouble("sentinel_cc3_stage", 1);
					self.getPersistentData().putDouble("sentinel_combo_active_ticks", 120);
				} else if (selectCombo == 104) {
					self.getPersistentData().putDouble("sentinel_cc4_stage", 1);
					self.getPersistentData().putDouble("sentinel_combo_active_ticks", 120);
				} else if (selectCombo == 105) {
					self.getPersistentData().putDouble("sentinel_cc5_stage", 1);
					self.getPersistentData().putDouble("sentinel_combo_active_ticks", 180);
				} else {
					self.getPersistentData().putDouble("sentinel_combo_active_ticks", 40);
				}

				if (selectCombo >= 101) {
					return;
				}
			}
		}

		// Check target attributes/properties
		boolean targetHasArmor = false;
		boolean targetHasMace = false;
		double targetDmg = 2.0;

		if (target instanceof LivingEntity targetLiving) {
			if (targetLiving.getArmorValue() > 0) {
				targetHasArmor = true;
			}
			var mainHand = targetLiving.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND);
			var offHand = targetLiving.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND);
			String mainHandName = BuiltInRegistries.ITEM.getKey(mainHand.getItem()).toString();
			String offHandName = BuiltInRegistries.ITEM.getKey(offHand.getItem()).toString();
			if (mainHandName.contains("mace") || offHandName.contains("mace")) {
				targetHasMace = true;
			}

			var attr = targetLiving.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
			if (attr != null) {
				targetDmg = attr.getValue();
			}
		}

		// Adaptation 2: Copy High Damage eventually
		double adaptedPunchDmg = self.getPersistentData().getDouble("adapted_punch_damage");
		if (adaptedPunchDmg < basePunchDmg) {
			adaptedPunchDmg = basePunchDmg;
		}

		if (targetDmg > adaptedPunchDmg) {
			// Adapt/copy target damage eventually
			adaptedPunchDmg += (targetDmg - adaptedPunchDmg) * 0.45;
			self.getPersistentData().putDouble("adapted_punch_damage", adaptedPunchDmg);
			// Spawn subtle adaptation enchantment particles
			level.sendParticles(ParticleTypes.ENCHANT, self.getX(), self.getY() + 1.2, self.getZ(), 6, 0.3, 0.3, 0.3, 0.1);
		}

		double finalDamage = adaptedPunchDmg;

		// Adaptation 1: Mace / Armor-Piercing crusher
		if (targetHasMace && target instanceof LivingEntity targetLiving) {
			// Deals dynamic armor damage: significantly degrade armor slots
			for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
				if (slot.isArmor()) {
					var stack = targetLiving.getItemBySlot(slot);
					if (!stack.isEmpty()) {
						stack.setDamageValue(Math.min(stack.getMaxDamage(), stack.getDamageValue() + 25));
					}
				}
			}
			// Deal extra raw piercing damage based on target's armor!
			finalDamage += targetLiving.getArmorValue() * 0.65;
		}

		// Apply Damage
		if (ls != null) {
			target.hurt(ls.damageSources().mobAttack(ls), (float) finalDamage * (float) getAdaptationMultiplier(ls));
		} else {
			target.hurt(new DamageSource(level.holderOrThrow(DamageTypes.MOB_ATTACK)), (float) finalDamage * (float) getAdaptationMultiplier(self));
		}

		// Effects and Knockback
		Vec3 sweepPush = target.position().subtract(self.position()).normalize();
		double knockbackStrength = 1.05;

		// Adaptation 2: Proportional more knockback based on adapted/copied damage
		if (adaptedPunchDmg > basePunchDmg) {
			knockbackStrength += (adaptedPunchDmg - basePunchDmg) * 0.045;
		}

		// Apply Knockback or specialized combos!
		if (selectCombo == 1 && target instanceof LivingEntity liv) {
			// Launch target high into the air! (Airborne Launch Combo)
			liv.setDeltaMovement(sweepPush.x * 0.3, 1.35, sweepPush.z * 0.3);
			liv.hasImpulse = true;

			// Spawn gusty wind launch particles
			level.sendParticles(ParticleTypes.CLOUD, target.getX(), target.getY() + 0.5, target.getZ(), 8, 0.2, 0.2, 0.2, 0.05);
			playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.wind_charge.throw", 1.2F, 0.75F);

			// Trigger immediate follow-up attack combo (Laser or Sonic Boom)
			double rng = Math.random();
			if (rng < 0.45 && unlockedSonicBoom) {
				self.getPersistentData().putDouble("sentinel_sonic_ticks", SONIC_BOOM_ANIMATION_TICKS);
				self.getPersistentData().putDouble("sentinel_warden_sonic_cooldown", SONIC_BOOM_COOLDOWN);
				playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.warden.sonic_charge", 1.4F, 0.4F);
			} else if (unlockedSolar || unlockedCryo) {
				java.util.List<String> lasers = new java.util.ArrayList<>();
				if (unlockedSolar) lasers.add("solar");
				if (unlockedCryo) lasers.add("cryo");
				if (!lasers.isEmpty()) {
					String chosen = lasers.get((int) (Math.random() * lasers.size()));
					if ("solar".equals(chosen)) {
						self.getPersistentData().putDouble("sentinel_solar_charge_ticks", 1);
					} else {
						self.getPersistentData().putDouble("sentinel_cryo_charge_ticks", 1);
					}
				}
			}
		} else if (selectCombo == 2 && target instanceof LivingEntity liv) {
			// Siphon combo! Drag close and initiate grapple
			self.getPersistentData().putDouble(K_GRAPPLE_CD, 20); // quick reset
			self.getPersistentData().putDouble(K_GRAPPLE_TICKS, 32); // 32 ticks of grapple
			Vec3 vectorToSelf = self.position().subtract(target.position()).normalize();
			liv.setDeltaMovement(vectorToSelf.x * 1.25, 0.22, vectorToSelf.z * 1.25); // Pull target close!
			liv.hasImpulse = true;
			playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.warden.attack_impact", 1.2F, 0.85F);
			level.sendParticles(ParticleTypes.PORTAL, target.getX(), target.getY() + 1.0, target.getZ(), 10, 0.2, 0.2, 0.2, 0.1);
		} else if (selectCombo == 3 && target instanceof LivingEntity liv) {
			// Telekinesis combo! Float target instantly into midair suspension
			self.getPersistentData().putDouble(K_TK_CD, 20); // quick reset
			self.getPersistentData().putDouble(K_TK_TICKS, 25); // 25 ticks of telekinesis suspension
			liv.setDeltaMovement(0.0, 0.85, 0.0);
			liv.hasImpulse = true;
			playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.warden.sonic_boom", 0.9F, 1.4F);
			level.sendParticles(ParticleTypes.PORTAL, target.getX(), target.getY() + 1.2, target.getZ(), 12, 0.3, 0.3, 0.3, 0.05);
		} else if (selectCombo == 4 && target instanceof LivingEntity liv) {
			// Elite Nanite blink blitz! Teleport behind target and strike with 1.4x heavier impact thrust
			double angle = target.getYRot() * (Math.PI / 180.0);
			double spawnX = target.getX() - Math.sin(angle) * 1.5;
			double spawnZ = target.getZ() + Math.cos(angle) * 1.5;
			double spawnY = target.getY();

			level.sendParticles(ParticleTypes.PORTAL, self.getX(), self.getY() + 1.1, self.getZ(), 8, 0.2, 0.2, 0.2, 0.05);
			teleportEntity(self, spawnX, spawnY, spawnZ);
			level.sendParticles(ParticleTypes.PORTAL, spawnX, spawnY + 1.1, spawnZ, 8, 0.2, 0.2, 0.2, 0.05);
			playHostileSound(level, spawnX, spawnY, spawnZ, "item.chorus_fruit.teleport", 1.1F, 0.95F);

			// Heavier strike damage & backward push
			liv.hurt(ls != null ? ls.damageSources().mobAttack(ls) : new DamageSource(level.holderOrThrow(DamageTypes.MOB_ATTACK)), (float) (finalDamage * 1.4) * (float) getAdaptationMultiplier(ls));
			liv.setDeltaMovement(liv.getDeltaMovement().add(self.getViewVector(1.0F).scale(1.8)));
			liv.hasImpulse = true;
			playHostileSound(level, target.getX(), target.getY(), target.getZ(), "entity.player.attack.sweep", 1.2F, 0.65F);
			level.sendParticles(ParticleTypes.SWEEP_ATTACK, self.getX(), self.getY() + 1.2, self.getZ(), 1, 0.2, 0.2, 0.2, 0.0);
		} else if (selectCombo == 5 && target instanceof LivingEntity liv) {
			// Combo 5: Seismic Ground Slam Combo (requires unlockedTK)
			liv.setDeltaMovement(0.0, 0.45, 0.0);
			liv.hasImpulse = true;
			liv.addEffect(new MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 40, 4, false, false));
			liv.hurt(ls != null ? ls.damageSources().mobAttack(ls) : new DamageSource(level.holderOrThrow(DamageTypes.MOB_ATTACK)), (float) (finalDamage * 1.5) * (float) getAdaptationMultiplier(ls));
			liv.setDeltaMovement(sweepPush.x * 0.2, -1.8, sweepPush.z * 0.2);
			liv.hasImpulse = true;

			playHostileSound(level, target.getX(), target.getY(), target.getZ(), "entity.iron_golem.damage", 1.4F, 0.5F);
			playHostileSound(level, target.getX(), target.getY(), target.getZ(), "entity.iron_golem.death", 0.9F, 0.6F);
			level.sendParticles(ParticleTypes.EXPLOSION, target.getX(), target.getY(), target.getZ(), 5, 0.5, 0.1, 0.5, 0.1);
			level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(), target.getZ(), 10, 0.4, 0.4, 0.4, 0.2);
		} else if (selectCombo == 6 && target instanceof LivingEntity liv) {
			// Combo 6: Thermal-Cryo Shock Combo (requires unlockedSolar or unlockedCryo)
			liv.setRemainingFireTicks(80);
			liv.addEffect(new MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, 60, 2, false, false));
			liv.hurt(ls != null ? ls.damageSources().mobAttack(ls) : new DamageSource(level.holderOrThrow(DamageTypes.MOB_ATTACK)), (float) (finalDamage * 1.35) * (float) getAdaptationMultiplier(ls));

			level.sendParticles(ParticleTypes.FLAME, target.getX(), target.getY() + 1.0, target.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
			level.sendParticles(ParticleTypes.SNOWFLAKE, target.getX(), target.getY() + 1.0, target.getZ(), 8, 0.3, 0.3, 0.3, 0.05);
			playHostileSound(level, target.getX(), target.getY(), target.getZ(), "item.firecharge.use", 1.2F, 0.8F);
			playHostileSound(level, target.getX(), target.getY(), target.getZ(), "block.powder_snow.break", 1.2F, 1.1F);
			playHostileSound(level, target.getX(), target.getY(), target.getZ(), "entity.generic.explode", 0.8F, 1.4F);
		} else if (selectCombo == 7 && target instanceof LivingEntity liv) {
			// Combo 7: Chrono Blink Storm Sequence (requires unlockedTP)
			for (int i = 0; i < 3; i++) {
				double oX = (Math.random() - 0.5) * 3.0;
				double oZ = (Math.random() - 0.5) * 3.0;
				level.sendParticles(ParticleTypes.PORTAL, target.getX() + oX, target.getY() + 1.0, target.getZ() + oZ, 4, 0.1, 0.1, 0.1, 0.05);
			}
			playHostileSound(level, target.getX(), target.getY(), target.getZ(), "item.chorus_fruit.teleport", 1.3F, 1.2F);
			playHostileSound(level, target.getX(), target.getY(), target.getZ(), "entity.player.attack.sweep", 1.4F, 1.5F);

			liv.hurt(ls != null ? ls.damageSources().mobAttack(ls) : new DamageSource(level.holderOrThrow(DamageTypes.MOB_ATTACK)), (float) (finalDamage * 1.6) * (float) getAdaptationMultiplier(ls));
			liv.setDeltaMovement(sweepPush.x * 1.6, 0.45, sweepPush.z * 1.6);
			liv.hasImpulse = true;

			level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + 1.0, target.getZ(), 2, 0.3, 0.3, 0.3, 0.0);
			level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.0, target.getZ(), 8, 0.3, 0.3, 0.3, 0.08);
		} else if (selectCombo == 8 && target instanceof LivingEntity liv) {
			self.getPersistentData().putDouble("rot_overhead_ticks", OVERHEAD_TOTAL_TICKS);
			self.getPersistentData().putString("overhead_target_uuid", target.getUUID().toString());
		} else if (selectCombo == 9 && target instanceof LivingEntity liv) {
			for (int hit = 1; hit <= 3; hit++) {
				double targetYRot = target.getYRot() * (Math.PI / 180.0);
				double tx = target.getX() - Math.sin(targetYRot) * 1.6;
				double tz = target.getZ() + Math.cos(targetYRot) * 1.6;
				double ty = target.getY();
				level.sendParticles(ParticleTypes.PORTAL, self.getX(), self.getY() + 0.5, self.getZ(), 6, 0.2, 0.2, 0.2, 0.05);
				teleportEntity(self, tx, ty, tz);
				level.sendParticles(ParticleTypes.PORTAL, tx, ty + 0.5, tz, 6, 0.2, 0.2, 0.2, 0.05);
				playHostileSound(level, tx, ty, tz, "item.chorus_fruit.teleport", 1.1F, 1.0F + hit * 0.1F);
				playHostileSound(level, tx, ty, tz, "entity.player.attack.knockback", 1.3F, 0.7F + hit * 0.1F);
				target.hurt(ls != null ? ls.damageSources().mobAttack(ls) : new DamageSource(level.holderOrThrow(DamageTypes.MOB_ATTACK)), (float) (finalDamage * 1.2) * (float) getAdaptationMultiplier(ls));
				Vec3 pushVec = target.position().subtract(self.position()).normalize();
				target.setDeltaMovement(pushVec.x * 1.9, 0.35, pushVec.z * 1.9);
				target.hasImpulse = true;
			}
		} else if (selectCombo == 10 && target instanceof LivingEntity liv) {
			self.setDeltaMovement(0.0, 0.0, 0.0);
			self.hasImpulse = true;
			playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.warden.sonic_charge", 1.5F, 0.5F);
			self.getPersistentData().putDouble("sentinel_judgment_ticks", 60);
		} else if (selectCombo == 11 && target instanceof LivingEntity liv) {
			double startX = self.getX();
			double startY = self.getY();
			double startZ = self.getZ();

			double targetYRot = liv.getYRot() * (Math.PI / 180.0);
			double tx = liv.getX() - Math.sin(targetYRot) * 1.2;
			double tz = liv.getZ() + Math.cos(targetYRot) * 1.2;
			double ty = liv.getY();

			// 1. Break block obstructions above before jumping
			BlockPos selfPos = self.blockPosition();
			for (int h = 1; h <= 10; h++) {
				BlockPos above = selfPos.above(h);
				BlockState aboveState = level.getBlockState(above);
				float hardness = aboveState.getDestroySpeed(level, above);
				if (!aboveState.isAir() && hardness >= 0 && hardness < 40f) {
					level.destroyBlock(above, false);
				}
			}

			// 2. Small start shockwave
			level.sendParticles(ParticleTypes.EXPLOSION, self.getX(), self.getY() + 0.5, self.getZ(), 8, 1.0, 0.2, 1.0, 0.1);
			playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.generic.explode", 1.2F, 1.2F);
			playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.warden.sonic_boom", 0.8F, 1.4F);

			// 3. Jump high and fast instead of teleporting
			Vec3 jumpVec = target.position().subtract(self.position());
			double dx = jumpVec.x;
			double dz = jumpVec.z;
			double distXZ = Math.sqrt(dx * dx + dz * dz);
			if (distXZ < 0.1) distXZ = 0.1;
			double forwardMultiplier = 2.4; // Very fast horizontal launch
			double upwardVelocity = 2.0;    // Very high jump
			self.setDeltaMovement(dx / distXZ * forwardMultiplier, upwardVelocity, dz / distXZ * forwardMultiplier);
			self.hasImpulse = true;

			Vec3 start = new Vec3(startX, startY + self.getBbHeight() * 0.5, startZ);
			Vec3 end = new Vec3(tx, ty + liv.getBbHeight() * 0.5, tz);
			double distance = start.distanceTo(end);

			int steps = (int) Math.ceil(distance * 1.5);
			for (int i = 0; i <= steps; i++) {
				double pct = (double) i / steps;
				double px = startX + (tx - startX) * pct;
				double py = startY + (ty - startY) * pct;
				double pz = startZ + (tz - startZ) * pct;

				BlockPos centerPos = BlockPos.containing(px, py + 1.0, pz);
				for (BlockPos bp : BlockPos.betweenClosed(centerPos.offset(-1, -1, -1), centerPos.offset(1, 2, 1))) {
					BlockState state = level.getBlockState(bp);
					float hardness = state.getDestroySpeed(level, bp);
					if (!state.isAir() && hardness >= 0 && hardness < 40f) {
						level.destroyBlock(bp, false);
					}
				}

				if (i % 2 == 0) {
					level.sendParticles(ParticleTypes.EXPLOSION, px, py + 1.0, pz, 1, 0.2, 0.2, 0.2, 0.0);
				}
				level.sendParticles(ParticleTypes.CRIT, px, py + 1.0, pz, 3, 0.1, 0.1, 0.1, 0.1);
			}

			playHostileSound(level, tx, ty, tz, "entity.generic.explode", 1.4F, 1.1F);
			playHostileSound(level, tx, ty, tz, "entity.warden.attack_impact", 1.6F, 0.6F);

			liv.hurt(ls != null ? ls.damageSources().mobAttack(ls) : new DamageSource(level.holderOrThrow(DamageTypes.MOB_ATTACK)), (float) (finalDamage * 2.2) * (float) getAdaptationMultiplier(ls));
			liv.setDeltaMovement(sweepPush.x * 3.5, 0.95, sweepPush.z * 3.5);
			liv.hasImpulse = true;
		} else if (selectCombo == 12 && target instanceof LivingEntity liv) {
			// UPPERCUT + SLAM combo!
			// 1. Launch target into the air (Uppercut!)
			liv.setDeltaMovement(0.0, 1.45, 0.0);
			liv.hasImpulse = true;
			playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
			level.sendParticles(ParticleTypes.CLOUD, target.getX(), target.getY() + 0.5, target.getZ(), 10, 0.2, 0.2, 0.2, 0.05);

			// 2. Launch ourselves higher in the air (Uppercut rise!) - equivalents to jump boost 10 rise!
			self.setDeltaMovement(0.0, 1.9, 0.0);
			self.hasImpulse = true;

			// 3. Initiate the Custom Ground Slam phase machine!
			self.getPersistentData().putDouble("sentinel_slam_phase", 1);
			self.getPersistentData().putDouble("sentinel_slam_ticks", 22); // RISE timeline
		} else if (selectCombo == 13 && target instanceof LivingEntity liv) {
			// DIE! RIDER KICK move!
			// 1. Launch ourselves straight high into the air (Rise phase!)
			self.setDeltaMovement(0.0, 1.95, 0.0);
			self.hasImpulse = true;

			// 2. Initiate the Custom Die Rider Kick phase machine!
			self.getPersistentData().putDouble("sentinel_die_kick_phase", 1);
			self.getPersistentData().putDouble("sentinel_die_kick_ticks", 22); // RISE timeline (roughly 1 second rise)
			playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
		} else if (selectCombo == 14 && target instanceof LivingEntity liv) {
			// MINOS PRIME COMBO Initializer
			self.getPersistentData().putDouble("sentinel_minos_ticks", 50);
		} else {
			// Normal knockback
			double kx = sweepPush.x * knockbackStrength;
			double kz = sweepPush.z * knockbackStrength;
			double ky = 0.26;
			if (!(target instanceof Player)) {
				kx *= 1.6;
				kz *= 1.6;
				ky *= 1.2;
			}
			target.setDeltaMovement(kx, ky, kz);
			target.hasImpulse = true;
		}

		// Adaptation 3: Thermal Igniting Punch (if target isn't already heavily burning)
		if (selectCombo != 1 && target instanceof LivingEntity liv && !liv.isOnFire() && Math.random() < 0.4) {
			liv.setRemainingFireTicks(100);
			// Plays the fire sound upon contact (perfect selection & timing)
			playHostileSound(level, target.getX(), target.getY(), target.getZ(), "item.firecharge.use", 1.1F, 0.9F);
			level.sendParticles(ParticleTypes.FLAME, target.getX(), target.getY() + 1.0, target.getZ(), 5, 0.2, 0.2, 0.2, 0.02);
		} else {
			// Play appropriate contact/mace sounds based on target armor and adaptations
			if (targetHasArmor) {
				// Only use mace sounds if target is wearing armor. Also pitch down the mace sounds.
				playHostileSound(level, target.getX(), target.getY(), target.getZ(), "item.mace.heavy_smash", 1.2F, 0.65F);
			} else if (adaptedPunchDmg > basePunchDmg) {
				// Use mace kickback/impact sounds for high-yielding kinetic punch adaptations, normal pitch
				playHostileSound(level, target.getX(), target.getY(), target.getZ(), "item.mace.knockback", 1.1F, 0.95F);
			} else {
				// Normal standard punch contact sound
				playHostileSound(level, target.getX(), target.getY(), target.getZ(), "entity.iron_golem.damage", 1.0F, 0.85F);
			}
		}

		// Subtle impact particles on every hit
		level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 1.2, target.getZ(), 8, 0.2, 0.2, 0.2, 0.1);
		level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + 1.0, target.getZ(), 1, 0.1, 0.1, 0.1, 0.0);
		level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY() + 1.0, target.getZ(), 3, 0.1, 0.1, 0.1, 0.02);
	}

	private static boolean isWither(Entity entity) {
		if (entity == null) return false;
		String key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
		return "minecraft:wither".equals(key);
	}

	private static void sendActionBarToNearbyPlayers(LevelAccessor world, Vec3 pos, double range, String text) {
		if (world instanceof ServerLevel s) {
			s.players().stream()
				.filter(p -> p.position().distanceToSqr(pos) <= range * range)
				.forEach(p -> p.displayClientMessage(Component.literal(text), true));
		}
	}

	private static boolean isTargetPillaring(LevelAccessor level, Entity target, Entity self) {
		if (target == null || self == null) return false;
		if (target.getY() <= self.getY() + 2.2) return false;
		net.minecraft.core.BlockPos targetPos = target.blockPosition();
		int targetY = targetPos.getY() - 1;
		int selfY = self.blockPosition().getY();
		int startY = targetY;
		int endY = Math.max(selfY + 1, targetY - 5);
		if (startY < endY) return false;
		int totalSolidCount = 0;
		int layersChecked = 0;
		for (int y = startY; y >= endY; y--) {
			int layerSolidCount = 0;
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					net.minecraft.core.BlockPos p = targetPos.offset(dx, y - targetPos.getY(), dz);
					if (level.getBlockState(p).isCollisionShapeFullBlock(level, p)) {
						layerSolidCount++;
					}
				}
			}
			totalSolidCount += layerSolidCount;
			layersChecked++;
		}
		if (layersChecked == 0) return false;
		double avgSolid = (double) totalSolidCount / layersChecked;
		return avgSolid < 4.5;
	}

	private static void lockLookAtTarget(Entity entity, Entity target) {
		if (entity instanceof Mob mob) {
			boolean isChanneling = isChannelingAbility(entity);

			if (isChanneling) {
				mob.getLookControl().setLookAt(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), 45.0F, 45.0F);

				double dx = target.getX() - mob.getX();
				double dy = (target.getY() + target.getBbHeight() * 0.5) - mob.getEyeY();
				double dz = target.getZ() - mob.getZ();
				double dh = Math.sqrt(dx * dx + dz * dz);
				float targetYRot = (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90F;
				float targetXRot = (float) (-(Mth.atan2(dy, dh) * (180F / Math.PI)));

				float curY = mob.getYRot();
				float curX = mob.getXRot();
				float lerpedYRot = Mth.rotLerp(0.32F, curY, targetYRot);
				float lerpedXRot = Mth.rotLerp(0.32F, curX, targetXRot);

				mob.setYRot(lerpedYRot);
				mob.setXRot(lerpedXRot);
				mob.setYHeadRot(lerpedYRot);
				mob.yBodyRot = lerpedYRot;
			} else {
				mob.getLookControl().setLookAt(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(), 60.0F, 60.0F);
				double dx = target.getX() - mob.getX();
				double dz = target.getZ() - mob.getZ();
				if (dx * dx + dz * dz > 0.001) {
					float targetYRot = (float) (Mth.atan2(dz, dx) * (180F / Math.PI)) - 90F;
					float currentYRot = mob.getYRot();
					float lerpedYRot = Mth.rotLerp(0.3F, currentYRot, targetYRot);
					mob.setYRot(lerpedYRot);
					mob.setYHeadRot(lerpedYRot);
					mob.yBodyRot = lerpedYRot;
				}
			}
		} else {
			entity.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ()));
		}
	}

	private static Entity acquireTarget(LevelAccessor world, Entity self, double x, double y, double z) {
		double cc1 = self.getPersistentData().getDouble("sentinel_cc1_stage");
		double cc2 = self.getPersistentData().getDouble("sentinel_cc2_stage");
		double cc3 = self.getPersistentData().getDouble("sentinel_cc3_stage");
		double cc4 = self.getPersistentData().getDouble("sentinel_cc4_stage");
		double cc5 = self.getPersistentData().getDouble("sentinel_cc5_stage");
		double slamPhase = self.getPersistentData().getDouble("sentinel_slam_phase");
		double judgmentTicks = self.getPersistentData().getDouble("sentinel_judgment_ticks");
		double dieKickPhase = self.getPersistentData().getDouble("sentinel_die_kick_phase");

		if (cc1 > 0 || cc2 > 0 || cc3 > 0 || cc4 > 0 || cc5 > 0 || slamPhase > 0 || judgmentTicks > 0 || dieKickPhase > 0) {
			int storedId = self.getPersistentData().getInt("sentinel_combo_target_id");
			if (storedId != 0 && world instanceof ServerLevel level) {
				Entity comboTarget = level.getEntity(storedId);
				if (comboTarget instanceof LivingEntity && comboTarget.isAlive()) {
					if (self instanceof Mob mob) {
						mob.setTarget((LivingEntity) comboTarget);
					}
					return comboTarget;
				}
			}
		}

		if (self instanceof Mob mob && mob.getTarget() != null && mob.getTarget().isAlive()) {
			LivingEntity currentTarget = mob.getTarget();
			if (shouldIgnoreCombatFilter(self) || shouldIgnoreCombatFilter(currentTarget)) {
				return currentTarget;
			}
			if (!isValidTarget(currentTarget, self, true)) {
				mob.setTarget(null);
				return null;
			}
		}
		double solarFire = self.getPersistentData().getDouble("sentinel_solar_fire_ticks");
		double solarCharge = self.getPersistentData().getDouble("sentinel_solar_charge_ticks");
		double cryoFire = self.getPersistentData().getDouble("sentinel_cryo_fire_ticks");
		double cryoCharge = self.getPersistentData().getDouble("sentinel_cryo_charge_ticks");
		boolean ignoreLOS = solarFire > 0 || solarCharge > 0 || cryoFire > 0 || cryoCharge > 0
			|| self.getPersistentData().getDouble("sentinel_cc1_stage") > 0
			|| self.getPersistentData().getDouble("sentinel_cc2_stage") > 0
			|| self.getPersistentData().getDouble("sentinel_cc3_stage") > 0
			|| self.getPersistentData().getDouble("sentinel_cc4_stage") > 0
			|| self.getPersistentData().getDouble("sentinel_cc5_stage") > 0;

		if (self instanceof Mob mob && ignoreLOS) {
			int targetId = self.getPersistentData().getInt("sentinel_laser_target_id");
			if (world instanceof ServerLevel level) {
				Entity laserTarget = level.getEntity(targetId);
				if (laserTarget instanceof LivingEntity && laserTarget.isAlive() && isValidTarget(laserTarget, self, true)) {
					mob.setTarget((LivingEntity) laserTarget);
					return laserTarget;
				}
			}
		}

		// Prevent body and head twitching/jittering when surrounded by multiple hostiles by locking target focus
		if (world instanceof ServerLevel level && self instanceof Mob mob) {
			int lockedId = self.getPersistentData().getInt("sentinel_locked_target_id");
			int lockTicks = self.getPersistentData().getInt("sentinel_target_lock_ticks");
			if (lockTicks > 0) {
				Entity lockedTarget = level.getEntity(lockedId);
				if (lockedTarget instanceof LivingEntity && lockedTarget.isAlive() && isValidTarget(lockedTarget, self, ignoreLOS)) {
					self.getPersistentData().putInt("sentinel_target_lock_ticks", lockTicks - 1);
					mob.setTarget((LivingEntity) lockedTarget);
					return lockedTarget;
				}
			}
		}

		Entity target = (self instanceof Mob mob) ? mob.getTarget() : null;
		boolean targetOccupied = target instanceof LivingEntity l && isTargetOccupied(world, self, l);
		if (!isValidTarget(target, self, ignoreLOS || target != null) || targetOccupied) {
			Entity alternative = findEntityInWorldRange(world, LivingEntity.class, x, y, z, TARGET_RANGE, self);
			if (alternative != null) {
				target = alternative;
			} else if (targetOccupied) {
				if (!isValidTarget(target, self, ignoreLOS)) target = null;
			} else {
				target = null;
			}
		}

		if (target != null && self instanceof Mob mob) {
			self.getPersistentData().putInt("sentinel_locked_target_id", target.getId());
			self.getPersistentData().putInt("sentinel_target_lock_ticks", 60); // lock target focus for 3 seconds
			mob.setTarget((LivingEntity) target);
		} else if (target == null && self instanceof Mob mob) {
			mob.setTarget(null);
		}
		return target;
	}

	private static boolean isTargetOccupied(LevelAccessor world, Entity self, LivingEntity target) {
		if (target == null) return false;
		AABB searchBox = self.getBoundingBox().inflate(64.0);
		List<RotEntity> otherRots = world.getEntitiesOfClass(RotEntity.class, searchBox, e -> e != self);
		for (RotEntity other : otherRots) {
			if (other instanceof Mob otherMob && otherMob.getTarget() == target) {
				return true;
			}
		}
		return false;
	}

	private static boolean isValidTarget(Entity target, Entity self) {
		return isValidTarget(target, self, false);
	}

	private static boolean isValidTarget(Entity target, Entity self, boolean ignoreLineOfSight) {
		if (target == null || !target.isAlive() || target == self) return false;
		if (BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString().equals("spore:scent")) return false;
		if (self.distanceTo(target) > TARGET_RANGE) return false;

		// Always ignore woodbound entities unconditionally
		if (target.getType().is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse(K_WOODBOUND)))
			|| target instanceof RotEntity
			|| target.getClass().getName().toLowerCase().contains("splinter")
			|| target.getClass().getName().toLowerCase().contains("woodbound")) {
			return false;
		}

		boolean isRetaliation = false;
		if (self instanceof LivingEntity ls) {
			if (ls.getLastHurtByMob() == target || (target instanceof Mob mob && mob.getTarget() == self)) {
				isRetaliation = true;
			}
		}

		boolean isArphexTarget = isArphexEntity(target);

		// Always ignore passive and neutral entities (cows, sheep, pigs, chickens, villagers, ambient, water animals, slimes, etc.) unless they are tagged/registered as ArPhEx combat entities.
		if (!isArphexTarget && (target instanceof Villager || target instanceof AmbientCreature || target instanceof Animal || target instanceof Slime || target instanceof net.minecraft.world.entity.animal.WaterAnimal)) {
		    return false;
		}

		boolean bypassFactionFilter = shouldIgnoreCombatFilter(self) || shouldIgnoreCombatFilter(target);

		boolean isPlayer = target instanceof Player;

		if (!ignoreLineOfSight && !isPlayer && !isRetaliation && self instanceof LivingEntity ls && !ls.hasLineOfSight(target)) return false;

		// Retaliation check: whoever attacks it (except woodbound which was checked above)
		if (self instanceof LivingEntity ls) {
			if (ls.getLastHurtByMob() == target) {
				return true;
			}
			if (target instanceof Mob mob && mob.getTarget() == self) {
				return true;
			}
		}

		if (target instanceof Player p) {
			if (p instanceof ServerPlayer sp) {
				if (sp.gameMode.getGameModeForPlayer() != net.minecraft.world.level.GameType.SURVIVAL) return false;
			} else {
				if (p.isCreative() || p.isSpectator()) return false;
			}
			return true; // Automatically attack players since its made for overpowered players
		}

		if (target instanceof Monster || target instanceof net.minecraft.world.entity.monster.Enemy) {
			return true; // Automatically attack hostiles (monsters and enemies)
		}

		// Treat ArPhEx's own entity tag/namespace as hostile even when its classes do not implement Monster/Enemy.
		if (isArphexTarget) {
			return true;
		}

		String tid = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString().toLowerCase();
		if (tid.contains("arphex") || tid.contains("fractus") || tid.contains("hostile") || tid.contains("boss") || tid.contains("sentinel") 
			|| tid.contains("zombie") || tid.contains("skeleton") || tid.contains("creeper") || tid.contains("spider") 
			|| tid.contains("witch") || tid.contains("enderman") || tid.contains("piglin") || tid.contains("hoglin") 
			|| tid.contains("phantom") || tid.contains("ghast") || tid.contains("blaze") || tid.contains("magma") 
			|| tid.contains("pillager") || tid.contains("evoker") || tid.contains("vindicator") || tid.contains("vex") 
			|| tid.contains("ravager") || tid.contains("warden")) {
			return true;
		}

		return bypassFactionFilter;
	}

	private static boolean isArphexEntity(Entity target) {
		if (target == null) return false;
		ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
		String tid = entityId.toString().toLowerCase(java.util.Locale.ROOT);
		return target.getType().is(TagKey.create(Registries.ENTITY_TYPE, ResourceLocation.parse("arphex:arphex")))
			|| "arphex".equals(entityId.getNamespace())
			|| tid.contains("arphex");
	}

	private static void handleAdaptationScaling(Entity entity, boolean inCombat) {
		if (!(entity instanceof LivingEntity living)) return;
		double combatTicks = entity.getPersistentData().getDouble("sentinel_combat_ticks");
		if (inCombat) {
			combatTicks = Math.min(72000.0, combatTicks + 1.0);

			// Track and increment flying target ticks if targeting a flying enemy
			Mob mobCheck = (entity instanceof Mob m) ? m : null;
			LivingEntity combatTarget = (mobCheck != null) ? mobCheck.getTarget() : null;
			if (combatTarget != null) {
				boolean isTargetFlying = combatTarget.isFallFlying() || (!combatTarget.onGround() && combatTarget.getY() > entity.getY() + 2.0 && !combatTarget.isInWater() && !combatTarget.isInLava());
				if (isTargetFlying) {
					double flyingTicks = entity.getPersistentData().getDouble("sentinel_flying_target_ticks");
					entity.getPersistentData().putDouble("sentinel_flying_target_ticks", flyingTicks + 1.0);
				}
			}

			// Realistically learn teleportation if fighting End entity, Player utilizing Ender Pearl, seeing dropped ender pearl, or in the End dimension
			boolean learnTP = false;
			if (combatTicks > 3000.0) {
				learnTP = true;
			}
			if (entity.level().dimension() == net.minecraft.world.level.Level.END) {
				learnTP = true;
			} else {
				// Scan for near ender pearls (projectile)
				List<net.minecraft.world.entity.projectile.Projectile> projectiles = entity.level().getEntitiesOfClass(net.minecraft.world.entity.projectile.Projectile.class, AABB.ofSize(entity.position(), 48.0, 48.0, 48.0));
				for (net.minecraft.world.entity.projectile.Projectile proj : projectiles) {
					if (BuiltInRegistries.ENTITY_TYPE.getKey(proj.getType()).toString().contains("ender_pearl")) {
						learnTP = true;
						break;
					}
				}

				// Scan for dropped ender pearls (ItemEntity)
				if (!learnTP) {
					List<net.minecraft.world.entity.item.ItemEntity> items = entity.level().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, AABB.ofSize(entity.position(), 48.0, 48.0, 48.0));
					for (net.minecraft.world.entity.item.ItemEntity itemEnt : items) {
						if (itemEnt.getItem() != null && BuiltInRegistries.ITEM.getKey(itemEnt.getItem().getItem()).toString().contains("ender_pearl")) {
							learnTP = true;
							break;
						}
					}
				}

				if (!learnTP) {
					Mob mob = (entity instanceof Mob m) ? m : null;
					LivingEntity target = (mob != null) ? mob.getTarget() : null;
					if (target != null) {
						String targetId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
						boolean isEndEntity = targetId.contains("enderman") || targetId.contains("endermite") || targetId.contains("shulker") || targetId.contains("ender_dragon");
						if (isEndEntity) {
							learnTP = true;
						} else if (target instanceof Player playerCheck) {
							if (BuiltInRegistries.ITEM.getKey(playerCheck.getMainHandItem().getItem()).toString().contains("ender_pearl") ||
								BuiltInRegistries.ITEM.getKey(playerCheck.getOffhandItem().getItem()).toString().contains("ender_pearl")) {
								learnTP = true;
							}
						}
					}
				}
			}

			if (learnTP) {
				double progress = entity.getPersistentData().getDouble("sentinel_teleport_learning_progress");
				entity.getPersistentData().putDouble("sentinel_teleport_learning_progress", progress + 1.0);
			}
		} else {
			combatTicks = Math.max(0.0, combatTicks - 0.5);
		}
		entity.getPersistentData().putDouble("sentinel_combat_ticks", combatTicks);

		// Milestones database check
		checkLearnedMilestone(living, combatTicks, inCombat);

		// Adaptive Wolverine-style regeneration
		if ((living.getPersistentData().getBoolean("unlocked_regen") || entity.getPersistentData().getBoolean("sentinel_is_infinity_totem")) && living.getHealth() < living.getMaxHealth()) {
			double regenTimer = entity.getPersistentData().getDouble("sentinel_regen_timer") + 1.0;
			double combatFactor = 1.0 + (combatTicks / 1000.0) * ADAPTATION_REGEN_COMBAT_MULTIPLIER; // Up to 3.0x healing efficiency
			double healthRatio = (double) living.getHealth() / (double) living.getMaxHealth();
			double lowHealthFactor = 1.0;
			if (healthRatio < 0.25) {
				lowHealthFactor = ADAPTATION_REGEN_HEALTH_LOW_BURST; // Trigger extreme survival burst at critical damage!
			} else if (healthRatio < 0.50) {
				lowHealthFactor = ADAPTATION_REGEN_HEALTH_MID_BURST; // Double the tick rate below half health
			}

			double requiredTicks = 20.0 / (combatFactor * lowHealthFactor);
			if (requiredTicks < 1.0) requiredTicks = 1.0;

			if (regenTimer >= requiredTicks) {
				regenTimer = 0.0;
				float healAmount = (float) (ADAPTATION_REGEN_BASE_HEAL + (living.getMaxHealth() * ADAPTATION_REGEN_MAX_HEALTH_RATIO));
				if (entity.getPersistentData().getBoolean("sentinel_is_infinity_totem")) {
					healAmount *= 2.5F;
				}
				living.heal(healAmount);
			}
			entity.getPersistentData().putDouble("sentinel_regen_timer", regenTimer);
		}

		// Decay the recent damage pool for high-DPS adaptive resistance
		double recentDamage = entity.getPersistentData().getDouble("sentinel_recent_damage");
		recentDamage = Math.max(0.0, recentDamage * ADAPTATION_RESISTANCE_DECAY);
		entity.getPersistentData().putDouble("sentinel_recent_damage", recentDamage);

		boolean isInfinity = entity.getPersistentData().getBoolean("sentinel_is_infinity_totem");
		double effectiveRecentDamage = recentDamage * (isInfinity ? 2.5 : 1.0);

		// Apply adaptive resistance level depending on recent DPS
		if (effectiveRecentDamage > ADAPTATION_RESISTANCE_HIGH_THRESHOLD) {
			int amp = isInfinity ? 3 : 1; // Resistance IV instead of Resistance II
			living.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 40, amp, false, false));
		} else if (effectiveRecentDamage > ADAPTATION_RESISTANCE_MID_THRESHOLD) {
			int amp = isInfinity ? 2 : 0; // Resistance III instead of Resistance I
			living.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 40, amp, false, false));
		} else if (effectiveRecentDamage > ADAPTATION_RESISTANCE_LOW_THRESHOLD) {
			int amp = isInfinity ? 1 : 0; // Resistance II instead of Resistance I
			living.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 40, amp, false, false));
		}

		// Speed Adaptation (Starts slow with penalty. If teleport is unlocked, adapt to normal pacing. Otherwise, learn to run realistically!)
		if (living.getAttributes().hasAttribute(Attributes.MOVEMENT_SPEED)) {
			var attr = living.getAttribute(Attributes.MOVEMENT_SPEED);
			attr.removeModifier(ResourceLocation.parse("the_backwoods:sentinel_adaptation_speed"));
			double speedBonus;
			boolean tpUnlocked = entity.getPersistentData().getBoolean("unlocked_teleportation");
			if (tpUnlocked) {
				speedBonus = ADAPTATION_SPEED_MIN_MULTIPLIER + (combatTicks / ADAPTATION_SPEED_SCALING_TELEPORT) * (ADAPTATION_SPEED_MAX_MULTIPLIER - ADAPTATION_SPEED_MIN_MULTIPLIER);
				if (speedBonus > ADAPTATION_SPEED_MAX_MULTIPLIER) {
					speedBonus = ADAPTATION_SPEED_MAX_MULTIPLIER;
				}
			} else {
				// Learning how to run as a fallback, capping at a modest 25% sprint speed so it remains completely realistic
				speedBonus = ADAPTATION_SPEED_MIN_MULTIPLIER + (combatTicks / ADAPTATION_SPEED_SCALING_FALLBACK) * (ADAPTATION_SPEED_MAX_FALLBACK - ADAPTATION_SPEED_MIN_MULTIPLIER);
				if (speedBonus > ADAPTATION_SPEED_MAX_FALLBACK) {
					speedBonus = ADAPTATION_SPEED_MAX_FALLBACK;
				}
			}

			// Override speedBonus to 0.0 if circling a pillar to maintain a constant, cruising speed
			boolean targetOnPillar = false;
			Mob mobCheck = (entity instanceof Mob m) ? m : null;
			LivingEntity combatTarget = (mobCheck != null) ? mobCheck.getTarget() : null;
			if (combatTarget != null && isTargetPillaring(entity.level(), combatTarget, entity)) {
				double tdx = combatTarget.getX() - entity.getX();
				double tdz = combatTarget.getZ() - entity.getZ();
				double distSqXZ = tdx * tdx + tdz * tdz;
				double maxPillarDist = ROT_PILLAR_BACK_OFF_DISTANCE + 2.0;
				if (distSqXZ < maxPillarDist * maxPillarDist) {
					targetOnPillar = true;
				}
			}
			if (targetOnPillar) {
				speedBonus = 0.0;
			}

			if (entity.getPersistentData().getBoolean("sentinel_totem_active") && !entity.getPersistentData().getBoolean("sentinel_is_infinity_totem")) {
				speedBonus += 0.25;
			}
			attr.addTransientModifier(new AttributeModifier(ResourceLocation.parse("the_backwoods:sentinel_adaptation_speed"), speedBonus, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));

			// Slow down speed if laser charging or laser firing is active!
			attr.removeModifier(ResourceLocation.parse("the_backwoods:sentinel_laser_slowdown"));
			double solarCharge = entity.getPersistentData().getDouble("sentinel_solar_charge_ticks");
			double solarFire = entity.getPersistentData().getDouble("sentinel_solar_fire_ticks");
			if (solarCharge > 0 || solarFire > 0) {
				attr.addTransientModifier(new AttributeModifier(ResourceLocation.parse("the_backwoods:sentinel_laser_slowdown"), -0.85, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
			}
		}

		// Damage Adaptation (Starts with 20% penalty, eventually gains 20% damage boost after 1000 ticks)
		if (living.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)) {
			var attr = living.getAttribute(Attributes.ATTACK_DAMAGE);
			attr.removeModifier(ResourceLocation.parse("the_backwoods:sentinel_adaptation_damage"));
			double damageBonus = -0.20 + (combatTicks / 1000.0) * 0.40;
			if (entity.getPersistentData().getBoolean("sentinel_totem_active")) {
				damageBonus += 0.50; // Extra 50% permanent attack damage boost!
			}
			attr.addTransientModifier(new AttributeModifier(ResourceLocation.parse("the_backwoods:sentinel_adaptation_damage"), damageBonus, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
		}
	}

	private static void fireSuperchargedSonicBoom(LevelAccessor world, Entity self, Entity target) {
		if (!(world instanceof ServerLevel level)) return;
		self.getPersistentData().putDouble("sentinel_sonic_ticks", SONIC_BOOM_ANIMATION_TICKS); // Starts the customizable state machine
		playHostileSound(world, self.getX(), self.getY(), self.getZ(), "entity.warden.sonic_charge", 1.4F, 0.4F);
	}

	private static void fireSuperchargedSonicBoomEffectAndDamage(LevelAccessor world, Entity self, Entity target) {
		if (!(world instanceof ServerLevel level)) return;
		playHostileSound(world, self.getX(), self.getY(), self.getZ(), "entity.warden.sonic_boom", 1.5F, 0.3F);

		Vec3 lookVec = target.position().add(0, target.getBbHeight() * 0.5, 0).subtract(self.position().add(0, self.getBbHeight() * SONIC_BOOM_TORSO_Y_FACTOR, 0)).normalize();
		Vec3 eyePos = self.position().add(0, self.getBbHeight() * SONIC_BOOM_TORSO_Y_FACTOR, 0);
		double range = SONIC_BOOM_RANGE;

		for (double step = 1.0; step <= range; step += 1.0) {
			Vec3 beamPoint = eyePos.add(lookVec.scale(step));

			// Greatly reduced sonic/kinetic particle bursts
			if (step % 2 == 0) {
				level.sendParticles(ParticleTypes.SONIC_BOOM, beamPoint.x, beamPoint.y, beamPoint.z, 1, 0.1, 0.1, 0.1, 0.0);
			}
			level.sendParticles(ParticleTypes.CRIT, beamPoint.x, beamPoint.y, beamPoint.z, 1, 0.2, 0.2, 0.2, 0.1);

			// Devastate nearby living entities
			List<Entity> swept = level.getEntitiesOfClass(Entity.class, new AABB(beamPoint, beamPoint).inflate(2.4), e -> e != self && e instanceof LivingEntity);
			for (Entity targetVictim : swept) {
				targetVictim.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, net.minecraft.resources.ResourceLocation.parse("the_backwoods:rot_sonic_boom"))), self), 32.0F * (float) getAdaptationMultiplier(self));
				
				Vec3 push = targetVictim.position().subtract(self.position()).normalize();
				targetVictim.setDeltaMovement(push.x * 2.5, 0.6, push.z * 2.5);
			}
		}
	}

	private static void tryPredictiveDodge(LevelAccessor world, Entity self, Entity target, double dist) {
		if (self.getPersistentData().getDouble(K_TP_DODGE_CD) > 0) return;
		if (!(target instanceof LivingEntity tl) || dist > DODGE_TRIGGER_DIST) return;

		if (!self.getPersistentData().getBoolean("unlocked_teleportation")) return;

		double combatTicks = self.getPersistentData().getDouble("sentinel_combat_ticks");
		if (combatTicks < 100) return;

		boolean likelySwingNow = tl.swinging;
		boolean likelySwingSoon = false;
		if (target instanceof Player p) likelySwingSoon = p.getAttackStrengthScale(0.5f) > 0.9f && dist < 4.6;

		if (!(likelySwingNow || likelySwingSoon)) return;
		if (Math.random() > DODGE_SWING_CHANCE) return;

		Vec3 look = target.getLookAngle().normalize();
		Vec3 right = new Vec3(-look.z, 0, look.x).normalize();

		double side = Mth.nextDouble(RandomSource.create(), TELEPORT_SIDE_MIN, TELEPORT_SIDE_MAX);
		if (RandomSource.create().nextBoolean()) side *= -1;

		double tx = target.getX() + right.x * side - look.x * 0.8;
		double tz = target.getZ() + right.z * side - look.z * 0.8;

		trySafeTeleportToGround(world, self, tx, tz, "entity.warden.attack_impact", 1.5f, 0.85f, K_TP_DODGE_CD, TP_DODGE_CD);
	}

	private static void tryFlankTeleport(LevelAccessor world, Entity self, Entity target, double dist) {
		if (self.getPersistentData().getDouble(K_TP_FLANK_CD) > 0) return;

		if (!self.getPersistentData().getBoolean("unlocked_teleportation")) return;

		double combatTicks = self.getPersistentData().getDouble("sentinel_combat_ticks");
		if (combatTicks < 80) return;

		boolean noLos = true;
		if (self instanceof LivingEntity ls) noLos = !ls.hasLineOfSight(target);

		boolean shouldFlank = (dist > TELEPORT_MIN_GAP || noLos || self.getPersistentData().getBoolean("sentinel_totem_active")) && Math.random() < (self.getPersistentData().getBoolean("sentinel_totem_active") ? 0.28 : 0.18);
		if (!shouldFlank) return;

		Vec3 look = target.getLookAngle().normalize();
		Vec3 right = new Vec3(-look.z, 0, look.x).normalize();
		double side = Mth.nextDouble(RandomSource.create(), TELEPORT_SIDE_MIN, TELEPORT_SIDE_MAX);
		if (RandomSource.create().nextBoolean()) side *= -1;

		double tx = target.getX() - look.x * TELEPORT_BACK_OFFSET + right.x * side;
		double tz = target.getZ() - look.z * TELEPORT_BACK_OFFSET + right.z * side;

		trySafeTeleportToGround(world, self, tx, tz, "entity.warden.attack_impact", 1.8f, 0.55f, K_TP_FLANK_CD, TP_FLANK_CD);
	}



	@SubscribeEvent
	public static void onLivingDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
		LivingEntity entity = event.getEntity();
		if (entity instanceof RotEntity) {
			entity.getPersistentData().putDouble("sentinel_solar_fire_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_solar_charge_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_cryo_fire_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_cryo_charge_ticks", 0);
			stopHostileSound(entity.level(), entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:fractus_laser", 64.0);
		}
	}

	@SubscribeEvent
	public static void onEntityLeaveLevel(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
		Entity entity = event.getEntity();
		if (entity instanceof RotEntity) {
			entity.getPersistentData().putDouble("sentinel_solar_fire_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_solar_charge_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_cryo_fire_ticks", 0);
			entity.getPersistentData().putDouble("sentinel_cryo_charge_ticks", 0);
			stopHostileSound(entity.level(), entity.getX(), entity.getY(), entity.getZ(), "the_backwoods:fractus_laser", 64.0);
		}
	}

	private static void playTeleportEffects(ServerLevel level, Entity entity, double x, double y, double z) {
		Entity target = (entity instanceof Mob mob) ? mob.getTarget() : null;
		net.minecraft.core.particles.ParticleOptions mainParticle = ParticleTypes.PORTAL;
		net.minecraft.core.particles.ParticleOptions secondaryParticle = ParticleTypes.CAMPFIRE_COSY_SMOKE;
		String sound = "entity.warden.attack_impact";
		float vol = 0.9f;
		float pitch = 1.15f;

		if (target != null) {
			String targetId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
			if (targetId.contains("enderman") || targetId.contains("shulker")) {
				mainParticle = ParticleTypes.PORTAL;
				secondaryParticle = ParticleTypes.DRAGON_BREATH;
				sound = "entity.enderman.teleport";
				vol = 1.0f;
				pitch = 1.0f;
			} else if (targetId.contains("blaze") || targetId.contains("ghast") || targetId.contains("magma_cube") || target.level().dimension() == Level.NETHER) {
				mainParticle = ParticleTypes.FLAME;
				secondaryParticle = ParticleTypes.LAVA;
				sound = "item.firecharge.use";
				vol = 1.1f;
				pitch = 0.85f;
			} else if (targetId.contains("warden")) {
				mainParticle = ParticleTypes.SONIC_BOOM;
				secondaryParticle = ParticleTypes.CRIT;
				sound = "entity.warden.sonic_boom";
				vol = 0.8f;
				pitch = 1.4f;
			} else if (targetId.contains("wither")) {
				mainParticle = ParticleTypes.WITCH;
				secondaryParticle = ParticleTypes.SMOKE;
				sound = "entity.wither.shoot";
				vol = 0.9f;
				pitch = 0.75f;
			}
		}

		level.sendParticles(mainParticle, x, y + 1.0, z, 6, 0.2, 0.5, 0.2, 0.1);
		level.sendParticles(secondaryParticle, x, y + 1.1, z, 3, 0.15, 0.25, 0.15, 0.01);
		playHostileSound(level, x, y, z, sound, vol, pitch);
	}

	private static net.minecraft.core.particles.ParticleOptions getAdaptiveBeamParticle(Entity self) {
		Entity target = (self instanceof Mob mob) ? mob.getTarget() : null;
		if (target != null) {
			String targetId = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString();
			if (targetId.contains("blaze") || targetId.contains("ghast") || targetId.contains("magma_cube") || target.level().dimension() == Level.NETHER) {
				return ParticleTypes.LAVA;
			}
		}
		return ParticleTypes.FLAME;
	}

	public static boolean tryDodgeProjectile(Entity entity, DamageSource source) {
		if (!(entity.level() instanceof ServerLevel level)) {
			return false;
		}

		if (!entity.getPersistentData().getBoolean("unlocked_teleportation")) {
			return false;
		}

		double combatTicks = entity.getPersistentData().getDouble("sentinel_combat_ticks");
		if (combatTicks < 100) {
			return false;
		}

		// Sentinel projectile dodging starts mediocre (30%) and adapts up to peak mastery (95%) as it downloads ballistic files
		double dodgeChance = 0.30 + (Math.min(1000.0, combatTicks) / 1000.0) * 0.65;
		if (entity.getRandom().nextDouble() > dodgeChance) {
			return false;
		}

		Entity direct = source.getDirectEntity();
		Vec3 sourcePos = direct != null ? direct.position() : source.getSourcePosition();
		if (sourcePos == null) {
			sourcePos = entity.position().add(1.0, 0.0, 1.0);
		}

		Vec3 away = entity.position().subtract(sourcePos);
		Vec3 horizontalAway = new Vec3(away.x, 0.0, away.z);

		if (horizontalAway.lengthSqr() < 0.001) {
			horizontalAway = new Vec3(entity.getRandom().nextDouble() - 0.5, 0.0, entity.getRandom().nextDouble() - 0.5);
		}

		horizontalAway = horizontalAway.normalize();
		Vec3 side = new Vec3(-horizontalAway.z, 0.0, horizontalAway.x).scale(entity.getRandom().nextBoolean() ? 1.0 : -1.0);

		for (int i = 0; i < 16; i++) {
			double distance = 4.5 + entity.getRandom().nextDouble() * 5.5;
			double lift = (entity.getRandom().nextDouble() - 0.3) * 2.0;
			Vec3 candidate = entity.position()
				.add(horizontalAway.scale(distance))
				.add(side.scale((entity.getRandom().nextDouble() - 0.5) * 5.0))
				.add(0.0, lift, 0.0);

			double cy = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) candidate.x, (int) candidate.z);
			if (isSafeTeleportSpot(level, candidate.x, cy, candidate.z, entity.getY())) {
				playTeleportEffects(level, entity, entity.getX(), entity.getY(), entity.getZ());
				teleportEntity(entity, candidate.x, cy, candidate.z);
				playTeleportEffects(level, entity, candidate.x, cy, candidate.z);
				
				entity.setDeltaMovement(Vec3.ZERO);
				entity.hasImpulse = true;
				return true;
			}
		}
		return false;
	}

	private static void trySafeTeleportToGround(LevelAccessor world, Entity self, double targetX, double targetZ, String soundId, float vol, float pitch, String cdKey, int cdTicks) {
		double groundY = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) targetX, (int) targetZ);
		if (!isSafeTeleportSpot(world, targetX, groundY, targetZ, self.getY())) return;

		if (world instanceof ServerLevel level) {
			playTeleportEffects(level, self, self.getX(), self.getY(), self.getZ());
			teleportEntity(self, targetX, groundY, targetZ);
			playTeleportEffects(level, self, targetX, groundY, targetZ);
		} else {
			teleportEntity(self, targetX, groundY, targetZ);
			playHostileSound(world, targetX, groundY, targetZ, soundId, vol, pitch);
		}
		self.getPersistentData().putDouble(cdKey, cdTicks);
	}

	private static boolean isSafeTeleportSpot(LevelAccessor world, double x, double y, double z, double fromY) {
		if (Math.abs(y - fromY) > TELEPORT_MAX_VERTICAL_DIFF) return false;

		BlockPos feet = BlockPos.containing(x, y, z);
		BlockPos head = feet.above();
		BlockPos below = feet.below();

		BlockState feetState = world.getBlockState(feet);
		BlockState headState = world.getBlockState(head);
		BlockState belowState = world.getBlockState(below);

		if (!belowState.blocksMotion()) return false;
		if (!feetState.isAir() && !feetState.canBeReplaced()) return false;
		if (!headState.isAir() && !headState.canBeReplaced()) return false;
		return true;
	}

	private static void handleForwardCarveMining(LevelAccessor world, Entity self, Entity target) {
		if (target != null && isTargetPillaring(world, target, self)) {
			double tdx = target.getX() - self.getX();
			double tdz = target.getZ() - self.getZ();
			double distSqXZ = tdx * tdx + tdz * tdz;
			double maxPillarDist = ROT_PILLAR_BACK_OFF_DISTANCE + 2.0;
			if (distSqXZ < maxPillarDist * maxPillarDist) { // Within circling/mining range of the pillar
				double circleTicks = 0;
				if (self.getPersistentData() != null) {
					circleTicks = self.getPersistentData().getDouble("rot_pillar_circle_ticks") + 1.0;
					self.getPersistentData().putDouble("rot_pillar_circle_ticks", circleTicks);
				}

				// Manage circling vs staring state and timer
				double stateTimer = 0.0;
				double isCircling = 1.0; // Default to circling initially
				double angle = self.tickCount * 0.05;

				if (self.getPersistentData() != null) {
					if (!self.getPersistentData().contains("rot_pillar_is_circling")) {
						self.getPersistentData().putDouble("rot_pillar_is_circling", 1.0);
					}
					if (!self.getPersistentData().contains("rot_pillar_angle")) {
						self.getPersistentData().putDouble("rot_pillar_angle", angle);
					}
					stateTimer = self.getPersistentData().getDouble("rot_pillar_state_timer");
					isCircling = self.getPersistentData().getDouble("rot_pillar_is_circling");
					angle = self.getPersistentData().getDouble("rot_pillar_angle");
				}

				if (stateTimer <= 0) {
					double randState = self.getRandom().nextDouble();
					if (randState < ROT_PILLAR_CIRCLING_CHANCE) {
						isCircling = 1.0;
						stateTimer = 60.0 + self.getRandom().nextInt(61); // 3-6s circling
					} else {
						isCircling = 0.0;
						stateTimer = 160.0 + self.getRandom().nextInt(201); // 8-18s staring
					}
					if (self.getPersistentData() != null) {
						self.getPersistentData().putDouble("rot_pillar_is_circling", isCircling);
						self.getPersistentData().putDouble("rot_pillar_state_timer", stateTimer);
					}
				} else {
					stateTimer--;
					if (self.getPersistentData() != null) {
						self.getPersistentData().putDouble("rot_pillar_state_timer", stateTimer);
					}
				}

				if (isCircling == 1.0) {
					angle += 0.05; // Circular rotation step per tick
					if (self.getPersistentData() != null) {
						self.getPersistentData().putDouble("rot_pillar_angle", angle);
					}
				}

				double circleRadius = ROT_PILLAR_BACK_OFF_DISTANCE; // Distance to maintain from the pillar
				double targetCircleX = target.getX() + Math.cos(angle) * circleRadius;
				double targetCircleZ = target.getZ() + Math.sin(angle) * circleRadius;

				if (self instanceof Mob mob) {
					lockLookAtTarget(mob, target);
					if (isCircling == 1.0) {
						mob.getNavigation().moveTo(targetCircleX, self.getY(), targetCircleZ, ROT_PILLAR_CIRCLING_SPEED);
					} else {
						mob.getNavigation().stop();
					}
				}

				// Only consider attacking after the initial delay has elapsed
				if (circleTicks >= ROT_PILLAR_INITIAL_ATTACK_DELAY) {
					// Chance to use Minos Prime moves like Overhead Slam or Die Rider Kick if target is out of reach on a tall pillar
					double rand = self.getRandom().nextDouble();
					if (self.getPersistentData() != null && rand < ROT_PILLAR_ATTACK_CHANCE) {
						if (rand < (ROT_PILLAR_ATTACK_CHANCE / 2.0) && self.getPersistentData().getBoolean("unlocked_overhead_combo")) {
							// Trigger Overhead Slam
							self.getPersistentData().putDouble("rot_overhead_ticks", OVERHEAD_TOTAL_TICKS);
							self.getPersistentData().putString("overhead_target_uuid", target.getUUID().toString());
							self.getPersistentData().putDouble("rot_pillar_circle_ticks", 0.0); // Reset timer after attacking
						} else if (self.getPersistentData().getBoolean("unlocked_knockback_rider_combo") || self.getPersistentData().getBoolean("unlocked_triple_threat_combo") || self.getPersistentData().getBoolean("unlocked_dropkick_combo")) {
							// Trigger "Die" Rider Kick! Lift off into air first
							self.setDeltaMovement(0.0, 1.95, 0.0);
							self.hasImpulse = true;
							self.getPersistentData().putDouble("sentinel_die_kick_phase", 1);
							self.getPersistentData().putDouble("sentinel_die_kick_ticks", 22);
							self.getPersistentData().putDouble("sentinel_landing_ticks", 0);
							self.getPersistentData().putDouble("rot_pillar_circle_ticks", 0.0); // Reset timer after attacking
							if (world instanceof ServerLevel level) {
								playHostileSound(level, self.getX(), self.getY(), self.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
								level.sendParticles(ParticleTypes.CLOUD, self.getX(), self.getY() + 0.5, self.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
							}
						}
					}
				}
				return;
			}
		}

		if (self.getPersistentData() != null && self.getPersistentData().contains("rot_pillar_circle_ticks")) {
			self.getPersistentData().putDouble("rot_pillar_circle_ticks", 0.0);
		}

		double mineReach = isDoingCombo(self) ? 3.5 : MINE_REACH;

		float yaw = self.getYRot();
		double rad = Math.toRadians(yaw);
		Vec3 forward = new Vec3(-Math.sin(rad), 0, Math.cos(rad)).normalize();
		Vec3 right = new Vec3(-forward.z, 0, forward.x).normalize();
		int baseY = Mth.floor(self.getY());

		BlockPos refPos = null;
		float maxHardness = -1f;

		for (int depth = 1; depth <= (int) Math.ceil(mineReach); depth++) {
			double cx = self.getX() + forward.x * depth;
			double cz = self.getZ() + forward.z * depth;

			for (int w = -MINE_HALF_WIDTH; w <= MINE_HALF_WIDTH; w++) {
				double px = cx + right.x * w;
				double pz = cz + right.z * w;

				for (int h = 0; h < MINE_HEIGHT; h++) {
					BlockPos bp = BlockPos.containing(px, baseY + h, pz);

					if (target != null && bp.getY() == (int) (target.getY() - 2)) continue;

					BlockState st = world.getBlockState(bp);
					if (st.isAir() || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.canBeReplaced()) continue;

					float hard = st.getDestroySpeed(world, bp);
					if (hard < 0 || hard >= MAX_BREAKABLE_HARDNESS) continue;

					if (hard > maxHardness) {
						maxHardness = hard;
						refPos = bp;
					}
				}
			}
		}

		if (refPos == null) {
			if (self instanceof RotEntity rotSet) rotSet.getEntityData().set(RotEntity.DATA_mineProgress, 0);
			return;
		}

		if (self instanceof RotEntity rotSet) {
			int currentProgress = rotSet.getEntityData().get(RotEntity.DATA_mineProgress);
			if (currentProgress == 0 && self.getPersistentData() != null) {
				self.getPersistentData().putDouble("rot_random_mine_wait", 100.0 + self.getRandom().nextInt(101));
			}
			rotSet.getEntityData().set(RotEntity.DATA_mineProgress, currentProgress + 1);
		}

		if (self.tickCount % 10 == 0 && self instanceof LivingEntity liv) {
			liv.swing(InteractionHand.MAIN_HAND);
			if (world instanceof ServerLevel level) {
				level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, world.getBlockState(refPos)), refPos.getX() + 0.5, refPos.getY() + 0.5, refPos.getZ() + 0.5, 8, 0.3, 0.3, 0.3, 0.1);
			}
			boolean lastHandLeft = false;
			if (self.getPersistentData() != null) {
				lastHandLeft = self.getPersistentData().getBoolean("sentinel_punch_hand_toggle");
				self.getPersistentData().putBoolean("sentinel_punch_hand_toggle", !lastHandLeft);
				if (lastHandLeft) {
					self.getPersistentData().putDouble("sentinel_left_punch_ticks", 8);
					self.getPersistentData().putDouble("sentinel_right_punch_ticks", 0);
				} else {
					self.getPersistentData().putDouble("sentinel_right_punch_ticks", 8);
					self.getPersistentData().putDouble("sentinel_left_punch_ticks", 0);
				}
			}
		}

		float mineThreshold = 150f;
		if (self.getPersistentData() != null && self.getPersistentData().contains("rot_random_mine_wait")) {
			mineThreshold = (float) self.getPersistentData().getDouble("rot_random_mine_wait");
		}

		int progress = self instanceof RotEntity rot ? rot.getEntityData().get(RotEntity.DATA_mineProgress) : 0;
		if (progress < mineThreshold) return;

		for (int depth = 1; depth <= (int) Math.ceil(mineReach); depth++) {
			double cx = self.getX() + forward.x * depth;
			double cz = self.getZ() + forward.z * depth;

			for (int w = -MINE_HALF_WIDTH; w <= MINE_HALF_WIDTH; w++) {
				double px = cx + right.x * w;
				double pz = cz + right.z * w;

				for (int h = 0; h < MINE_HEIGHT; h++) {
					BlockPos bp = BlockPos.containing(px, baseY + h, pz);

					if (target != null && bp.getY() == (int) (target.getY() - 2)) continue;

					BlockState st = world.getBlockState(bp);
					float hard = st.getDestroySpeed(world, bp);
					if (hard < 0 || hard >= MAX_BREAKABLE_HARDNESS || st.isAir() || st.is(net.minecraft.tags.BlockTags.LEAVES) || st.canBeReplaced()) continue;

					world.destroyBlock(bp, false);
					if (world instanceof Level l) l.updateNeighborsAt(bp, l.getBlockState(bp).getBlock());
				}
			}
		}

		if (self instanceof RotEntity rotSet) rotSet.getEntityData().set(RotEntity.DATA_mineProgress, 0);
	}

	private static boolean isDoingCombo(Entity entity) {
		return entity.getPersistentData().getDouble("sentinel_combo_active_ticks") > 0
			|| entity.getPersistentData().getDouble("sentinel_sky_warp_slam_ticks") > 0
			|| entity.getPersistentData().getDouble("sentinel_judgment_ticks") > 0
			|| entity.getPersistentData().getDouble("sentinel_minos_ticks") > 0
			|| entity.getPersistentData().getDouble("sentinel_cc1_stage") > 0
			|| entity.getPersistentData().getDouble("sentinel_cc2_stage") > 0
			|| entity.getPersistentData().getDouble("sentinel_cc3_stage") > 0
			|| entity.getPersistentData().getDouble("sentinel_cc4_stage") > 0
			|| entity.getPersistentData().getDouble("sentinel_cc5_stage") > 0;
	}

	private static void checkTrenchAndJump(LevelAccessor world, Entity self, Entity target) {
		if (!(self instanceof LivingEntity living) || !living.onGround()) {
			return;
		}
		if (target == null) {
			return;
		}

		double dx = target.getX() - self.getX();
		double dz = target.getZ() - self.getZ();
		double distXZ = Math.sqrt(dx * dx + dz * dz);
		if (distXZ < 0.1) {
			return;
		}
		double dirX = dx / distXZ;
		double dirZ = dz / distXZ;

		boolean hasTrench = false;
		double[] checkDistances = {1.2, 2.2};
		for (double d : checkDistances) {
			double cx = self.getX() + dirX * d;
			double cz = self.getZ() + dirZ * d;
			net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(cx, self.getY(), cz);
			
			if (world.getBlockState(pos).isAir() && world.getBlockState(pos.below()).isAir()) {
				int depth = 0;
				net.minecraft.core.BlockPos tracePos = pos.below();
				while (depth < 5 && world.getBlockState(tracePos).isAir()) {
					tracePos = tracePos.below();
					depth++;
				}
				if (depth >= 2) {
					hasTrench = true;
					break;
				}
			}
		}

		if (hasTrench) {
			Vec3 motion = self.getDeltaMovement();
			double jumpY = 0.52;
			double jumpForward = 0.35;
			self.setDeltaMovement(motion.x + dirX * jumpForward, jumpY, motion.z + dirZ * jumpForward);
			living.setJumping(true);
			self.hasImpulse = true;
			
			if (world instanceof ServerLevel level) {
				net.minecraft.core.BlockPos belowPos = net.minecraft.core.BlockPos.containing(self.getX(), self.getY() - 0.5, self.getZ());
				net.minecraft.world.level.block.state.BlockState floorState = level.getBlockState(belowPos);
				if (!floorState.isAir()) {
					net.minecraft.core.particles.BlockParticleOption dust = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.DUST_PILLAR, floorState);
					level.sendParticles(dust, self.getX(), self.getY() + 0.1, self.getZ(), 8, 0.25, 0.1, 0.25, 0.05);
				}
			}
		} else if (target.getY() > self.getY() + 1.5) {
			double cx = self.getX() + dirX * 1.2;
			double cz = self.getZ() + dirZ * 1.2;
			net.minecraft.core.BlockPos posAhead = net.minecraft.core.BlockPos.containing(cx, self.getY(), cz);
			if (!world.getBlockState(posAhead).isAir() || !world.getBlockState(posAhead.above()).isAir()) {
				Vec3 motion = self.getDeltaMovement();
				double jumpY = 0.52;
				self.setDeltaMovement(motion.x + dirX * 0.15, jumpY, motion.z + dirZ * 0.15);
				living.setJumping(true);
				self.hasImpulse = true;
			}
		}
	}

	private static void handlePassengerAndGrowth(Entity entity) {
		if (entity.isPassenger()) entity.stopRiding();

		entity.getPersistentData().putDouble(K_AGE, entity.getPersistentData().getDouble(K_AGE) + 1);
		if (entity.getPersistentData().getDouble(K_AGE) % 1200 == 0) {
			if (entity instanceof LivingEntity living && living.getAttributes().hasAttribute(Attributes.MAX_HEALTH)) {
				living.getAttribute(Attributes.MAX_HEALTH).setBaseValue(living.getAttribute(Attributes.MAX_HEALTH).getBaseValue() + 2);
				living.setHealth(living.getHealth() + 2);
			}
		}
	}

	private static void tickCooldown(Entity e, String key, int step) {
		double v = e.getPersistentData().getDouble(key);
		if (v > 0) {
			double finalStep = step;
			if (e.getPersistentData().getBoolean("sentinel_totem_active") && !e.getPersistentData().getBoolean("sentinel_is_infinity_totem") && (key.endsWith("cd") || key.contains("_cd") || key.contains("cooldown") || key.equals("sentinel_solar_cd"))) {
				finalStep = step * 2.0; // cooldowns tick down twice as fast!
			}
			if (COOLDOWN_MULTIPLIER > 0 && (key.endsWith("cd") || key.contains("_cd") || key.contains("cooldown") || key.equals("sentinel_solar_cd"))) {
				finalStep = finalStep / COOLDOWN_MULTIPLIER;
			}
			e.getPersistentData().putDouble(key, Math.max(0, v - finalStep));
		}
	}

	private static void playHostileSound(LevelAccessor world, double x, double y, double z, String soundId, float volume, float pitch) {
		if (!(world instanceof Level level)) return;
		net.minecraft.sounds.SoundEvent sound = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse(soundId));
		if (sound != null) {
			if (!level.isClientSide()) {
				level.playSound(null, BlockPos.containing(x, y, z), sound, SoundSource.HOSTILE, volume, pitch);
			} else {
				level.playLocalSound(x, y, z, sound, SoundSource.HOSTILE, volume, pitch, false);
			}
		}
	}

	private static void stopHostileSound(LevelAccessor world, double x, double y, double z, String soundId, double range) {
		if (world instanceof ServerLevel level) {
			net.minecraft.network.protocol.game.ClientboundStopSoundPacket packet = new net.minecraft.network.protocol.game.ClientboundStopSoundPacket(ResourceLocation.parse(soundId), SoundSource.HOSTILE);
			for (ServerPlayer player : level.getPlayers(p -> p.position().distanceToSqr(x, y, z) <= range * range)) {
				player.connection.send(packet);
			}
		}
	}

	private static void applyKnockbackAndSync(Entity victim, double vx, double vy, double vz) {
		if (!(victim instanceof Player)) {
			vx *= 1.6;
			vz *= 1.6;
			vy *= 1.2;
		}
		victim.setDeltaMovement(vx, vy, vz);
		victim.hasImpulse = true;
		victim.hurtMarked = true;
		if (victim instanceof ServerPlayer sp) {
			sp.connection.send(new net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket(sp));
		}
	}

	private static void teleportEntity(Entity ent, double x, double y, double z) {
		double startX = ent.getX();
		double startY = ent.getY();
		double startZ = ent.getZ();
		ent.teleportTo(x, y, z);
		if (ent instanceof ServerPlayer sp) {
			sp.connection.teleport(x, y, z, ent.getYRot(), ent.getXRot());
		}
		if (ent.level() instanceof ServerLevel level) {
			spawnTeleportTrail(level, startX, startY, startZ, x, y, z);
		}
	}

	private static void spawnTeleportTrail(ServerLevel level, double startX, double startY, double startZ, double targetX, double targetY, double targetZ) {
		Vec3 start = new Vec3(startX, startY, startZ);
		Vec3 end = new Vec3(targetX, targetY, targetZ);
		double distance = start.distanceTo(end);
		if (distance < 1.0) return;

		int steps = (int) Math.ceil(distance * 1.5);
		for (int i = 0; i <= steps; i++) {
			double pct = (double) i / steps;
			double px = startX + (targetX - startX) * pct;
			double pz = startZ + (targetZ - startZ) * pct;
			double py = startY + (targetY - startY) * pct;

			BlockPos checkPos = BlockPos.containing(px, py + 1.0, pz);
			BlockPos groundPos = checkPos;
			boolean foundGround = false;
			for (int dy = 2; dy >= -5; dy--) {
				BlockPos bp = checkPos.above(dy);
				if (!level.getBlockState(bp).isAir() && level.getBlockState(bp).getFluidState().isEmpty() && level.getBlockState(bp).blocksMotion()) {
					groundPos = bp;
					foundGround = true;
					break;
				}
			}

			double spawnY = foundGround ? (groundPos.getY() + 1.0) : py;
			BlockState state = foundGround ? level.getBlockState(groundPos) : net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();

			if (!state.isAir()) {
				try {
					level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, state), px, spawnY + 0.1, pz, 4, 0.1, 0.1, 0.1, 0.15);
				} catch (Exception ignored) {}
			}
		}
	}

	private static Entity findEntityInWorldRange(LevelAccessor world, Class<? extends Entity> clazz, double x, double y, double z, double range, Entity self) {
		return world.getEntitiesOfClass(clazz, AABB.ofSize(new Vec3(x, y, z), range, range, range), e -> isValidTarget(e, self))
				.stream()
				.sorted((e1, e2) -> {
					if (e1 instanceof LivingEntity l1 && e2 instanceof LivingEntity l2) {
						boolean occ1 = isTargetOccupied(world, self, l1);
						boolean occ2 = isTargetOccupied(world, self, l2);
						if (occ1 != occ2) {
							return occ1 ? 1 : -1;
						}
						float hp1 = l1.getHealth();
						float hp2 = l2.getHealth();
						if (Math.abs(hp1 - hp2) > 0.05f) {
							return Float.compare(hp1, hp2);
						}
					}
					return Double.compare(e1.distanceToSqr(x, y, z), e2.distanceToSqr(x, y, z));
				})
				.findFirst()
				.orElse(null);
	}

	public static boolean isChannelingAbility(Entity entity) {
		if (entity == null) return false;
		double solarCharge = entity.getPersistentData().getDouble("sentinel_solar_charge_ticks");
		double solarFire = entity.getPersistentData().getDouble("sentinel_solar_fire_ticks");
		double cryoCharge = entity.getPersistentData().getDouble("sentinel_cryo_charge_ticks");
		double cryoFire = entity.getPersistentData().getDouble("sentinel_cryo_fire_ticks");
		double grappleTicks = entity.getPersistentData().getDouble("sentinel_grapple_ticks");
		double tkTicks = entity.getPersistentData().getDouble("sentinel_tk_ticks");
		double sonicTicks = entity.getPersistentData().getDouble("sentinel_sonic_ticks");
		double closingTicks = entity.getPersistentData().getDouble("sentinel_laser_closing_ticks");
		double skyWarp = entity.getPersistentData().getDouble("sentinel_sky_warp_slam_ticks");
		double judgment = entity.getPersistentData().getDouble("sentinel_judgment_ticks");
		double omniSonic = entity.getPersistentData().getDouble("sentinel_omni_sonic_charge_ticks");
		double sonicScream = entity.getPersistentData().getDouble("sentinel_sonic_scream_ticks");
		double armorRipTicks = entity.getPersistentData().getDouble("rot_armor_rip_ticks");
		double blockTicks = entity.getPersistentData().getDouble("rot_block_active_ticks");
		return solarCharge > 0 || solarFire > 0 || cryoCharge > 0 || cryoFire > 0 || grappleTicks > 0 || tkTicks > 0 || sonicTicks > 0 || closingTicks > 0 || skyWarp > 0 || judgment > 0 || omniSonic > 0 || sonicScream > 0 || armorRipTicks > 0 || blockTicks > 0;
	}

	private static void syncNBTFlags(Entity entity) {
		double solarCharge = entity.getPersistentData().getDouble("sentinel_solar_charge_ticks");
		double cryoCharge = entity.getPersistentData().getDouble("sentinel_cryo_charge_ticks");
		double solarFire = entity.getPersistentData().getDouble("sentinel_solar_fire_ticks");
		double cryoFire = entity.getPersistentData().getDouble("sentinel_cryo_fire_ticks");
		double closingTicks = entity.getPersistentData().getDouble("sentinel_laser_closing_ticks");
		double sonicTicks = entity.getPersistentData().getDouble("sentinel_sonic_ticks");
		double sonicCooldown = entity.getPersistentData().getDouble("sentinel_warden_sonic_cooldown");
		double landingTicks = entity.getPersistentData().getDouble("sentinel_landing_ticks");

		double leftPunchTicks = entity.getPersistentData().getDouble("sentinel_left_punch_ticks");
		double rightPunchTicks = entity.getPersistentData().getDouble("sentinel_right_punch_ticks");
		double heavyLeftPunchTicks = entity.getPersistentData().getDouble("sentinel_heavy_left_punch_ticks");
		double heavyRightPunchTicks = entity.getPersistentData().getDouble("sentinel_heavy_right_punch_ticks");
		double overheadTicks = entity.getPersistentData().getDouble("rot_overhead_ticks");
		if (entity.getPersistentData().getBoolean("debug_force_overhead")) {
			if (overheadTicks <= 1) {
				entity.getPersistentData().putDouble("rot_overhead_ticks", OVERHEAD_TOTAL_TICKS);
				overheadTicks = OVERHEAD_TOTAL_TICKS;
			}
		}
		double slamPhase = entity.getPersistentData().getDouble("sentinel_slam_phase");
		double skyWarpTicks = entity.getPersistentData().getDouble("sentinel_sky_warp_slam_ticks");

		boolean leftPunching = leftPunchTicks > 0;
		boolean rightPunching = rightPunchTicks > 0;
		boolean isHeavyLeftPunching = heavyLeftPunchTicks > 0 || entity.getPersistentData().getBoolean("debug_force_heavy_left");
		boolean isHeavyRightPunching = heavyRightPunchTicks > 0 || entity.getPersistentData().getBoolean("debug_force_heavy_right");
		boolean overheadActive = overheadTicks > 0;
		boolean inSlamCharge = slamPhase == 2;

		// [FIXED] isAirborne now only triggers properly for leaping or falling from great heights to avoid air time anim casually playing
		boolean isAirborne = false;
		boolean isHeavyFalling = false;
		if (!entity.onGround() && !entity.isInWater() && !entity.isInLava()) {
			if (entity.getDeltaMovement().y() > 0.4) {
				isAirborne = true;
			}
			if (entity.getDeltaMovement().y() < -0.15 && entity.fallDistance > 3.0F) {
				isHeavyFalling = true;
			}
		}

		if (slamPhase == 1 || skyWarpTicks > 0) {
			isAirborne = true;
		} else if (slamPhase == 2) {
			isAirborne = false;
		} else if (slamPhase == 3) {
			isAirborne = false;
		}

		double dieKickPhase = entity.getPersistentData().getDouble("sentinel_die_kick_phase");
		if (dieKickPhase == 1) {
			isAirborne = true;
		} else if (dieKickPhase == 2) {
			isAirborne = true;
		} else if (dieKickPhase == 3) {
			isAirborne = false;
		}

		double judgmentTicks = entity.getPersistentData().getDouble("sentinel_judgment_ticks");
		double sonicScreamTicks = entity.getPersistentData().getDouble("sentinel_sonic_scream_ticks");
		boolean charging = solarCharge > 0 || cryoCharge > 0 || (sonicScreamTicks > 200.0);
		boolean firing = solarFire > 0 || cryoFire > 0 || (sonicScreamTicks > 20.0 && sonicScreamTicks <= 200.0);
		boolean closing = (closingTicks > 0 && !firing && !charging) || (sonicScreamTicks > 0.0 && sonicScreamTicks <= 20.0);
		boolean sonic = sonicTicks > 0;
		boolean landing = landingTicks > 0;
		boolean isGroundCrushing = slamPhase == 3 || skyWarpTicks > 0 || (landingTicks > 0 && landingTicks <= 17);
		boolean isDropkickCharging = judgmentTicks > 20;

		entity.getPersistentData().putBoolean("laser_charging", charging);
		entity.getPersistentData().putBoolean("is_laser_charging", charging);
		entity.getPersistentData().putBoolean("laser_firing", firing);
		entity.getPersistentData().putBoolean("is_laser_firing", firing);
		entity.getPersistentData().putBoolean("laser_closing", closing);
		entity.getPersistentData().putBoolean("is_laser_closing", closing);
		entity.getPersistentData().putBoolean("sonic_boom_active", sonic);
		entity.getPersistentData().putBoolean("is_airborne", isAirborne);
		entity.getPersistentData().putBoolean("sentinel_is_airborne_state", isAirborne);
		entity.getPersistentData().putBoolean("is_landing", landing);
 
		entity.getPersistentData().putBoolean("is_left_punching", leftPunching);
		entity.getPersistentData().putBoolean("is_right_punching", rightPunching);
		entity.getPersistentData().putBoolean("is_heavy_left_punching", isHeavyLeftPunching);
		entity.getPersistentData().putBoolean("is_heavy_right_punching", isHeavyRightPunching);
		entity.getPersistentData().putBoolean("is_overhead", overheadTicks > 0);
		entity.getPersistentData().putBoolean("is_slam_charge", inSlamCharge);
		entity.getPersistentData().putBoolean("is_air_time", isAirborne);
		entity.getPersistentData().putBoolean("is_overhead_preparing", overheadTicks >= OVERHEAD_PREP_THRESHOLD);
		entity.getPersistentData().putBoolean("is_ground_crushing", isGroundCrushing);
		entity.getPersistentData().putBoolean("is_dropkick_charging", isDropkickCharging);
		entity.getPersistentData().putBoolean("is_airborne_state", isAirborne);
 
		if (entity instanceof RotEntity rot) {
			try {
				rot.getEntityData().set(RotEntity.DATA_sentinel_solar_charge_ticks, (int) solarCharge);
				rot.getEntityData().set(RotEntity.DATA_sentinel_cryo_charge_ticks, (int) cryoCharge);
				rot.getEntityData().set(RotEntity.DATA_is_laser_firing, firing || charging);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_left_punching, leftPunching);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_right_punching, rightPunching);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_heavy_left_punching, isHeavyLeftPunching);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_heavy_right_punching, isHeavyRightPunching);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_laser_closing, closing);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_airborne_state, isAirborne);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_overhead_preparing, overheadTicks >= OVERHEAD_PREP_THRESHOLD);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_overhead, overheadTicks > 0);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_slam_charge, inSlamCharge);
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_ground_crushing, isGroundCrushing);
			} catch (Exception e) {}
			try {
				boolean forceFall = entity.getPersistentData().getBoolean("debug_force_fall");
				rot.getEntityData().set(RotEntity.DATA_is_falling_heavy, forceFall || isHeavyFalling);
			} catch (Exception e) {}
			try {
				boolean forceRider = entity.getPersistentData().getBoolean("debug_force_rider");
				rot.getEntityData().set(RotEntity.DATA_is_rider_charging, forceRider || (judgmentTicks > 0 && judgmentTicks <= 20));
			} catch (Exception e) {}
			try {
				rot.getEntityData().set(RotEntity.DATA_is_rider_kick, dieKickPhase == 3);
			} catch (Exception e) {}
			try {
				boolean forceSonic = entity.getPersistentData().getBoolean("debug_force_sonic");
				rot.getEntityData().set(RotEntity.DATA_is_sonic_boom, forceSonic || sonic);
			} catch (Exception e) {}
			try {
				boolean sonicLarge = entity.getPersistentData().getBoolean("is_sonic_boom_large");
				rot.getEntityData().set(RotEntity.DATA_is_sonic_boom_large, sonicLarge);
			} catch (Exception e) {}
			try {
				boolean armorRipping = entity.getPersistentData().getBoolean("is_armor_ripping");
				rot.getEntityData().set(RotEntity.DATA_is_armor_ripping, armorRipping);
			} catch (Exception e) {}
			try {
				boolean isBlocking = entity.getPersistentData().getBoolean("is_blocking");
				rot.getEntityData().set(RotEntity.DATA_is_blocking, isBlocking);
			} catch (Exception e) {}
			try {
				boolean isBlockingFinish = entity.getPersistentData().getBoolean("is_blocking_finish");
				rot.getEntityData().set(RotEntity.DATA_is_blocking_finish, isBlockingFinish);
			} catch (Exception e) {}
			try {
				boolean isUppercutting = entity.getPersistentData().getBoolean("is_uppercutting");
				rot.getEntityData().set(RotEntity.DATA_is_uppercutting, isUppercutting);
			} catch (Exception e) {}
			try {
				boolean isDropkickChargingSynced = entity.getPersistentData().getBoolean("is_dropkick_charging");
				rot.getEntityData().set(RotEntity.DATA_is_dropkick_charging, isDropkickChargingSynced);
			} catch (Exception e) {}
		}
	}

	private static boolean handleOverheadState(LevelAccessor world, Entity entity, @Nullable Entity combatTarget) {
		double overheadTicks = entity.getPersistentData().getDouble("rot_overhead_ticks");
		if (entity.getPersistentData().getBoolean("debug_force_overhead")) {
			if (overheadTicks <= 1) {
				entity.getPersistentData().putDouble("rot_overhead_ticks", OVERHEAD_TOTAL_TICKS);
				overheadTicks = OVERHEAD_TOTAL_TICKS;
			}
		}

		if (overheadTicks > 0) {
			Entity targetEntity = null;
			if (world instanceof ServerLevel serverLevel) {
				try {
					String targetUUIDStr = entity.getPersistentData().getString("overhead_target_uuid");
					java.util.UUID targetUUID = !targetUUIDStr.isEmpty() ? java.util.UUID.fromString(targetUUIDStr) : null;
					if (targetUUID != null) {
						targetEntity = serverLevel.getEntity(targetUUID);
					}
				} catch (Exception ignored) {}
			}
			if (targetEntity == null) {
				targetEntity = combatTarget;
			}

			boolean overheadStarted = entity.getPersistentData().getBoolean("rot_overhead_started");
			if (!overheadStarted) {
				entity.getPersistentData().putBoolean("rot_overhead_started", true);
				// Phase 1: the rot jumps at normal height (equivalent to jump boost 2)
				entity.setDeltaMovement(0.0, 0.6, 0.0);
				entity.hasImpulse = true;

				// Phase 2: Teleports to target's head immediately
				if (targetEntity != null) {
					entity.teleportTo(targetEntity.getX(), targetEntity.getY() + targetEntity.getEyeHeight() + 1.2, targetEntity.getZ());
					lockLookAtTarget(entity, targetEntity);
				}
			} else if (overheadTicks >= OVERHEAD_PREP_THRESHOLD) {
				// Windup/Charge duration (first 0.33s / 7 ticks total): Keep suspended completely in mid-air above target's head
				if (targetEntity != null) {
					entity.teleportTo(targetEntity.getX(), targetEntity.getY() + targetEntity.getEyeHeight() + 1.4, targetEntity.getZ());
					lockLookAtTarget(entity, targetEntity);
				}
				entity.setDeltaMovement(0, 0, 0);
			} else if (overheadTicks == OVERHEAD_STRIKE_TICK) {
				// Phase 3: At 0.33s (tick 13), executes (snaps its body and strikes down)
				// Hit target from above like a heavy hammer, instantly sinking them by a block
				if (targetEntity instanceof LivingEntity liv) {
					double originalY = liv.getY();
					double targetSunkY = originalY - 1.0;
					
					// Perform heavy drop hit block/liquid collision check
					BlockPos belowPos = BlockPos.containing(liv.getX(), targetSunkY, liv.getZ());
					if (world.getBlockState(belowPos).isCollisionShapeFullBlock(world, belowPos)) {
						targetSunkY = originalY; // Can't sink below bedrock or solid block base, keep on surface
					}
					
					liv.teleportTo(liv.getX(), targetSunkY, liv.getZ());
					liv.setDeltaMovement(liv.getDeltaMovement().x(), -2.5, liv.getDeltaMovement().z());
					liv.hasImpulse = true;
					liv.hurtMarked = true;
					
					net.minecraft.world.damagesource.DamageSource damageSource;
					if (world instanceof ServerLevel level) {
						damageSource = new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_seismic_slam"))), entity);
					} else {
						damageSource = liv.damageSources().generic();
					}
					liv.hurt(damageSource, 25.0F * (float) getAdaptationMultiplier(entity));

					// Trigger the SAME shockwave around the player/target
					entity.getPersistentData().putDouble("sentinel_shockwave_stage", 1);
					entity.getPersistentData().putDouble("sentinel_shockwave_x", liv.getX());
					entity.getPersistentData().putDouble("sentinel_shockwave_y", liv.getY());
					entity.getPersistentData().putDouble("sentinel_shockwave_z", liv.getZ());
					entity.getPersistentData().putBoolean("sentinel_shockwave_vertical", false);

					playHostileSound(world, liv.getX(), liv.getY(), liv.getZ(), "entity.generic.explode", 1.5F, 0.55F);
					playHostileSound(world, liv.getX(), liv.getY(), liv.getZ(), "entity.iron_golem.attack", 1.8F, 0.45F);

					if (world instanceof ServerLevel level) {
						level.sendParticles(ParticleTypes.SONIC_BOOM, liv.getX(), liv.getY() + 0.5, liv.getZ(), 1, 0, 0, 0, 0);
						boolean totemActive = entity.getPersistentData().getBoolean("sentinel_totem_active");
						// Citadel Camera Shake Delivery (Method 1) - reduced for sonic boom particles
						sendCameraShake(totemActive ? 0.4F : 0.25F, totemActive ? 12 : 6, totemActive ? 15.0F : 10.0F);
					}
				}
			}

			handlePassengerAndGrowth(entity);
			return true;
		}
		entity.getPersistentData().putBoolean("rot_overhead_started", false);
		return false;
	}

	private static void handleHeavyPunchState(LevelAccessor world, Entity entity, @Nullable Entity combatTarget) {
		double heavyLeftTicks = entity.getPersistentData().getDouble("sentinel_heavy_left_punch_ticks");
		double heavyRightTicks = entity.getPersistentData().getDouble("sentinel_heavy_right_punch_ticks");

		// Precise strike tick: when the remaining animation ticks reach exactly HEAVY_PUNCH_STRIKE_TICK (matching overhead!)
		boolean strikeLeft = (heavyLeftTicks == HEAVY_PUNCH_STRIKE_TICK);
		boolean strikeRight = (heavyRightTicks == HEAVY_PUNCH_STRIKE_TICK);

		if (strikeLeft || strikeRight) {
			Entity target = combatTarget;
			if (target == null && world instanceof ServerLevel level) {
				target = level.getNearestPlayer(entity, 4.5);
			}
			if (target != null && entity.distanceTo(target) <= 4.5) {
				// Punch with hard knockback (high speed / magnitude)
				Vec3 knockDir = target.position().subtract(entity.position()).normalize();
				applyKnockbackAndSync(target, knockDir.x * 2.2, 0.35, knockDir.z * 2.2);

				// Deal punch damage
				if (world instanceof ServerLevel level) {
					target.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_consecutive_punches"))), entity), (float) MELEE_PUNCH_DAMAGE * (float) getAdaptationMultiplier(entity));
				}

				if (world instanceof ServerLevel level) {
					level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY() + 0.8, target.getZ(), 15, 0.3, 0.3, 0.3, 0.2);
					level.sendParticles(ParticleTypes.SWEEP_ATTACK, target.getX(), target.getY() + 1.0, target.getZ(), 2, 0.2, 0.2, 0.2, 0.0);
					level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, target.getX(), target.getY() + 1.0, target.getZ(), 5, 0.2, 0.2, 0.2, 0.05);
					// Heavy punch special pitched-down sound mix!
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.6F, 0.45F);
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.generic.explode", 0.8F, 0.45F);
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sonic_boom", 0.6F, 0.4F);
				}
			}
		}
	}

	private static boolean handleSlamState(LevelAccessor world, Entity entity, @Nullable Entity combatTarget) {
		double slamPhase = entity.getPersistentData().getDouble("sentinel_slam_phase");
		double slamTicks = entity.getPersistentData().getDouble("sentinel_slam_ticks");

		if (slamPhase > 0) {
			if (slamTicks > 0) {
				entity.getPersistentData().putDouble("sentinel_slam_ticks", slamTicks - 1);
			}

			if (slamPhase == 1) {
				if (combatTarget != null) {
					lockLookAtTarget(entity, combatTarget);
				}
				// Allow natural upward momentum (from high jump boost 10) but restrict horizontal drift so it rises straight up
				Vec3 m = entity.getDeltaMovement();
				entity.setDeltaMovement(0.0, m.y(), 0.0);

				if (slamTicks <= 1) {
					entity.getPersistentData().putDouble("sentinel_slam_phase", 2);
					entity.getPersistentData().putDouble("sentinel_slam_ticks", 25); // Freeze for at least 1 second (25 ticks)
					playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sonic_charge", 1.5F, 0.75F);
				}
			} else if (slamPhase == 2) {
				// Suspend completely in mid-air (play slam charge and hold)
				entity.setDeltaMovement(0, 0, 0);
				if (combatTarget != null) {
					lockLookAtTarget(entity, combatTarget);
				}
				if (world instanceof ServerLevel level) {
					level.sendParticles(ParticleTypes.PORTAL, entity.getX(), entity.getY() + 1.2, entity.getZ(), 4, 0.2, 0.2, 0.2, 0.1);
				}

				if (slamTicks <= 1) {
					entity.getPersistentData().putDouble("sentinel_slam_phase", 3);
					entity.getPersistentData().putDouble("sentinel_slam_ticks", 5);
				}
			} else if (slamPhase == 3) {
				double startX = entity.getX();
				double startY = entity.getY();
				double startZ = entity.getZ();
				double groundY = findGroundY(world, entity);

				// Trace a heavy lightspeed sonic boom vertical column
				if (world instanceof ServerLevel level) {
					for (double sy = groundY; sy <= startY; sy += 0.5) {
						level.sendParticles(ParticleTypes.SONIC_BOOM, startX, sy, startZ, 1, 0.1, 0.1, 0.1, 0.0);
						level.sendParticles(ParticleTypes.CLOUD, startX, sy, startZ, 2, 0.15, 0.15, 0.15, 0.02);
					}
				}

				// Instant lightspeed teleport descent
				entity.teleportTo(startX, groundY, startZ);
				entity.setDeltaMovement(0, -0.05, 0); // Stick the rot to the ground and DONT make it bounce

				entity.getPersistentData().putDouble("sentinel_slam_phase", 0);
				entity.getPersistentData().putDouble("sentinel_slam_ticks", 0);
				entity.getPersistentData().putDouble("sentinel_landing_ticks", 20); // Stick recovery on ground! (1 second)

				executeCrushLandingBlast(world, entity, combatTarget, false);
			}
			handlePassengerAndGrowth(entity);
			return true;
		}
		return false;
	}

	private static boolean handleDieKickState(LevelAccessor world, Entity entity, @Nullable Entity combatTarget) {
		double dieKickPhase = entity.getPersistentData().getDouble("sentinel_die_kick_phase");
		double dieKickTicks = entity.getPersistentData().getDouble("sentinel_die_kick_ticks");

		if (dieKickPhase > 0) {
			if (dieKickTicks > 0) {
				entity.getPersistentData().putDouble("sentinel_die_kick_ticks", dieKickTicks - 1);
			}

			if (dieKickPhase == 1) {
				if (combatTarget != null) {
					lockLookAtTarget(entity, combatTarget);
				}
				// Allow natural upward momentum but restrict horizontal drift so it rises straight up
				Vec3 m = entity.getDeltaMovement();
				entity.setDeltaMovement(0.0, m.y(), 0.0);

				if (dieKickTicks <= 1) {
					entity.getPersistentData().putDouble("sentinel_die_kick_phase", 2);
					entity.getPersistentData().putDouble("sentinel_die_kick_ticks", 25); // Pause for 1 second (25 ticks)
					playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sonic_charge", 1.5F, 0.75F);
				}
			} else if (dieKickPhase == 2) {
				// Suspend completely in mid-air (play rider kick charging/animation and hold, target aiming lock)
				entity.setDeltaMovement(0, 0, 0);
				if (combatTarget != null) {
					lockLookAtTarget(entity, combatTarget);
				}
				if (world instanceof ServerLevel level) {
					level.sendParticles(ParticleTypes.PORTAL, entity.getX(), entity.getY() + 1.2, entity.getZ(), 4, 0.2, 0.2, 0.2, 0.1);
				}

				if (dieKickTicks <= 1) {
					entity.getPersistentData().putDouble("sentinel_die_kick_phase", 3);
					entity.getPersistentData().putDouble("sentinel_die_kick_ticks", 40); // Max flight duration timeout
					playHostileSound(world, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sonic_boom", 1.5F, 0.55F);
				}
			} else if (dieKickPhase == 3) {
				// Slam/slide towards the player/target diagonally
				if (combatTarget != null) {
					lockLookAtTarget(entity, combatTarget);
					Vec3 currentPos = entity.position();
					Vec3 targetPos = combatTarget.position();
					Vec3 direction = targetPos.subtract(currentPos);
					double distance = direction.length();
					if (distance > 0.1) {
						// Lock onto target's position with high diagonal speed
						Vec3 velocity = direction.normalize().scale(DIE_KICK_SPEED);
						entity.setDeltaMovement(velocity);
					}
				} else {
					// No combat target, just dive in the looking direction
					Vec3 look = entity.getLookAngle();
					entity.setDeltaMovement(look.x * DIE_KICK_SPEED, -3.5, look.z * DIE_KICK_SPEED);
				}
				entity.hasImpulse = true;

				if (world instanceof ServerLevel level) {
					level.sendParticles(ParticleTypes.GUST, entity.getX(), entity.getY() + 0.3, entity.getZ(), 3, 0.1, 0.1, 0.1, 0.05);
					level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.3, entity.getZ(), 1, 0.1, 0.1, 0.1, 0.02);
				}

				double groundY = findGroundY(world, entity);
				// Contact detector: check close horizontal/distance proximity or hit ground floor
				boolean hitTarget = false;
				if (combatTarget != null && entity.getBoundingBox().inflate(0.5).intersects(combatTarget.getBoundingBox())) {
					hitTarget = true;
				}

				if (entity.onGround() || entity.getY() <= groundY + 0.6 || hitTarget || dieKickTicks <= 1) {
					entity.teleportTo(entity.getX(), groundY, entity.getZ());
					entity.setDeltaMovement(0, -0.05, 0);

					entity.getPersistentData().putDouble("sentinel_die_kick_ticks", 0);
					entity.getPersistentData().putDouble("sentinel_landing_ticks", 20); // Freeze recovery on ground (1 second)

					executeCrushLandingBlast(world, entity, combatTarget, true);
					entity.getPersistentData().putDouble("sentinel_die_kick_phase", 0);
				}
			}
			handlePassengerAndGrowth(entity);
			return true;
		}
		return false;
	}

	private static void executeCrushLandingBlast(LevelAccessor world, Entity self, @Nullable Entity target, boolean shakeCamera) {
		if (!(world instanceof ServerLevel level)) return;
		LivingEntity ls = (self instanceof LivingEntity) ? (LivingEntity) self : null;
		double targetX = self.getX();
		double targetY = self.getY();
		double targetZ = self.getZ();

		boolean totemActive = self.getPersistentData().getBoolean("sentinel_totem_active");
		boolean isInfinity = self.getPersistentData().getBoolean("sentinel_is_infinity_totem");
		double blastRadius = totemActive ? 12.0 : 6.5;
		if (isInfinity) blastRadius = 15.0;
		double _adaptation = getAdaptationMultiplier(self);
		blastRadius *= _adaptation;
		float blastDamage = totemActive ? 55.0F : 30.0F;
		if (isInfinity) blastDamage *= 2.5F;
		blastDamage *= (float) _adaptation;

		playHostileSound(level, targetX, targetY, targetZ, "entity.generic.explode", 1.8F, 0.45F);
		if (isInfinity) {
			level.explode(self, targetX, targetY + 0.5, targetZ, 6.5F * (float) Math.min(5.0, getAdaptationMultiplier(self)), Level.ExplosionInteraction.MOB);
		}
		if (shakeCamera) {
			playHostileSound(level, targetX, targetY, targetZ, "entity.warden.sonic_boom", 1.5F, 0.6F);
		}
		playHostileSound(level, targetX, targetY, targetZ, "entity.iron_golem.attack", 2.0F, 0.5F);

		// Initialize expanding shockwave variables
		self.getPersistentData().putDouble("sentinel_shockwave_stage", 1);
		self.getPersistentData().putDouble("sentinel_shockwave_x", targetX);
		self.getPersistentData().putDouble("sentinel_shockwave_y", targetY);
		self.getPersistentData().putDouble("sentinel_shockwave_z", targetZ);

		// Epic central impact blast particles
		level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, targetX, targetY + 0.5, targetZ, totemActive ? 6 : 3, 0.5, 0.3, 0.5, 0.15);

		net.minecraft.world.level.block.state.BlockState floorState = level.getBlockState(net.minecraft.core.BlockPos.containing(targetX, targetY - 0.5, targetZ));
		if (floorState.isAir()) {
			floorState = level.getBlockState(net.minecraft.core.BlockPos.containing(targetX, targetY - 1.5, targetZ));
		}
		if (floorState.isAir()) {
			floorState = net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState();
		}
		net.minecraft.core.particles.BlockParticleOption dustPillarOptions = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.DUST_PILLAR, floorState);

		// Change cloud to a small ring of dust pillar
		int smallRingCount = totemActive ? 28 : 16;
		for (int rIndex = 0; rIndex < smallRingCount; rIndex++) {
			double ang = (2 * Math.PI / smallRingCount) * rIndex;
			double c = Math.cos(ang);
			double s = Math.sin(ang);
			double r = totemActive ? 2.0 : 1.2;
			double px = targetX + c * r;
			double pz = targetZ + s * r;
			level.sendParticles(dustPillarOptions, px, targetY + 0.2, pz, 1, 0.0, 0.1, 0.0, 0.05);
		}

		net.minecraft.core.particles.ParticleType<?> _tsdType = BuiltInRegistries.PARTICLE_TYPE.get(ResourceLocation.parse("trial_spawner_detection"));
		net.minecraft.core.particles.ParticleOptions trialSpawnerDetection = _tsdType instanceof net.minecraft.core.particles.ParticleOptions _tsdOpt ? _tsdOpt : net.minecraft.core.particles.ParticleTypes.EFFECT;

		level.sendParticles(dustPillarOptions, targetX, targetY + 0.2, targetZ, totemActive ? 90 : 55, 1.5, 0.6, 1.5, 0.25);
		level.sendParticles(trialSpawnerDetection, targetX, targetY + 0.2, targetZ, totemActive ? 90 : 55, 1.5, 0.6, 1.5, 0.25);

		if (shakeCamera) {
			// Citadel Camera Shake Delivery (Method 1)
			sendCameraShake(totemActive ? 1.5F : 1.0F, totemActive ? 25 : 15, totemActive ? 30.0F : 20.0F);
		}

		java.util.List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, self.getBoundingBox().inflate(blastRadius), e -> e != self);
		for (LivingEntity victim : targets) {
			String dmgType = self.getPersistentData().getDouble("sentinel_die_kick_phase") > 0 ? "rot_die_rider_kick" : "rot_seismic_slam";
			victim.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:" + dmgType))), self), blastDamage * (float) getAdaptationMultiplier(self));
			Vec3 push = victim.position().subtract(self.position()).multiply(1.0, 0.0, 1.0);
			double horizontalDist = Math.sqrt(push.x * push.x + push.z * push.z);
			if (horizontalDist < 0.1) {
				push = self.getLookAngle().multiply(1.0, 0.0, 1.0);
				horizontalDist = Math.sqrt(push.x * push.x + push.z * push.z);
			}
			if (horizontalDist > 0.01) {
				push = new Vec3(push.x / horizontalDist, 0, push.z / horizontalDist);
			} else {
				push = new Vec3(1, 0, 0);
			}
			double pushMult = totemActive ? 5.5 : 3.5;
			double pushUp = totemActive ? 1.4 : 0.95;
			if (isInfinity) {
				pushMult = 9.5;
				pushUp = 2.2;
			}
			applyKnockbackAndSync(victim, push.x * pushMult, pushUp, push.z * pushMult);
		}
	}

	private static double findGroundY(LevelAccessor world, Entity entity) {
		BlockPos pos = entity.blockPosition();
		int minY = world.getMinBuildHeight();
		int startY = pos.getY();
		int maxDist = Math.max(64, startY - minY + 2);
		for (int i = 0; i < maxDist; i++) {
			BlockPos belowPos = pos.below(i);
			if (belowPos.getY() < minY) {
				return minY + 1.0;
			}
			BlockState state = world.getBlockState(belowPos);
			if (state.isCollisionShapeFullBlock(world, belowPos) || !state.getFluidState().isEmpty()) {
				return belowPos.getY() + 1.0;
			}
		}
		return minY + 1.0;
	}

	private static boolean isHighAboveGround(Entity entity, double height) {
		LevelAccessor world = entity.level();
		BlockPos pos = entity.blockPosition();
		for (int i = 1; i <= (int) Math.ceil(height); i++) {
			BlockState state = world.getBlockState(pos.below(i));
			if (state.isCollisionShapeFullBlock(world, pos.below(i))) {
				return false;
			}
		}
		return true;
	}

	private static void breakBlocksBehindTarget(LevelAccessor world, Entity target, Vec3 push, boolean totemActive) {
		if (!(world instanceof ServerLevel level) || target == null) return;
		double height = target.getBbHeight();
		Vec3 targetPos = target.position();
		Vec3 behindDir = push.normalize();
		double[] distances = totemActive ? new double[]{0.8, 1.4, 2.0, 2.6, 3.2, 3.8} : new double[]{0.8, 1.4};
		for (double dist : distances) {
			Vec3 pathPos = targetPos.add(behindDir.scale(dist));
			for (double dy = 0; dy < height + 0.5; dy += 0.9) {
				if (totemActive) {
					// Break in a 3x3 cylinder centered around the path position to carve a massive tunnel!
					for (int dx = -1; dx <= 1; dx++) {
						for (int dz = -1; dz <= 1; dz++) {
							BlockPos bp = BlockPos.containing(pathPos.x + dx, pathPos.y + dy, pathPos.z + dz);
							BlockState state = level.getBlockState(bp);
							float hardness = state.getDestroySpeed(level, bp);
							if (hardness >= 0.0F && hardness <= 50.0F && !state.isAir()) {
								level.destroyBlock(bp, false);
								level.sendParticles(ParticleTypes.EXPLOSION, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 1, 0.1, 0.1, 0.1, 0.02);
							}
						}
					}
				} else {
					BlockPos bp = BlockPos.containing(pathPos.x, pathPos.y + dy, pathPos.z);
					BlockState state = level.getBlockState(bp);
					float hardness = state.getDestroySpeed(level, bp);
					if (hardness >= 0.0F && hardness <= 50.0F && !state.isAir()) {
						level.destroyBlock(bp, false);
						level.sendParticles(ParticleTypes.CRIT, bp.getX() + 0.5, bp.getY() + 0.5, bp.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.02);
					}
				}
			}
		}
	}

	private static void sendCameraShake(float intensity, int duration, float range) {
		try {
			Class<?> msgClass = Class.forName("com.github.alexmodguy.citadel.server.message.CameraShakeMessage");
			java.lang.reflect.Constructor<?> constructor = msgClass.getConstructor(float.class, int.class, float.class);
			Object msgInstance = constructor.newInstance(intensity, duration, range);

			Class<?> citadelClass = Class.forName("com.github.alexmodguy.citadel.Citadel");
			java.lang.reflect.Method sendMethod = citadelClass.getMethod("sendMSGToAll", Object.class);
			sendMethod.invoke(null, msgInstance);
		} catch (Exception e) {
			// Other exceptions, safely ignore
		}
	}

	private static boolean handleScanningState(Entity entity) {
		double scanningTicks = entity.getPersistentData().getDouble("sentinel_scanning_ticks");
		if (scanningTicks > 0) {
			entity.getPersistentData().putDouble("sentinel_scanning_ticks", scanningTicks - 1.0);
			entity.setDeltaMovement(0, entity.getDeltaMovement().y(), 0);
			if (entity instanceof Mob mob) {
				mob.getNavigation().stop();
				
				double baseYaw = entity.getPersistentData().getDouble("sentinel_scanning_base_yaw");
				double maxTicks = entity.getPersistentData().getDouble("sentinel_scan_max_ticks");
				if (maxTicks <= 0.1) maxTicks = 60.0;
				double elapsed = maxTicks - scanningTicks;
				double fraction = elapsed / maxTicks;

				double targetOffset = 0;
				double targetPitch = 0;
				float lerpFactor = 0.18F;

				if (entity.getPersistentData().getBoolean("sentinel_reacting_to_attacker")) {
					targetOffset = 0;
					targetPitch = entity.getPersistentData().getDouble("sentinel_scan_target_pitch");
					lerpFactor = 0.35F;
				} else {
					if (fraction < 0.25) {
						targetOffset = 0;
						targetPitch = 2.0;
					} else if (fraction < 0.55) {
						targetOffset = -55.0;
						targetPitch = -8.0;
					} else if (fraction < 0.85) {
						targetOffset = 55.0;
						targetPitch = 12.0;
					} else {
						targetOffset = 0;
						targetPitch = 0;
					}
				}

				float currentY = mob.getYRot();
				float targetY = (float) (baseYaw + targetOffset);
				float lerpedY = Mth.rotLerp(lerpFactor, currentY, targetY);
				
				mob.setYRot(lerpedY);
				mob.setYHeadRot(lerpedY);
				mob.yBodyRot = lerpedY;

				float currentX = mob.getXRot();
				float targetX = (float) targetPitch;
				float lerpedX = Mth.lerp(lerpFactor, currentX, targetX);
				mob.setXRot(lerpedX);
				
				double lx = mob.getX() - Math.sin(Math.toRadians(lerpedY)) * 5.0;
				double ly = mob.getEyeY() + Math.sin(Math.toRadians(-lerpedX)) * 5.0;
				double lz = mob.getZ() + Math.cos(Math.toRadians(lerpedY)) * 5.0;
				mob.getLookControl().setLookAt(lx, ly, lz, 45.0F, 45.0F);
			}
			return true;
		}
		entity.getPersistentData().putBoolean("sentinel_reacting_to_attacker", false);
		return false;
	}

	private static boolean handleCustomCombos(LevelAccessor world, Entity entity, @Nullable Entity combatTarget) {
		double cc1 = entity.getPersistentData().getDouble("sentinel_cc1_stage");
		double cc2 = entity.getPersistentData().getDouble("sentinel_cc2_stage");
		double cc3 = entity.getPersistentData().getDouble("sentinel_cc3_stage");
		double cc4 = entity.getPersistentData().getDouble("sentinel_cc4_stage");
		double cc5 = entity.getPersistentData().getDouble("sentinel_cc5_stage");
		double slamPhase = entity.getPersistentData().getDouble("sentinel_slam_phase");
		double judgmentTicks = entity.getPersistentData().getDouble("sentinel_judgment_ticks");
		double dieKickPhase = entity.getPersistentData().getDouble("sentinel_die_kick_phase");

		if (cc1 <= 0 && cc2 <= 0 && cc3 <= 0 && cc4 <= 0 && cc5 <= 0 && slamPhase <= 0 && judgmentTicks <= 0 && dieKickPhase <= 0) {
			entity.getPersistentData().putInt("sentinel_combo_target_id", 0);
			return false;
		}

		if (combatTarget == null) {
			// If target is lost, reset any active custom combos
			entity.getPersistentData().putDouble("sentinel_cc1_stage", 0);
			entity.getPersistentData().putDouble("sentinel_cc2_stage", 0);
			entity.getPersistentData().putDouble("sentinel_cc3_stage", 0);
			entity.getPersistentData().putDouble("sentinel_cc4_stage", 0);
			entity.getPersistentData().putDouble("sentinel_cc5_stage", 0);
			entity.getPersistentData().putInt("sentinel_combo_target_id", 0);
			return false;
		}

		int storedId = entity.getPersistentData().getInt("sentinel_combo_target_id");
		if (storedId == 0) {
			entity.getPersistentData().putInt("sentinel_combo_target_id", combatTarget.getId());
		}

		// If standard skill subprocesses are active, let them tick naturally
		if (entity.getPersistentData().getDouble("sentinel_slam_phase") > 0 
			|| entity.getPersistentData().getDouble("sentinel_judgment_ticks") > 0 
			|| entity.getPersistentData().getDouble("sentinel_die_kick_phase") > 0) {
			return false;
		}

		// We have an active custom combo!
		lockLookAtTarget(entity, combatTarget);

		// Disable default movement behavior during active combo executing stage
		if (entity instanceof Mob mob) {
			mob.getNavigation().stop();
		}

		// BLINK TO TARGET IF FAR AWAY at the very start of the combo! (for chat trigger distance-bridging)
		if ((cc1 == 1 || cc2 == 1 || cc3 == 1 || cc4 == 1 || cc5 == 1) && entity.distanceTo(combatTarget) > 5.0) {
			if (world instanceof ServerLevel level) {
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, entity.getX(), entity.getY() + 1.0, entity.getZ(), 20, 0.4, 0.8, 0.4, 0.1);
				playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.enderman.teleport", 1.2F, 0.5F);
			}
			
			// Teleport right next to the target
			Vec3 dir = combatTarget.position().subtract(entity.position()).normalize();
			double targetX = combatTarget.getX() - dir.x * 2.5;
			double targetY = combatTarget.getY();
			double targetZ = combatTarget.getZ() - dir.z * 2.5;
			entity.teleportTo(targetX, targetY, targetZ);
			
			if (world instanceof ServerLevel level) {
				level.sendParticles(ParticleTypes.REVERSE_PORTAL, targetX, targetY + 1.0, targetZ, 20, 0.4, 0.8, 0.4, 0.1);
				playHostileSound(level, targetX, targetY, targetZ, "entity.enderman.teleport", 1.2F, 0.55F);
			}
		}

		boolean totemActive = entity.getPersistentData().getBoolean("sentinel_totem_active");

		if (cc1 > 0) {
			// ==========================================
			// COMBO 1: Triple Threat (Crush -> Judgment -> Die)
			// ==========================================
			double cc1Ticks = entity.getPersistentData().getDouble("sentinel_cc1_ticks");
			if (cc1 == 1) {
				// Init phase: Trigger the real Ground Crush (slam move)
				entity.setDeltaMovement(0.0, 1.9, 0.0);
				entity.hasImpulse = true;
				if (world instanceof ServerLevel level) {
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
					level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.5, entity.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
				}

				entity.getPersistentData().putDouble("sentinel_slam_phase", 1);
				entity.getPersistentData().putDouble("sentinel_slam_ticks", 22);

				entity.getPersistentData().putDouble("sentinel_cc1_stage", 2);
				entity.getPersistentData().putDouble("sentinel_cc1_ticks", 2); // short delay
			} else if (cc1 == 2) {
				// Wait for standard Ground Crush (slam_phase back to 0) to complete
				slamPhase = entity.getPersistentData().getDouble("sentinel_slam_phase");
				if (slamPhase == 0) {
					// Once slam is complete (and player is flung), initiate judgment dropkick state!
					entity.getPersistentData().putDouble("sentinel_judgment_ticks", 60);
					
					// Clear landing freeze so the entity can execute dropkick immediately
					entity.getPersistentData().putDouble("sentinel_landing_ticks", 0);

					entity.getPersistentData().putDouble("sentinel_cc1_stage", 3);
					entity.getPersistentData().putDouble("sentinel_cc1_ticks", 40); // safety max ticks to check impact
				}
			} else if (cc1 == 3) {
				// Wait for dropkick completion
				judgmentTicks = entity.getPersistentData().getDouble("sentinel_judgment_ticks");
				if (judgmentTicks == 0) {
					// Dropkick landed or complete! Fling player high again for follow up!
					Vec3 pushVec = combatTarget.position().subtract(entity.position()).normalize();
					double pushForce = totemActive ? 3.5 : 2.5;
					applyKnockbackAndSync(combatTarget, pushVec.x * pushForce, 1.45, pushVec.z * pushForce);

					entity.getPersistentData().putDouble("sentinel_cc1_stage", 4);
					entity.getPersistentData().putDouble("sentinel_cc1_ticks", 20); // wait 1s (20 ticks) of landing freeze/target rise
				}
			} else if (cc1 == 4) {
				// Wait for 20 ticks target rise to finish (landing freeze recovery)
				if (cc1Ticks == 0) {
					// Follow up with Die Rider Kick!
					entity.setDeltaMovement(0.0, 1.95, 0.0);
					entity.hasImpulse = true;

					entity.getPersistentData().putDouble("sentinel_die_kick_phase", 1);
					entity.getPersistentData().putDouble("sentinel_die_kick_ticks", 22);
					entity.getPersistentData().putDouble("sentinel_landing_ticks", 0); // cancel freeze

					if (world instanceof ServerLevel level) {
						playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
						level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.5, entity.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
					}
					entity.getPersistentData().putDouble("sentinel_cc1_stage", 5);
				}
			} else if (cc1 == 5) {
				// Wait for Die Rider Kick to complete
				dieKickPhase = entity.getPersistentData().getDouble("sentinel_die_kick_phase");
				double landingTicks = entity.getPersistentData().getDouble("sentinel_landing_ticks");
				if (dieKickPhase == 0 && landingTicks == 0) {
					// Completed Rider Kick completely! Put on cooldown
					double cd = totemActive ? COMBO_TRIPLE_THREAT_CD_TOTEM : COMBO_TRIPLE_THREAT_CD;
					entity.getPersistentData().putDouble("sentinel_cc1_cd", cd);
					entity.getPersistentData().putDouble("sentinel_cc1_stage", 0);
				}
				handlePassengerAndGrowth(entity);
			}
			handlePassengerAndGrowth(entity);
			return true;
		}

		if (cc2 > 0) {
			// ==========================================
			// COMBO 2: High Sky Slam (Teleport -> Uppercut -> Launch -> Peak Rise Leap -> Overhead Slam -> Crush/Rider Kick)
			// ==========================================
			double cc2Ticks = entity.getPersistentData().getDouble("sentinel_cc2_ticks");
			double cc2Air = entity.getPersistentData().getDouble("sentinel_cc2_air_ticks");

			if (cc2 == 1) {
				if (cc2Ticks == 0.0) {
					// Step 1: Teleport in front of target safely and initialize uppercut timing settings
					Vec3 look = combatTarget.getLookAngle().normalize();
					double tx = combatTarget.getX() + look.x * 1.8;
					double tz = combatTarget.getZ() + look.z * 1.8;
					double ty = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) tx, (int) tz);
					
					teleportEntity(entity, tx, ty, tz);
					lockLookAtTarget(entity, combatTarget);
					
					if (world instanceof ServerLevel level) {
						playTeleportEffects(level, entity, entity.getX(), entity.getY(), entity.getZ());
						playTeleportEffects(level, entity, tx, ty, tz);
						playHostileSound(level, tx, ty, tz, "entity.warden.snarl", 1.5F, 1.3F);
					}
					
					entity.getPersistentData().putDouble("sentinel_cc2_ticks", UPPERCUT_TOTAL_TICKS);
					entity.getPersistentData().putBoolean("is_uppercutting", true);
					entity.getPersistentData().putBoolean("sentinel_cc2_launched", false);
				} else {
					// Look lock during windup and swing
					lockLookAtTarget(entity, combatTarget);
					
					// Step 2: Uppercut execution and vertical launch at the exact strike frame tick
					boolean launched = entity.getPersistentData().getBoolean("sentinel_cc2_launched");
					if (cc2Ticks <= UPPERCUT_LAUNCH_TICK && !launched) {
						entity.getPersistentData().putBoolean("sentinel_cc2_launched", true);
						if (entity instanceof LivingEntity ls) ls.swing(InteractionHand.MAIN_HAND, true);
						
						combatTarget.hurt(entity instanceof LivingEntity ls ? ls.damageSources().mobAttack(ls) : new DamageSource(world.holderOrThrow(DamageTypes.MOB_ATTACK)), (float) UPPERCUT_DAMAGE * (float) getAdaptationMultiplier(entity));
						
						double launchHeightVel = 2.8 + Math.random() * 1.0; // Launches 25-45 blocks high
						applyKnockbackAndSync(combatTarget, 0.0, launchHeightVel, 0.0);

						if (world instanceof ServerLevel level) {
							level.sendParticles(ParticleTypes.CLOUD, combatTarget.getX(), combatTarget.getY() + 0.2, combatTarget.getZ(), 15, 0.4, 0.4, 0.4, 0.15);
							level.sendParticles(ParticleTypes.EXPLOSION, combatTarget.getX(), combatTarget.getY() + 0.5, combatTarget.getZ(), 3, 0.2, 0.2, 0.2, 0.1);
							playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.8F, 0.6F);
							playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.generic.explode", 1.2F, 1.4F);
						}
					}
					
					// Transition out of Uppercut stage once animation completes
					if (cc2Ticks <= 1) {
						entity.getPersistentData().putBoolean("is_uppercutting", false);
						if (entity.getPersistentData().getBoolean("is_uppercut_standalone")) {
							// Exiting standalone uppercut
							entity.getPersistentData().putBoolean("is_uppercut_standalone", false);
							entity.getPersistentData().putDouble("sentinel_cc2_stage", 0);
						} else {
							// Proceed to High Sky Slam follow-ups
							entity.getPersistentData().putDouble("sentinel_cc2_air_ticks", 1);
							entity.getPersistentData().putDouble("sentinel_cc2_stage", 2);
						}
					}
				}
			} else if (cc2 == 2) {
				entity.getPersistentData().putDouble("sentinel_cc2_air_ticks", cc2Air + 1);
				
				// Make the Rot leap up alongside the target at start of Rise phase
				if (cc2Air == 1) {
					double leapVel = 2.0; // Generous leap upward
					entity.setDeltaMovement(entity.getDeltaMovement().x(), leapVel, entity.getDeltaMovement().z());
					entity.hasImpulse = true;
					if (world instanceof ServerLevel level) {
						playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.2F, 0.7F);
						level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.2, entity.getZ(), 10, 0.3, 0.3, 0.3, 0.1);
					}
				}
				
				lockLookAtTarget(entity, combatTarget);
				
				// Wait for target to reach peak height (velocity close to 0 or downward)
				if (cc2Air >= 15 && combatTarget.getDeltaMovement().y() <= 0.1) {
					// Teleport directly to peak level right above the target
					entity.teleportTo(combatTarget.getX(), combatTarget.getY() + 1.5, combatTarget.getZ());
					entity.setDeltaMovement(0.0, -0.1, 0.0); // Slightly downward tilt

					// Trigger actual Overhead Slam
					entity.getPersistentData().putDouble("rot_overhead_ticks", OVERHEAD_TOTAL_TICKS);
					entity.getPersistentData().putString("overhead_target_uuid", combatTarget.getUUID().toString());

					// Reset punches
					entity.getPersistentData().putBoolean("is_left_punching", false);
					entity.getPersistentData().putBoolean("is_right_punching", false);
					if (entity instanceof RotEntity rot) {
						rot.getEntityData().set(RotEntity.DATA_is_left_punching, false);
						rot.getEntityData().set(RotEntity.DATA_is_right_punching, false);
					}

					entity.getPersistentData().putDouble("sentinel_cc2_stage", 3);
				}
			} else if (cc2 == 3) {
				if (combatTarget == null || !combatTarget.isAlive()) {
					entity.getPersistentData().putDouble("sentinel_cc2_stage", 0);
					entity.getPersistentData().putDouble("rot_overhead_ticks", 0);
					return true;
				}

				handleOverheadState(world, entity, combatTarget);

				double overheadTicks = entity.getPersistentData().getDouble("rot_overhead_ticks");
				if (overheadTicks <= 0) {
					// Follow up: Either Crush Slam or Die Rider Kick (50% chance)
					if (Math.random() < 0.5) {
						// Option A: Crush Slam (Leap + Ground Crush)
						entity.setDeltaMovement(0.0, 1.9, 0.0);
						entity.hasImpulse = true;
						if (world instanceof ServerLevel level) {
							playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
							level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.5, entity.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
						}
						entity.getPersistentData().putDouble("sentinel_slam_phase", 1);
						entity.getPersistentData().putDouble("sentinel_slam_ticks", 22);
					} else {
						// Option B: Die Rider Kick!
						entity.getPersistentData().putDouble("sentinel_die_kick_phase", 1);
						entity.getPersistentData().putDouble("sentinel_die_kick_ticks", 22);
						entity.setDeltaMovement(0.0, 1.95, 0.0);
						entity.hasImpulse = true;
						if (world instanceof ServerLevel level) {
							playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
							level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.5, entity.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
						}
					}

					double cd = totemActive ? COMBO_HIGH_SKY_SLAM_CD_TOTEM : COMBO_HIGH_SKY_SLAM_CD;
					entity.getPersistentData().putDouble("sentinel_cc2_cd", cd);
					entity.getPersistentData().putDouble("sentinel_cc2_stage", 0);
				}
			}
			handlePassengerAndGrowth(entity);
			return true;
		}

		if (cc3 > 0) {
			// ==========================================
			// COMBO 3: Punch + Dropkick Combo
			// ==========================================
			double cc3Ticks = entity.getPersistentData().getDouble("sentinel_cc3_ticks");
			if (cc3 == 1) {
				// Swing hands
				if (entity instanceof LivingEntity ls) ls.swing(InteractionHand.MAIN_HAND, true);
				
				boolean lastHandLeft = entity.getPersistentData().getBoolean("sentinel_punch_hand_toggle");
				entity.getPersistentData().putBoolean("sentinel_punch_hand_toggle", !lastHandLeft);
				if (lastHandLeft) {
					entity.getPersistentData().putDouble("sentinel_heavy_left_punch_ticks", HEAVY_PUNCH_TOTAL_TICKS);
					entity.getPersistentData().putDouble("sentinel_heavy_right_punch_ticks", 0);
				} else {
					entity.getPersistentData().putDouble("sentinel_heavy_right_punch_ticks", HEAVY_PUNCH_TOTAL_TICKS);
					entity.getPersistentData().putDouble("sentinel_heavy_left_punch_ticks", 0);
				}
				entity.getPersistentData().putDouble("sentinel_left_punch_ticks", 0);
				entity.getPersistentData().putDouble("sentinel_right_punch_ticks", 0);

				entity.getPersistentData().putDouble("sentinel_cc3_ticks", HEAVY_PUNCH_TOTAL_TICKS + 5); // 26 ticks windup + 30 ticks recovery gap + 5 ticks cushion
				entity.getPersistentData().putDouble("sentinel_cc3_stage", 2);
			} else if (cc3 == 2) {
				// Wait gap
				if (cc3Ticks == 0) {
					// Follow up with Dropkick!
					entity.getPersistentData().putDouble("sentinel_judgment_ticks", 60);
					entity.getPersistentData().putDouble("sentinel_cc3_stage", 3);
				}
			} else if (cc3 == 3) {
				// Wait for Judgment Dropkick to execute and land
				judgmentTicks = entity.getPersistentData().getDouble("sentinel_judgment_ticks");
				double landingTicks = entity.getPersistentData().getDouble("sentinel_landing_ticks");
				if (judgmentTicks == 0 && landingTicks == 0) {
					// Completed combo!
					double cd = totemActive ? COMBO_PUNCH_DROPKICK_CD_TOTEM : COMBO_PUNCH_DROPKICK_CD;
					entity.getPersistentData().putDouble("sentinel_cc3_cd", cd);
					entity.getPersistentData().putDouble("sentinel_cc3_stage", 0);
				}
				handlePassengerAndGrowth(entity);
			}
			handlePassengerAndGrowth(entity);
			return true;
		}

		if (cc4 > 0) {
			// ==========================================
			// COMBO 4: Punch + Rider Kick Combo
			// ==========================================
			double cc4Ticks = entity.getPersistentData().getDouble("sentinel_cc4_ticks");
			if (cc4 == 1) {
				// Swing hands
				if (entity instanceof LivingEntity ls) ls.swing(InteractionHand.MAIN_HAND, true);
				
				boolean lastHandLeft = entity.getPersistentData().getBoolean("sentinel_punch_hand_toggle");
				entity.getPersistentData().putBoolean("sentinel_punch_hand_toggle", !lastHandLeft);
				if (lastHandLeft) {
					entity.getPersistentData().putDouble("sentinel_heavy_left_punch_ticks", HEAVY_PUNCH_TOTAL_TICKS);
					entity.getPersistentData().putDouble("sentinel_heavy_right_punch_ticks", 0);
				} else {
					entity.getPersistentData().putDouble("sentinel_heavy_right_punch_ticks", HEAVY_PUNCH_TOTAL_TICKS);
					entity.getPersistentData().putDouble("sentinel_heavy_left_punch_ticks", 0);
				}
				entity.getPersistentData().putDouble("sentinel_left_punch_ticks", 0);
				entity.getPersistentData().putDouble("sentinel_right_punch_ticks", 0);

				entity.getPersistentData().putDouble("sentinel_cc4_ticks", HEAVY_PUNCH_TOTAL_TICKS + 5); // 26 ticks windup + 30 ticks recovery gap + 5 ticks cushion
				entity.getPersistentData().putDouble("sentinel_cc4_stage", 2);
			} else if (cc4 == 2) {
				// Wait gap
				if (cc4Ticks == 0) {
					// Follow up with Die Rider Kick!
					entity.setDeltaMovement(0.0, 1.95, 0.0);
					entity.hasImpulse = true;

					entity.getPersistentData().putDouble("sentinel_die_kick_phase", 1);
					entity.getPersistentData().putDouble("sentinel_die_kick_ticks", 22);
					entity.getPersistentData().putDouble("sentinel_landing_ticks", 0); // cancel freeze

					if (world instanceof ServerLevel level) {
						playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
						level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.5, entity.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
					}
					entity.getPersistentData().putDouble("sentinel_cc4_stage", 3);
				}
			} else if (cc4 == 3) {
				// Wait for Rider Kick completion
				dieKickPhase = entity.getPersistentData().getDouble("sentinel_die_kick_phase");
				double landingTicks = entity.getPersistentData().getDouble("sentinel_landing_ticks");
				if (dieKickPhase == 0 && landingTicks == 0) {
					// Completed combo!
					double cd = totemActive ? COMBO_PUNCH_RIDER_KICK_CD_TOTEM : COMBO_PUNCH_RIDER_KICK_CD;
					entity.getPersistentData().putDouble("sentinel_cc4_cd", cd);
					entity.getPersistentData().putDouble("sentinel_cc4_stage", 0);
				}
				handlePassengerAndGrowth(entity);
			}
			handlePassengerAndGrowth(entity);
			return true;
		}

		if (cc5 > 0) {
			// ==========================================
			// COMBO 5: Heavenly Repentance Plus
			// ==========================================
			double cc5Ticks = entity.getPersistentData().getDouble("sentinel_cc5_ticks");
			if (cc5 == 1) {
				// Swing hands
				if (entity instanceof LivingEntity ls) ls.swing(InteractionHand.MAIN_HAND, true);
				
				// Uppercut launcher / hard knockback
				Vec3 knockDir = combatTarget.position().subtract(entity.position()).normalize();
				double launchHeightVel = 1.35; // launched high in the air
				applyKnockbackAndSync(combatTarget, knockDir.x * 2.2, launchHeightVel, knockDir.z * 2.2);

				// Deal punch damage
				if (world instanceof ServerLevel level) combatTarget.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_consecutive_punches"))), entity), (float) MELEE_PUNCH_DAMAGE * (float) getAdaptationMultiplier(entity));

				if (world instanceof ServerLevel level) {
					level.sendParticles(ParticleTypes.CRIT, combatTarget.getX(), combatTarget.getY() + 0.8, combatTarget.getZ(), 15, 0.3, 0.3, 0.3, 0.2);
					level.sendParticles(ParticleTypes.SWEEP_ATTACK, combatTarget.getX(), combatTarget.getY() + 1.0, combatTarget.getZ(), 2, 0.2, 0.2, 0.2, 0.0);
					level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, combatTarget.getX(), combatTarget.getY() + 1.0, combatTarget.getZ(), 5, 0.2, 0.2, 0.2, 0.05);
					// Heavy punch special pitched-down sound mix!
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.6F, 0.45F);
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.generic.explode", 0.8F, 0.45F);
					playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.warden.sonic_boom", 0.6F, 0.4F);
				}

				entity.getPersistentData().putDouble("sentinel_cc5_ticks", 15); // slightly shorter gap (0.75s) to make it feel more connected!
				entity.getPersistentData().putDouble("sentinel_cc5_stage", 2);
			} else if (cc5 == 2) {
				// Wait gap
				if (cc5Ticks == 0) {
					// Follow up with Dropkick!
					entity.getPersistentData().putDouble("sentinel_judgment_ticks", 60);
					entity.getPersistentData().putDouble("sentinel_cc5_stage", 3);
				}
			} else if (cc5 == 3) {
				// Wait for Judgment Dropkick to execute and land/complete
				judgmentTicks = entity.getPersistentData().getDouble("sentinel_judgment_ticks");
				double landingTicks = entity.getPersistentData().getDouble("sentinel_landing_ticks");
				if (judgmentTicks == 0 && landingTicks == 0) {
					// Dropkick landed or finished! Check if target is still in the air.
					if (!combatTarget.onGround() && combatTarget.getY() > findGroundY(world, combatTarget) + 1.0) {
						// Teleport directly to peak level right above the target
						entity.teleportTo(combatTarget.getX(), combatTarget.getY() + 1.5, combatTarget.getZ());
						entity.setDeltaMovement(0.0, -0.1, 0.0); // Slightly downward tilt

						// Trigger actual Overhead Slam
						entity.getPersistentData().putDouble("rot_overhead_ticks", OVERHEAD_TOTAL_TICKS);
						entity.getPersistentData().putString("overhead_target_uuid", combatTarget.getUUID().toString());

						// Reset punches
						entity.getPersistentData().putBoolean("is_left_punching", false);
						entity.getPersistentData().putBoolean("is_right_punching", false);
						if (entity instanceof RotEntity rot) {
							rot.getEntityData().set(RotEntity.DATA_is_left_punching, false);
							rot.getEntityData().set(RotEntity.DATA_is_right_punching, false);
						}

						entity.getPersistentData().putDouble("sentinel_cc5_stage", 4);
					} else {
						// Target is not in the air! Finish combo normally
						double cd = totemActive ? COMBO_HEAVENLY_REPENTANCE_PLUS_CD_TOTEM : COMBO_HEAVENLY_REPENTANCE_PLUS_CD;
						entity.getPersistentData().putDouble("sentinel_cc5_cd", cd);
						entity.getPersistentData().putDouble("sentinel_cc5_stage", 0);
					}
				}
			} else if (cc5 == 4) {
				// Overhead Slam executes
				handleOverheadState(world, entity, combatTarget);

				double overheadTicks = entity.getPersistentData().getDouble("rot_overhead_ticks");
				if (overheadTicks <= 0) {
					// Once overhead hit completes, check when the target is near the ground to do the follow-up
					if (combatTarget.onGround() || combatTarget.getY() <= entity.getY() - 1.5) {
						if (Math.random() < 0.5) {
							// Option A: Crush Slam (Leap + Ground Crush)
							entity.setDeltaMovement(0.0, 1.9, 0.0);
							entity.hasImpulse = true;
							if (world instanceof ServerLevel level) {
								playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
								level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.5, entity.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
							}
							entity.getPersistentData().putDouble("sentinel_slam_phase", 1);
							entity.getPersistentData().putDouble("sentinel_slam_ticks", 22);
						} else {
							// Option B: Die Rider Kick!
							entity.getPersistentData().putDouble("sentinel_die_kick_phase", 1);
							entity.getPersistentData().putDouble("sentinel_die_kick_ticks", 22);
							entity.setDeltaMovement(0.0, 1.95, 0.0);
							entity.hasImpulse = true;
							if (world instanceof ServerLevel level) {
								playHostileSound(level, entity.getX(), entity.getY(), entity.getZ(), "entity.iron_golem.attack", 1.5F, 0.8F);
								level.sendParticles(ParticleTypes.CLOUD, entity.getX(), entity.getY() + 0.5, entity.getZ(), 10, 0.2, 0.2, 0.2, 0.05);
							}
						}

						double cd = totemActive ? COMBO_HEAVENLY_REPENTANCE_PLUS_CD_TOTEM : COMBO_HEAVENLY_REPENTANCE_PLUS_CD;
						entity.getPersistentData().putDouble("sentinel_cc5_cd", cd);
						entity.getPersistentData().putDouble("sentinel_cc5_stage", 0);
					}
				}
			}
			handlePassengerAndGrowth(entity);
			return true;
		}

		return false;
	}

	private static void executeArmorRipChoke(LevelAccessor world, Entity entity, @Nullable Entity combatTarget, int tickRemaining) {
		if (combatTarget == null || !combatTarget.isAlive()) {
			entity.getPersistentData().putDouble("rot_armor_rip_ticks", 0);
			entity.getPersistentData().putBoolean("is_armor_ripping", false);
			return;
		}

		if (combatTarget.level() != entity.level() || combatTarget.position().distanceTo(entity.position()) > ARMOR_RIP_MAX_DISTANCE) {
			entity.getPersistentData().putDouble("rot_armor_rip_ticks", 0);
			entity.getPersistentData().putBoolean("is_armor_ripping", false);
			return;
		}

		double lockedYaw = entity.getPersistentData().getDouble("rot_choke_locked_yaw");
		if (entity instanceof Mob mob) {
			mob.getNavigation().stop();
			mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
			mob.setYRot((float) lockedYaw);
			mob.setYHeadRot((float) lockedYaw);
			mob.yBodyRot = (float) lockedYaw;
		}

		if (combatTarget instanceof LivingEntity livTarget) {
			livTarget.setYRot((float) (lockedYaw + 180.0));
			livTarget.setYHeadRot((float) (lockedYaw + 180.0));
			livTarget.yBodyRot = (float) (lockedYaw + 180.0);
		} else {
			combatTarget.setYRot((float) (lockedYaw + 180.0));
		}

		// Calculate hold position: in front of the rot at some blocks away, and offset to the right a little of the target
		double dx = -Math.sin(Math.toRadians(lockedYaw));
		double dz = Math.cos(Math.toRadians(lockedYaw));
		double right_dx = Math.cos(Math.toRadians(lockedYaw));
		double right_dz = Math.sin(Math.toRadians(lockedYaw));

		double holdDistance = ARMOR_RIP_HOLD_DISTANCE;
		double rightOffset = ARMOR_RIP_RIGHT_OFFSET; // Shift to the right a little of the target
		double targetHoldX = entity.getX() + dx * holdDistance + right_dx * rightOffset;
		double targetHoldY = entity.getY() + ARMOR_RIP_HEIGHT_OFFSET; // height of left/right arm hold
		double targetHoldZ = entity.getZ() + dz * holdDistance + right_dz * rightOffset;

		double pullX = targetHoldX - combatTarget.getX();
		double pullY = targetHoldY - combatTarget.getY();
		double pullZ = targetHoldZ - combatTarget.getZ();
		double distToHold = Math.sqrt(pullX * pullX + pullY * pullY + pullZ * pullZ);

		if (distToHold > 0.02) {
			double velX = pullX * 0.8;
			double velY = pullY * 0.8;
			double velZ = pullZ * 0.8;
			combatTarget.setDeltaMovement(velX, velY, velZ);
		} else {
			combatTarget.setDeltaMovement(0, 0.01, 0);
		}
		combatTarget.fallDistance = 0.0F;
		combatTarget.hasImpulse = true;
		combatTarget.hurtMarked = true;

		// Disable shields if target is blocking
		if (combatTarget instanceof LivingEntity livTarget && livTarget.isBlocking()) {
			if (livTarget instanceof Player player) {
				player.getCooldowns().addCooldown(net.minecraft.world.item.Items.SHIELD, 100);
				player.stopUsingItem();
				if (world instanceof ServerLevel level) {
					level.broadcastEntityEvent(player, (byte) 30);
				}
			} else {
				livTarget.stopUsingItem();
			}
		}

		// Armor breaking logic & periodic tick damage
		if (combatTarget instanceof LivingEntity livTarget) {
			java.util.List<net.minecraft.world.entity.EquipmentSlot> equippedArmorSlots = new java.util.ArrayList<>();
			for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
				if (slot.isArmor() && !livTarget.getItemBySlot(slot).isEmpty()) {
					equippedArmorSlots.add(slot);
				}
			}

			if (!equippedArmorSlots.isEmpty()) {
				int randomIndex = net.minecraft.util.RandomSource.create().nextInt(equippedArmorSlots.size());
				net.minecraft.world.entity.EquipmentSlot randomSlot = equippedArmorSlots.get(randomIndex);
				ItemStack armorPiece = livTarget.getItemBySlot(randomSlot);

				boolean isIndestructible = armorPiece.getMaxDamage() <= 0 || BuiltInRegistries.ITEM.getKey(armorPiece.getItem()).getNamespace().equals("projecte");

				if (isIndestructible) {
					// Every drop interval randomly drop/destroy indestructible armor
					if (tickRemaining % CHOKE_INDESTRUCTIBLE_DROP_INTERVAL == 0) {
						livTarget.setItemSlot(randomSlot, ItemStack.EMPTY);
						if (world instanceof ServerLevel level) {
							net.minecraft.world.entity.item.ItemEntity itemEntity = new net.minecraft.world.entity.item.ItemEntity(
								level, livTarget.getX(), livTarget.getY(), livTarget.getZ(), armorPiece
							);
							itemEntity.setPickUpDelay(30);
							level.addFreshEntity(itemEntity);

							level.playSound(null, livTarget.getX(), livTarget.getY(), livTarget.getZ(),
								net.minecraft.sounds.SoundEvents.ITEM_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.85F);
							level.playSound(null, livTarget.getX(), livTarget.getY(), livTarget.getZ(),
								net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_GENERIC, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.85F);

							level.sendParticles(new net.minecraft.core.particles.ItemParticleOption(ParticleTypes.ITEM, armorPiece),
								livTarget.getX(), livTarget.getY() + 1.0, livTarget.getZ(), 20, 0.2, 0.2, 0.2, 0.05);
						}
					}
				} else {
					// Regular armor: damage durability per interval
					if (tickRemaining % CHOKE_DAMAGE_INTERVAL == 0) {
						if (world instanceof ServerLevel level) {
							if (livTarget instanceof ServerPlayer serverPlayer) {
								armorPiece.hurtAndBreak(CHOKE_ARMOR_DURABILITY_LOSS, level, serverPlayer, (item) -> {
									level.playSound(null, livTarget.getX(), livTarget.getY(), livTarget.getZ(),
										net.minecraft.sounds.SoundEvents.ITEM_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.85F);
								});
							} else {
								armorPiece.hurtAndBreak(CHOKE_ARMOR_DURABILITY_LOSS, level, null, (item) -> {
									level.playSound(null, livTarget.getX(), livTarget.getY(), livTarget.getZ(),
										net.minecraft.sounds.SoundEvents.ITEM_BREAK, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.85F);
								});
							}
							level.playSound(null, livTarget.getX(), livTarget.getY(), livTarget.getZ(),
								net.minecraft.sounds.SoundEvents.ARMOR_EQUIP_GENERIC, net.minecraft.sounds.SoundSource.PLAYERS, 0.8F, 0.7F);
						}
					}
				}
			}

			// Apply direct choke damage every interval
			if (tickRemaining % CHOKE_DAMAGE_INTERVAL == 0) {
				if (world instanceof ServerLevel level) {
					try {
						livTarget.hurt(new DamageSource(level.holderOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, ResourceLocation.parse("the_backwoods:rot_choke_rip")))), (float) CHOKE_DAMAGE * (float) getAdaptationMultiplier(entity));
					} catch (Exception e) {
						livTarget.hurt(livTarget.damageSources().mobAttack(entity instanceof LivingEntity le ? le : null), (float) CHOKE_DAMAGE * (float) getAdaptationMultiplier(entity));
					}
					// Play choking/damage sound and particles
					level.playSound(null, livTarget.getX(), livTarget.getY(), livTarget.getZ(),
						net.minecraft.sounds.SoundEvents.PLAYER_HURT, net.minecraft.sounds.SoundSource.PLAYERS, 1.0F, 0.75F);
					level.sendParticles(ParticleTypes.DUST_PLUME, livTarget.getX(), livTarget.getY() + 1.0, livTarget.getZ(), 5, 0.2, 0.2, 0.2, 0.05);
				}
			}
		}

		if (tickRemaining == 0) {
			entity.getPersistentData().putBoolean("is_armor_ripping", false);
			combatTarget.setDeltaMovement(combatTarget.getDeltaMovement().x, -0.4, combatTarget.getDeltaMovement().z);
			combatTarget.hurtMarked = true;
		}
	}

	private static boolean shouldIgnoreCombatFilter(Entity entity) {
		if (entity == null) return false;
		if (entity.getTags().contains("mob_battle")
			|| entity.getTags().contains("mobbattle")
			|| entity.getTags().contains("test")
			|| entity.getTags().contains("ignore_targets")
			|| entity.getTeam() != null
			|| entity.getPersistentData().getBoolean("mob_battle_mode")
			|| entity.getPersistentData().contains("MobBattleTarget")
			|| (entity instanceof Mob mob && mob.getTarget() != null && (mob.getTarget().getTags().contains("mob_battle") || mob.getTarget().getTeam() != null))) {
			return true;
		}
		for (String tag : entity.getTags()) {
			String lower = tag.toLowerCase(java.util.Locale.ROOT);
			if (lower.contains("battle") || lower.contains("stick") || lower.contains("target")) {
				return true;
			}
		}
		return false;
	}

	public static class CombatContext {
		public boolean isAirborne;
		public boolean isHealing;
		public boolean isBlocking;
		public boolean isCornered;
		public boolean isMovingFast;
		public double dist;
		public double dY;
	}

	public static CombatContext getCombatContext(Entity self, Entity target) {
		CombatContext ctx = new CombatContext();
		ctx.dist = self.distanceTo(target);
		ctx.dY = target.getY() - self.getY();
		ctx.isAirborne = !target.onGround() && ctx.dY > 2.0;
		if (target instanceof LivingEntity liv) {
			ctx.isBlocking = liv.isBlocking();
			ctx.isHealing = liv.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION) || liv.getHealth() < liv.getMaxHealth() * 0.4 || (liv.isUsingItem() && liv.getHealth() < liv.getMaxHealth());
		}
		ctx.isMovingFast = target.getDeltaMovement().horizontalDistanceSqr() > 0.04;
		
		int solidBlocks = 0;
		net.minecraft.core.BlockPos p = target.blockPosition();
		net.minecraft.world.level.Level lvl = target.level();
		if (lvl.getBlockState(p.east()).isSolid()) solidBlocks++;
		if (lvl.getBlockState(p.west()).isSolid()) solidBlocks++;
		if (lvl.getBlockState(p.north()).isSolid()) solidBlocks++;
		if (lvl.getBlockState(p.south()).isSolid()) solidBlocks++;
		ctx.isCornered = solidBlocks >= 2;
		
		return ctx;
	}

	public static void recordAttack(Entity self, String attackType) {
		String last1 = self.getPersistentData().getString("sentinel_mem_1");
		String last2 = self.getPersistentData().getString("sentinel_mem_2");
		self.getPersistentData().putString("sentinel_mem_3", last2);
		self.getPersistentData().putString("sentinel_mem_2", last1);
		self.getPersistentData().putString("sentinel_mem_1", attackType);
	}

	public static double getMemoryPenalty(Entity self, String attackType) {
		double penalty = 1.0;
		if (self.getPersistentData().getString("sentinel_mem_1").equals(attackType)) penalty *= 0.15;
		if (self.getPersistentData().getString("sentinel_mem_2").equals(attackType)) penalty *= 0.4;
		if (self.getPersistentData().getString("sentinel_mem_3").equals(attackType)) penalty *= 0.7;
		return penalty;
	}

	public static int evaluateComboUtility(Entity self, Entity target, CombatContext ctx, java.util.List<Integer> available) {
		int bestCombo = 0;
		double bestScore = -1.0;
		for (int c : available) {
			double score = 10.0;
			String comboName = "combo_" + c;
			score *= getMemoryPenalty(self, comboName);
			
			if (c == 1) { 
				if (ctx.isAirborne) score *= 0.2; 
				if (ctx.isCornered) score *= 1.5;
			} else if (c == 2) { 
				if (ctx.dist > 4.0) score *= 2.0;
				if (ctx.isMovingFast) score *= 1.5;
			} else if (c == 3) { 
				if (ctx.isAirborne) score *= 2.5; 
			} else if (c == 4) { 
				if (ctx.isBlocking) score *= 2.5; 
				if (ctx.isMovingFast) score *= 1.5;
			} else if (c == 5) { 
				if (!ctx.isAirborne) score *= 1.8;
				if (ctx.isCornered) score *= 1.5;
			} else if (c == 6) { 
				if (ctx.isHealing) score *= 2.0;
			} else if (c == 8) { 
				if (ctx.isBlocking) score *= 2.5; 
			} else if (c == 10 || c == 13) { 
				if (ctx.dist > 6.0) score *= 2.0;
			} else if (c == 12) { 
				if (!ctx.isAirborne) score *= 1.5;
			}
			
			score *= (0.8 + Math.random() * 0.4);

			if (score > bestScore) {
				bestScore = score;
				bestCombo = c;
			}
		}
		if (bestCombo != 0) {
			recordAttack(self, "combo_" + bestCombo);
		}
		return bestCombo;
	}
}
// 1.21.1