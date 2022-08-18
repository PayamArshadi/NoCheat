package cc.co.evenprime.bukkit.nocheat;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import cc.co.evenprime.bukkit.nocheat.actions.ActionManager;
import cc.co.evenprime.bukkit.nocheat.command.CommandHandler;
import cc.co.evenprime.bukkit.nocheat.config.ConfigurationManager;
import cc.co.evenprime.bukkit.nocheat.config.cache.ConfigurationCache;
import cc.co.evenprime.bukkit.nocheat.config.util.ActionList;
import cc.co.evenprime.bukkit.nocheat.data.BaseData;
import cc.co.evenprime.bukkit.nocheat.data.DataManager;
import cc.co.evenprime.bukkit.nocheat.data.ExecutionHistory;
import cc.co.evenprime.bukkit.nocheat.debug.ActiveCheckPrinter;
import cc.co.evenprime.bukkit.nocheat.debug.LagMeasureTask;
import cc.co.evenprime.bukkit.nocheat.debug.Performance;
import cc.co.evenprime.bukkit.nocheat.debug.PerformanceManager;
import cc.co.evenprime.bukkit.nocheat.debug.PerformanceManager.Type;

import cc.co.evenprime.bukkit.nocheat.events.BlockPlaceEventManager;
import cc.co.evenprime.bukkit.nocheat.events.BlockBreakEventManager;
import cc.co.evenprime.bukkit.nocheat.events.EntityDamageEventManager;
import cc.co.evenprime.bukkit.nocheat.events.EventManager;
import cc.co.evenprime.bukkit.nocheat.events.PlayerChatEventManager;
import cc.co.evenprime.bukkit.nocheat.events.PlayerMoveEventManager;
import cc.co.evenprime.bukkit.nocheat.events.PlayerTeleportEventManager;
import cc.co.evenprime.bukkit.nocheat.events.SwingEventManager;
import cc.co.evenprime.bukkit.nocheat.events.TimedEventManager;
import cc.co.evenprime.bukkit.nocheat.log.LogLevel;
import cc.co.evenprime.bukkit.nocheat.log.LogManager;

/**
 * 
 * NoCheat
 * 
 * Check various player events for their plausibility and log/deny them/react to
 * them based on configuration
 */
public class NoCheat extends JavaPlugin {

    private ConfigurationManager conf;
    private LogManager           log;
    private DataManager          data;
    private PerformanceManager   performance;
    private ActionManager        action;

    private List<EventManager>   eventManagers;

    private LagMeasureTask       lagMeasureTask;

    private int                  taskId = -1;

    public NoCheat() {

    }

    public void onDisable() {

        PluginDescriptionFile pdfFile = this.getDescription();

        if(taskId != -1) {
            getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }

        if(lagMeasureTask != null) {
            lagMeasureTask.cancel();
            lagMeasureTask = null;
        }

        if(conf != null) {
            conf.cleanup();
            conf = null;
        }

        // Just to be sure nothing gets left out
        getServer().getScheduler().cancelTasks(this);

        log.logToConsole(LogLevel.LOW, "[NoCheat] version [" + pdfFile.getVersion() + "] is disabled.");
    }

    public void onEnable() {

        // First set up logging
        this.log = new LogManager();

        log.logToConsole(LogLevel.LOW, "[NoCheat] This version for CB #1060. It may break at any time and for any other version.");

        // Then set up in memory per player data storage
        this.data = new DataManager();

        // Then read the configuration files
        this.conf = new ConfigurationManager(this.getDataFolder().getPath());

        // Then set up the performance counters
        this.performance = new PerformanceManager();

        // Then set up the Action Manager
        this.action = new ActionManager(this);

        eventManagers = new ArrayList<EventManager>(8); // Big enough
        // Then set up the event listeners
        eventManagers.add(new PlayerMoveEventManager(this));
        eventManagers.add(new PlayerTeleportEventManager(this));
        eventManagers.add(new PlayerChatEventManager(this));
        eventManagers.add(new BlockBreakEventManager(this));
        eventManagers.add(new BlockPlaceEventManager(this));
        eventManagers.add(new EntityDamageEventManager(this));
        eventManagers.add(new SwingEventManager(this));
        TimedEventManager m = new TimedEventManager(this);
        eventManagers.add(m);

        // Then set up a task to monitor server lag
        if(lagMeasureTask == null) {
            lagMeasureTask = new LagMeasureTask(this);
            lagMeasureTask.start();
        }

        // Then print a list of active checks per world
        ActiveCheckPrinter.printActiveChecks(this, eventManagers);

        // Tell the server admin that we finished loading NoCheat now
        log.logToConsole(LogLevel.LOW, "[NoCheat] version [" + this.getDescription().getVersion() + "] is enabled.");
    }

    public ConfigurationCache getConfig(Player player) {
        return getConfig(player.getWorld());
    }

    public ConfigurationCache getConfig(World world) {
        return conf.getConfigurationCacheForWorld(world.getName());
    }

    public void log(LogLevel level, String message, ConfigurationCache cc) {
        log.log(level, message, cc);
    }

    public BaseData getData(String playerName) {
        return data.getData(playerName);
    }

    public void clearCriticalData(String playerName) {
        data.clearCriticalData(playerName);
    }

    public void playerJoined(String playerName) {
        clearCriticalData(playerName);
    }

    public Performance getPerformance(Type type) {
        return performance.get(type);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return CommandHandler.handleCommand(this, sender, command, label, args);
    }

    public int getIngameSeconds() {
        if(lagMeasureTask != null)
            return lagMeasureTask.getIngameSeconds();
        return 0;
    }

    public boolean skipCheck() {
        if(lagMeasureTask != null)
            return lagMeasureTask.skipCheck();
        return false;
    }

    public long getIngameSecondDuration() {
        if(lagMeasureTask != null)
            return lagMeasureTask.getIngameSecondDuration();
        return 1000L;
    }

    public boolean execute(Player player, ActionList actions, int violationLevel, ExecutionHistory history, ConfigurationCache cc) {
        if(action != null) {
            return action.executeActions(player, actions, violationLevel, history, cc);
        }
        return false;
    }

    public void logToConsole(LogLevel low, String message) {
        if(log != null) {
            log.logToConsole(low, message);
        }

    }

    public void reloadConfig() {
        conf.cleanup();
        this.conf = new ConfigurationManager(this.getDataFolder().getPath());
        data.cleanDataMap();
        data.clearCriticalData();
    }

    /**
     * Call this periodically to walk over the stored data map and remove
     * old/unused entries
     * 
     */
    public void cleanDataMap() {
        data.cleanDataMap();

    }
}
