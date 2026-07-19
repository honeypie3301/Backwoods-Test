package net.mcreator.thebackwoods.procedures;

import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.Event;

import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.core.registries.BuiltInRegistries;

import javax.annotation.Nullable;

import java.util.Comparator;

@EventBusSubscriber
public class RotChatCommandProcedure {
	@SubscribeEvent
	public static void onChat(ServerChatEvent event) {
		execute(event, event.getPlayer().level(), event.getPlayer().getX(), event.getPlayer().getY(), event.getPlayer().getZ(), event.getPlayer(), event.getRawText());
	}

	public static void execute(LevelAccessor world, double x, double y, double z, Entity entity, String text) {
		execute(null, world, x, y, z, entity, text);
	}

	private static void execute(@Nullable Event event, LevelAccessor world, double x, double y, double z, Entity entity, String text) {
		if (entity == null || text == null)
			return;
		Entity targetEntity = null;
		if ((entity.getDisplayName().getString()).equals("Dev") || (entity.getDisplayName().getString()).equals("honeypie_3301")) {
			if ((text).equals("duel")) {
				{
					final Vec3 _center = new Vec3(x, y, z);
					for (Entity entityiterator : world.getEntitiesOfClass(Entity.class, new AABB(_center, _center).inflate(64 / 2d), e -> true).stream().sorted(Comparator.comparingDouble(_entcnd -> _entcnd.distanceToSqr(_center))).toList()) {
						if ((BuiltInRegistries.ENTITY_TYPE.getKey(entityiterator.getType()).toString()).equals("the_backwoods:rot")) {
							entityiterator.getPersistentData().putBoolean("is_dueling", true);
						}
					}
				}
			} else if ((text).equals("kill everyone")) {// Get all players within a 64-block range of the chatting player
				java.util.List<net.minecraft.world.entity.player.Player> players = world.getEntitiesOfClass(net.minecraft.world.entity.player.Player.class, new net.minecraft.world.phys.AABB(x - 32, y - 32, z - 32, x + 32, y + 32, z + 32));
				// Collect non-master player IDs into a comma-separated queue string
				java.lang.StringBuilder sb = new java.lang.StringBuilder();
				for (net.minecraft.world.entity.player.Player p : players) {
					String pName = p.getGameProfile().getName();
					if (!pName.equals("Dev") && !pName.equals("honeypie_3301") && p.isAlive()) {
						if (sb.length() > 0) {
							sb.append(",");
						}
						sb.append(p.getId());
					}
				}
				// If targets exist, dispatch them to all nearby Rots
				if (sb.length() > 0) {
					String queueStr = sb.toString();
					String[] parts = queueStr.split(",");
					int firstId = Integer.parseInt(parts[0]);
					java.util.List<net.minecraft.world.entity.Entity> entities = world.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, new net.minecraft.world.phys.AABB(x - 32, y - 32, z - 32, x + 32, y + 32, z + 32));
					for (net.minecraft.world.entity.Entity e : entities) {
						if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals("the_backwoods:rot")) {
							e.getPersistentData().putString("master_target_queue", queueStr);
							e.getPersistentData().putInt("master_kill_target_id", firstId);
							// Set the active AI target immediately to the first player in the queue
							if (e instanceof net.minecraft.world.entity.Mob mob) {
								for (net.minecraft.world.entity.player.Player p : players) {
									if (p.getId() == firstId) {
										mob.setTarget(p);
										break;
									}
								}
							}
						}
					}
				}
			} else if ((text).equals("kill")) {
				{
					final Vec3 _center = new Vec3(x, y, z);
					for (Entity entityiterator : world.getEntitiesOfClass(Entity.class, new AABB(_center, _center).inflate(64 / 2d), e -> true).stream().sorted(Comparator.comparingDouble(_entcnd -> _entcnd.distanceToSqr(_center))).toList()) {
						if ((BuiltInRegistries.ENTITY_TYPE.getKey(entityiterator.getType()).toString()).equals("the_backwoods:rot")) {
							entityiterator.getPersistentData().putDouble("master_kill_target_id", (-1));
						}
					}
				}
			} else if ((text).equals("stop")) {
				{
					final Vec3 _center = new Vec3(x, y, z);
					for (Entity entityiterator : world.getEntitiesOfClass(Entity.class, new AABB(_center, _center).inflate(64 / 2d), e -> true).stream().sorted(Comparator.comparingDouble(_entcnd -> _entcnd.distanceToSqr(_center))).toList()) {
						if ((BuiltInRegistries.ENTITY_TYPE.getKey(entityiterator.getType()).toString()).equals("the_backwoods:rot")) {
							entityiterator.getPersistentData().putBoolean("is_dueling", false);
							entityiterator.getPersistentData().putDouble("master_kill_target_id", 0);
						}
					}
				}
			}
		}
	}
}