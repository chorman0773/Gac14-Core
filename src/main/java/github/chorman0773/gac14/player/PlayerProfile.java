package github.chorman0773.gac14.player;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.mojang.authlib.GameProfile;

import github.chorman0773.gac14.Gac14Core;
import github.chorman0773.gac14.Gac14Module;
import github.chorman0773.gac14.permissions.IBasicPermissible;
import github.chorman0773.gac14.permissions.IGroup;
import github.chorman0773.gac14.permissions.IPermission;
import github.chorman0773.gac14.permissions.PermissionManager;
import github.chorman0773.gac14.server.DataEvent;
import github.chorman0773.gac14.util.Comparators;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.INBT;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.Constants.NBT;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;

@Mod.EventBusSubscriber(modid="gac14-core",bus=Mod.EventBusSubscriber.Bus.FORGE)
public class PlayerProfile implements IBasicPermissible<UUID>, INBTSerializable<CompoundNBT> {
	@Nullable private ServerPlayerEntity player;
	@Nonnull private final GameProfile profile;
	@Nonnull private final UUID id;
	
	private Set<IGroup<ResourceLocation,String,PermissionManager,?>> groups = new TreeSet<>(Comparators.with(Comparators.with(String.CASE_INSENSITIVE_ORDER, ResourceLocation::toString), IGroup::getName));
	private Set<IPermission<PermissionManager,String,?>> permissions = new TreeSet<>();
	private Set<IPermission<PermissionManager,String,?>> revoked = new TreeSet<>();
	
	private Map<ResourceLocation,Consumer<PlayerProfile>> updaters = new TreeMap<>(Comparators.stringOrder);
	
	private Set<IPermission<PermissionManager,String,?>> cached;
	private Set<IGroup<ResourceLocation,String,PermissionManager,?>> cachedGroups;
	private boolean permissionsDirty = true;
	private boolean groupsDirty = true;
	
	private boolean dirty = false;
	
	@Nonnull private static final Gac14Core core = Gac14Core.getInstance(); 
	@Nonnull private static final MinecraftServer server = core.getServer();
	private static final Map<UUID,PlayerProfile> profiles = new TreeMap<>();
	
	@Nonnull private Map<ResourceLocation,PlayerInfoTag<?,?,?,?>> tags = new TreeMap<>((a,b)->a.toString().compareToIgnoreCase(b.toString()));
	
	private static final Logger LOGGER = LogManager.getLogger();
	
	private PlayerProfile(ServerPlayerEntity player,GameProfile profile,UUID id) {
		
		this.player = player;
		this.profile = profile;
		this.id = id;
		LOGGER.info(String.format("Creating PlayerProfile Object for %s", profile));
	}
	
	
	public <Module extends Gac14Module<Module>,Type,NBTTag extends INBT,InfoT extends PlayerInfoTag<Module,Type,NBTTag,InfoT>> void registerKey(InfoT tag) {
		if(tags.putIfAbsent(tag.key, tag)!=null)
			throw new IllegalArgumentException("Tag with key "+tag.key+" already exists");
	}
	
	public void subscribeToUpdates(ResourceLocation loc,Consumer<PlayerProfile> r) {
		updaters.put(loc, r);
	}
	
	public void unsubscribe(ResourceLocation loc) {
		updaters.remove(loc);
	}
	
	public void update() {
		updaters
			.values()
			.forEach(c->c.accept(PlayerProfile.this));
	}
	
	@SubscribeEvent
	public static void doTick(ServerTickEvent e) {
		profiles.values().parallelStream().forEach(PlayerProfile::update);
	}
	
	
	@SuppressWarnings("unchecked")
	public <Type> Type getTag(ResourceLocation key) {
		return (Type)tags.get(key).get();
	}
	
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public <Type> void setTag(ResourceLocation key,Type t) {
		((PlayerInfoTag)tags.get(key)).set((Object)t);
		this.dirty = true;
	}
	
	
	/**
	 * Gets a player by the associated GameProfile. 
	 * 
	 */
	public static PlayerProfile get(GameProfile profile) {
		final UUID id = profile.getId();
		if(profiles.containsKey(id))
			return profiles.get(id);
		ServerPlayerEntity player = server.getPlayerList().getPlayerByUUID(profile.getId());
		PlayerProfile prof = new PlayerProfile(player,profile,id);
		profiles.put(profile.getId(), prof);
		MinecraftForge.EVENT_BUS.post(new PlayerProfileEvent.Create(prof));
		return prof;
	}
	
	public static PlayerProfile get(UUID id) {
		if(profiles.containsKey(id))
			return profiles.get(id);
		GameProfile profile = server.getPlayerProfileCache().getProfileByUUID(id);
		ServerPlayerEntity player = server.getPlayerList().getPlayerByUUID(id);
		PlayerProfile prof = new PlayerProfile(player,profile,id);
		profiles.put(id, prof);
		MinecraftForge.EVENT_BUS.post(new PlayerProfileEvent.Create(prof));
		return prof;
	}
	
	@SubscribeEvent(priority=EventPriority.LOWEST)
	public static void loadProfileInfo(PlayerProfileEvent.Create info) throws IOException {
		Path p = core.getPlayerProfileFile(info.player.id);
		if(Files.exists(p))
			info.player.deserializeNBT(CompressedStreamTools.readCompressed(Files.newInputStream(p)));
		else
			MinecraftForge.EVENT_BUS.post(new PlayerProfileEvent.New(info.player));
	}
	
	@SubscribeEvent
	public static void savePlayers(DataEvent.Save save) throws IOException {
		Set<UUID> idsToPurge = new TreeSet<>();
		for(UUID player:profiles.keySet()) {
			PlayerProfile prof = profiles.get(player);
			if(prof.dirty) {
				Path p = core.getPlayerProfileFile(player);
				CompressedStreamTools.writeCompressed(prof.serializeNBT(), Files.newOutputStream(p));
				prof.dirty = false;
			}
			if(prof.getPlayer()==null)//IE. the player is offline
				idsToPurge.add(prof.id);
		}
		for(UUID id:idsToPurge)
			profiles.remove(id);
		
	}
	
	
	public static PlayerProfile get(ServerPlayerEntity player) {
		PlayerProfile prof = get(player.getUniqueID());
		prof.getPlayer();
		return prof;
	}
	
	public ServerPlayerEntity getPlayer() {
		if(player!=null) {
			if(player.hasDisconnected())
				return player = null;
			else
				return player;
		}
		else 
			return player = server.getPlayerList().getPlayerByUUID(id);
	}
	
	public void markDirty() {
		this.dirty = true;
	}
	
	/**
	* Adds the given permission to this players set of permissions.
	* After this call, that permission, and all children that are more general than any revoked permission will appear in the result of getPermissions().
	* 
	* The permission, but not any of its children, are added to the list of explicit permissions.
	* 
	* Complexity Guarantee:
	* O(logn) or Amortized Constant Time (O(1) with O(n) permissible some times).
	* 
	* Postconditions:
	* After this call, reguardless of whether or not it has any observable result, invalidates all previous calls to getPermissions()
	*/
	public void addPermission(IPermission<PermissionManager,String,?> permission) {
		revoked.remove(permission);
		permissions.add(permission);
		permissionsDirty = true;
		markDirty();
	}
	
	/**
	 * Removes the given permission from the explicit permissions list
	 * This removes this permission iff its not implied by any other explicit permission,
	 *  then all permissions implied by this permission are removed also if they are not an explicit permission, or implied by an explicit permission.
	 * 
	 * Complexity Guarantee:
	 * This call guarantees O(n). 
	 * 
	 * Additionally, if the passed permission is the most specific node it implies which is an explicit permission,
	 *  or it is not an explicit permission, Guarantees either O(logn) or Amortized Constant Time.
	 *  
	 * Postconditions:
	 * After this call, reguardless of if any changes occured, this method invalidates any previous results of getPermissions()
	 */
	public void removePermission(IPermission<PermissionManager,String,?> permission) {
		permissions.remove(permission);
		permissionsDirty = true;
		markDirty();
	}
	
	public void revokePermission(IPermission<PermissionManager,String,?> permission) {
		permissions.remove(permission);
		revoked.add(permission);
		permissionsDirty = true;
		markDirty();
	}


	@Override
	public UUID getName() {
		// TODO Auto-generated method stub
		return id;
	}


	/**
	 * Gets the set of all permissions.
	 * The result of this method is invalidated on any call to addPermission, removePermission, and revokePermission.
	 * <br/>
	 * Complexity Guarantee:<br/>
	 * O(n^2)
	 */
	@Override
	public Set<? extends IPermission<PermissionManager, String, ?>> getPermissions(PermissionManager manager) {
		if(permissionsDirty) {
			Set<IPermission<PermissionManager,String,?>> permissions = new TreeSet<>();
			for(IPermission<PermissionManager,String,?> p:this.permissions)
				permissions.addAll(p.implies(manager));
			for(IGroup<ResourceLocation,String,PermissionManager,?> g:this.groups)
				permissions.addAll((Set<? extends IPermission<PermissionManager,String,?>>)g.getPermissions(manager));
			for(IPermission<PermissionManager,String,?> p:this.revoked)
				permissions.removeAll(p.implies(manager));
			cached = permissions;
			permissionsDirty = false;
		}
		return Collections.unmodifiableSet(this.cached);
	}


	@Override
	public Set<? extends IGroup<ResourceLocation,String, PermissionManager, ?>> getGroups(PermissionManager manager) {
		if(groupsDirty) {
			Set<IGroup<ResourceLocation,String,PermissionManager,?>> groups = new TreeSet<>(PermissionManager.groupsByName);
			for(IGroup<ResourceLocation,String,PermissionManager,?> g:this.groups)
				groups.addAll(g.getGroups(manager));
			cachedGroups = groups;
			groupsDirty = false;
		}
		return Collections.unmodifiableSet(cachedGroups);
	}


	@Override
	public CompoundNBT serializeNBT() {
		CompoundNBT nbt = new CompoundNBT();
		ListNBT permissions = new ListNBT();
		for(IPermission<PermissionManager,String,?> permission:this.permissions)
			permissions.add(new StringNBT(permission.getName()));
		nbt.put("Permissions", permissions);
		ListNBT revoked = new ListNBT();
		for(IPermission<PermissionManager,String,?> permission:this.revoked)
			revoked.add(new StringNBT(permission.getName()));
		nbt.put("RevokedPermissions", revoked);
		ListNBT groups = new ListNBT();
		for(IGroup<ResourceLocation,String,PermissionManager,?> group:this.groups)
			groups.add(new StringNBT(group.getName().toString()));
		nbt.put("Groups", groups);
		CompoundNBT tags = new CompoundNBT();
		for(Map.Entry<ResourceLocation, PlayerInfoTag<?,?,?,?>> tag:this.tags.entrySet())
			if(tag.getValue() instanceof PlayerInfoTransientTag<?,?,?,?>)
				continue;
			else
				tags.put(tag.getKey().toString(), tag.getValue().writeToNbt());
		return nbt;
	}


	@Override
	public void deserializeNBT(CompoundNBT nbt) {
		PermissionManager manager = Gac14Core.getInstance().getPermissionManager();
		ListNBT permissions = nbt.getList("Permissions", NBT.TAG_STRING);
		for(int i = 0;i<permissions.size();i++)
			this.permissions.add(manager.getPermission(permissions.getString(i)));
		ListNBT revoked = nbt.getList("RevokedPermissions", NBT.TAG_STRING);
		for(int i = 0;i<revoked.size();i++)
			this.revoked.add(manager.getPermission(revoked.getString(i)));
		ListNBT groups = nbt.getList("Groups", NBT.TAG_STRING);
		for(int i = 0;i<groups.size();i++)
			this.groups.add(manager.getGroupByName(new ResourceLocation(groups.getString(i))));
		CompoundNBT tags = nbt.getCompound("Tags");
		for(Map.Entry<ResourceLocation, PlayerInfoTag<?,?,?,?>> tag:this.tags.entrySet())
			tag.getValue().readNBT(tags.get(tag.getKey().toString()));
		permissionsDirty = true;
		dirty = false;
	}
	
	public static void playerJoinsGame(PlayerEvent.PlayerLoggedInEvent logIn) {
		ServerPlayerEntity player = (ServerPlayerEntity) logIn.getPlayer();
		get(player);
	}


	public GameProfile getGameProfile() {
		// TODO Auto-generated method stub
		return this.profile;
	}


	public void joinGroup(IGroup<ResourceLocation,String,PermissionManager,?> group) {
		this.groups.add(group);
		permissionsDirty = true;
		groupsDirty = true;
		markDirty();
	}
	
	public void leaveGroup(IGroup<ResourceLocation,String,PermissionManager,?> group) {
		this.groups.remove(group);
		permissionsDirty = true;
		groupsDirty = true;
		markDirty();
	}
	
}
