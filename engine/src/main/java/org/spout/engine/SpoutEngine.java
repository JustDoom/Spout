/*
 * This file is part of Spout.
 *
 * Copyright (c) 2011 Spout LLC <http://www.spout.org/>
 * Spout is licensed under the Spout License Version 1.
 *
 * Spout is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * In addition, 180 days after any changes are published, you can use the
 * software, incorporating those changes, under the terms of the MIT license,
 * as described in the Spout License Version 1.
 *
 * Spout is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License,
 * the MIT license and the Spout License Version 1 along with this program.
 * If not, see <http://www.gnu.org/licenses/> for the GNU Lesser General Public
 * License and see <http://spout.in/licensev1> for the full license, including
 * the MIT license.
 */
package org.spout.engine;

import java.io.File;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import org.spout.api.Engine;
import org.spout.api.Spout;
import org.spout.api.command.CommandManager;
import org.spout.api.command.CommandSource;
import org.spout.api.command.annotated.AnnotatedCommandExecutorFactory;
import org.spout.api.event.EventManager;
import org.spout.api.event.SimpleEventManager;
import org.spout.api.event.server.permissions.PermissionGetAllWithNodeEvent;
import org.spout.api.exception.SpoutRuntimeException;
import org.spout.api.generator.biome.BiomeRegistry;
import org.spout.api.geo.World;
import org.spout.api.geo.cuboid.Region;
import org.spout.api.geo.discrete.Point;
import org.spout.api.inventory.recipe.RecipeManager;
import org.spout.api.inventory.recipe.SimpleRecipeManager;
import org.spout.api.lighting.LightingRegistry;
import org.spout.api.material.MaterialRegistry;
import org.spout.api.permissions.DefaultPermissions;
import org.spout.api.permissions.PermissionsSubject;
import org.spout.api.plugin.Plugin;
import org.spout.api.plugin.PluginManager;
import org.spout.api.plugin.security.PluginSecurityManager;
import org.spout.api.plugin.services.ServiceManager;
import org.spout.api.protocol.Protocol;
import org.spout.api.scheduler.TaskManager;
import org.spout.api.scheduler.TaskPriority;
import org.spout.api.util.StringToUniqueIntegerMap;
import org.spout.api.util.SyncedStringMap;
import org.spout.cereal.config.ConfigurationException;
import org.spout.engine.command.AnnotatedCommandExecutorTest;
import org.spout.engine.command.ClientCommands;
import org.spout.engine.command.CommonCommands;
import org.spout.engine.command.InputCommands;
import org.spout.engine.command.MessagingCommands;
import org.spout.engine.command.ServerCommands;
import org.spout.engine.command.TestCommands;
import org.spout.engine.console.ConsoleManager;
import org.spout.engine.entity.EntityManager;
import org.spout.engine.entity.SpoutPlayer;
import org.spout.engine.filesystem.CommonFileSystem;
import org.spout.engine.filesystem.ServerFileSystem;
import org.spout.engine.input.SpoutInputConfiguration;
import org.spout.engine.protocol.builtin.SpoutProtocol;
import org.spout.engine.scheduler.SpoutParallelTaskManager;
import org.spout.engine.scheduler.SpoutScheduler;
import org.spout.engine.util.DeadlockMonitor;
import org.spout.engine.util.TicklockMonitor;
import org.spout.engine.util.thread.AsyncManager;
import org.spout.engine.util.thread.snapshotable.SnapshotManager;
import org.spout.engine.util.thread.snapshotable.SnapshotableLinkedHashMap;
import org.spout.engine.util.thread.snapshotable.SnapshotableReference;
import org.spout.engine.world.MemoryReclamationThread;
import org.spout.engine.world.SpoutRegion;

public abstract class SpoutEngine implements AsyncManager, Engine {
	private static final Logger logger = Logger.getLogger("Spout");
	private final SpoutConfiguration config = new SpoutConfiguration();
	private final SpoutInputConfiguration inputConfig = new SpoutInputConfiguration();
	// TODO: this is really hacky
	{
		try {
			config.load();
			inputConfig.load();
		} catch (ConfigurationException e) {
			Spout.severe("Error loading config: " + e.getMessage(), e);
		}
	}
	private final PluginSecurityManager securityManager = new PluginSecurityManager(0); //TODO Need to integrate this/evaluate security in the engine.
	private final PluginManager pluginManager = new PluginManager(this, securityManager, 0.0);
	private final ConsoleManager consoleManager;
	private final EventManager eventManager = new SimpleEventManager();
	private final RecipeManager recipeManager = new SimpleRecipeManager();
	private final ServiceManager serviceManager = new ServiceManager();
	protected final SnapshotManager snapshotManager = new SnapshotManager();
	protected final SpoutScheduler scheduler = new SpoutScheduler(this);
	protected final SpoutParallelTaskManager parallelTaskManager = new SpoutParallelTaskManager(this);
	private final AtomicBoolean setupComplete = new AtomicBoolean(false);
	protected final SnapshotableReference<World> defaultWorld = new SnapshotableReference<>(snapshotManager, null);
	protected final ConcurrentMap<SocketAddress, Protocol> boundProtocols = new ConcurrentHashMap<>();
	protected final SnapshotableLinkedHashMap<String, SpoutPlayer> onlinePlayers = new SnapshotableLinkedHashMap<>(snapshotManager);
	protected final CommandManager cmdManager = new CommandManager();
	protected String logFile;
	private SpoutApplication arguments;
	protected MemoryReclamationThread reclamation = null;
	private DefaultPermissions defaultPerms;
	private SyncedStringMap engineItemMap = null;
	private SyncedStringMap engineBiomeMap = null;
	private SyncedStringMap engineLightingMap = null;

	public SpoutEngine() {
		logFile = "log-%D.txt";
		consoleManager = new ConsoleManager(this);
	}

	public void init(SpoutApplication args) {
		this.arguments = args;

		consoleManager.setupConsole();

		scheduler.addAsyncManager(this);

		defaultPerms = new DefaultPermissions(this, new File(CommonFileSystem.CONFIG_DIRECTORY, "permissions.yml"));
		getDefaultPermissions().addDefaultPermission(STANDARD_BROADCAST_PERMISSION);
		getDefaultPermissions().addDefaultPermission(STANDARD_CHAT_PREFIX + "*");

		if (debugMode()) {
			new TicklockMonitor().start();
			new DeadlockMonitor().start();
		}

		// Must register protocol on init or client session can't happen
		Protocol.registerProtocol(SpoutProtocol.INSTANCE);
		if (Protocol.getProtocol("Spout") == null) {
			throw new IllegalStateException("SpoutProtocol was not successfully registered!");
		}
		loadPluginsAndProtocol();
	}

	@Override
	public String getAPIVersion() {
		return SpoutEngine.class.getPackage().getImplementationVersion();
	}

	public void start() {
		Spout.info("Spout is starting in {0}-only mode.", getPlatform().name().toLowerCase());
		Spout.info("This {0}'s version is {1}.",
				getPlatform().name().toLowerCase(),
				"unknown");
				//Spout.getAPIVersion().replace("dev b", ""));
		Spout.info("This software is currently in alpha status so components may");
		Spout.info("have bugs or not work at all. Please report any issues to");
		Spout.info("http://issues.spout.org");

		if (debugMode()) {
			Spout.warn("Debug Mode has been toggled on!  This mode is intended for developers only");
		}

		scheduler.scheduleSyncRepeatingTask(this, getSessionTask(), 50, 50, TaskPriority.CRITICAL);

		//Setup the Material Registry
		engineItemMap = MaterialRegistry.setupRegistry();
		// Setup the Biome Registry
		engineBiomeMap = BiomeRegistry.setupRegistry();
		// Setup the Lighting Registry
		engineLightingMap = LightingRegistry.setupRegistry();

		// Register commands
		Object exe;
		switch (getPlatform()) {
			case CLIENT:
				exe = new ClientCommands((SpoutClient) this);
				break;
			case SERVER:
				exe = new ServerCommands(this);
				break;
			default:
				exe = new CommonCommands(this);
				break;
		}
		AnnotatedCommandExecutorFactory.create(exe);

		AnnotatedCommandExecutorFactory.create(new MessagingCommands(this));
		InputCommands.setupInputCommands(this);

		if (debugMode()) {
			AnnotatedCommandExecutorFactory.create(new TestCommands(this));
			// testing the annotated command API at runtime
			AnnotatedCommandExecutorFactory.create(new AnnotatedCommandExecutorTest.RootExecutor());
			AnnotatedCommandExecutorFactory.create(new AnnotatedCommandExecutorTest.ChildExecutor(), cmdManager.getCommand("root"));
		}

		// Start loading plugins
		setupBindings(config);
		enablePlugins();

		if (SpoutConfiguration.RECLAIM_MEMORY.getBoolean()) {
			reclamation = new MemoryReclamationThread();
			reclamation.start();
		}

		scheduler.startMainThread();
		setupComplete.set(true);
	}

	protected abstract Runnable getSessionTask();

	/**
	 * This method is called before {@link #enablePlugins()}
	 */
	protected void setupBindings(SpoutConfiguration config) {
	}

	private void loadPluginsAndProtocol() {
		pluginManager.clearPlugins();
		pluginManager.installUpdates();

		pluginManager.loadPlugins(ServerFileSystem.PLUGINS_DIRECTORY);
	}

	public SpoutApplication getArguments() {
		return arguments;
	}

	private void enablePlugins() {
		for (Plugin plugin : pluginManager.getPlugins()) {
			pluginManager.enablePlugin(plugin);
		}
	}

	@Override
	public String getVersion() {
		return getClass().getPackage().getImplementationVersion();
	}

	@Override
	public Set<PermissionsSubject> getAllWithNode(String permission) {
		return getEventManager().callEvent(new PermissionGetAllWithNodeEvent(permission)).getAllowedReceivers();
	}

	@Override
	public PluginManager getPluginManager() {
		return pluginManager;
	}

	@Override
	public Logger getLogger() {
		return logger;
	}

	@Override
	public File getUpdateFolder() {
		return CommonFileSystem.UPDATES_DIRECTORY;
	}

	@Override
	public File getConfigFolder() {
		return CommonFileSystem.CONFIG_DIRECTORY;
	}

	@Override
	public File getDataFolder() {
		File dataDir = CommonFileSystem.DATA_DIRECTORY;
		File playerDirectory = new File(dataDir, "players");
		if (!playerDirectory.exists()) {
			playerDirectory.mkdirs();
		}
		return dataDir;
	}

	@Override
	public File getPluginFolder() {
		return CommonFileSystem.PLUGINS_DIRECTORY;
	}

	@Override
	public boolean stop() {
		return stop("Spout shutting down");
	}

	private final AtomicBoolean stopping = new AtomicBoolean();

	@Override
	public boolean stop(final String message) {
		return stop(message, true);
	}

	/**
	 * Used to allow subclasses submit final tasks before stopping the scheduler
	 *
	 * @param message to send
	 * @param stopScheduler true if should stop scheduler
	 * @return true if successfully stopped
	 */
	protected boolean stop(final String message, boolean stopScheduler) {
		if (!stopping.compareAndSet(false, true)) {
			return false;
		}

		getPluginManager().clearPlugins();

		if (stopScheduler) {
			scheduler.stop();
		}
		return true;
	}

	@Override
	public EventManager getEventManager() {
		return eventManager;
	}

	@Override
	public SpoutScheduler getScheduler() {
		return scheduler;
	}

	@Override
	public TaskManager getParallelTaskManager() {
		return parallelTaskManager;
	}

	@Override
	public Protocol getProtocol(SocketAddress socketAddress) {
		Protocol proto = boundProtocols.get(socketAddress);
		if (proto == null) {
			for (Map.Entry<SocketAddress, Protocol> entry : boundProtocols.entrySet()) {
				if (entry.getKey() instanceof InetSocketAddress && socketAddress instanceof InetSocketAddress) {
					InetSocketAddress key = (InetSocketAddress) entry.getKey(), given = (InetSocketAddress) socketAddress;
					if (key.getPort() == given.getPort() && ((given.getAddress() instanceof Inet4Address && key.getAddress().getHostAddress().equals("0.0.0.0"))
							|| (given.getAddress() instanceof Inet6Address && key.getAddress().getHostAddress().equals("::")))) { // TODO: Make sure IPV6 works
						proto = entry.getValue();
						break;
					}
				}
			}
		}

		if (proto == null) {
			throw new SpoutRuntimeException("No protocol for bound address!");
		}
		return proto;
	}

	@Override
	public ServiceManager getServiceManager() {
		return serviceManager;
	}

	@Override
	public RecipeManager getRecipeManager() {
		return recipeManager;
	}

	@Override
	public boolean debugMode() {
		return arguments.debug;
	}

	@Override
	public Thread getMainThread() {
		return scheduler.getMainThread();
	}

	@Override
	public void finalizeRun() {
		// TODO Auto-generated method stub

	}

	@Override
	public void preSnapshotRun() {
		// TODO Auto-generated method stub

	}

	@Override
	public void copySnapshotRun() {
		snapshotManager.copyAllSnapshots();
	}

	@Override
	public int getMaxStage() {
		return 0;
	}

	@Override
	public boolean setDefaultWorld(World world) {
		if (world == null) {
			return false;
		}

		defaultWorld.set(world);
		return true;
	}

	@Override
	public String getLogFile() {
		return logFile;
	}

	@Override
	public CommandSource getCommandSource() {
		return consoleManager.getCommandSource();
	}

	public boolean isSetupComplete() {
		return setupComplete.get();
	}

	// The engine doesn't do any of these

	@Override
	public void runPhysics(int sequence) {
	}

	@Override
	public long getFirstDynamicUpdateTime() {
		return SpoutScheduler.END_OF_THE_WORLD;
	}

	@Override
	public void runDynamicUpdates(long time, int sequence) {
	}

	@Override
	public void runLighting(int sequence) {
	}

	@Override
	public DefaultPermissions getDefaultPermissions() {
		return defaultPerms;
	}

	private Thread executionThread;

	@Override
	public Thread getExecutionThread() {
		return executionThread;
	}

	@Override
	public void setExecutionThread(Thread t) {
		this.executionThread = t;
	}

	@Override
	public int getSequence() {
		return 0;
	}

	@Override
	public CommandManager getCommandManager() {
		return cmdManager;
	}

	/**
	 * Gets the item map used across all worlds on the engine
	 *
	 * @return engine map
	 */
	public StringToUniqueIntegerMap getEngineItemMap() {
		return engineItemMap;
	}

	/**
	 * Gets the lighting map used across all worlds on the engine
	 *
	 * @return engine map
	 */
	public StringToUniqueIntegerMap getEngineLightingMap() {
		return engineLightingMap;
	}

	/**
	 * Gets the biome map used accorss all worlds on the engine
	 *
	 * @return biome map
	 */
	public StringToUniqueIntegerMap getEngineBiomeMap() {
		return engineBiomeMap;
	}
}
