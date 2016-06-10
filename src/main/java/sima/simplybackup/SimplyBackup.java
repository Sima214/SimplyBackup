package sima.simplybackup;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.event.FMLServerStoppedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * The java file responsible for everything
 */
@Mod(modid = "simplybackup", version = "1.0", name = "Simply Backup", acceptedMinecraftVersions = "1.7.10", acceptableRemoteVersions = "*")
public class SimplyBackup {
    @Mod.Instance("simplybackup")
    public static SimplyBackup instance;
    //Required instances (Used mainly to do stuff than to store data)
    protected Logger forgeLog;
    protected ProcessBuilder procBuilder;
    protected BackupThread thread = new BackupThread();
    //Data-storing variables, mainly of the type loaded once, used a lot.
    protected ArrayList<String> args = new ArrayList<String>(4);
    protected File outputDir;
    protected int autoTicks;
    protected int keep;
    protected boolean backupOnExit;
    //Constants
    protected static final int secsToTicks = 20;
    //Constantly(?) changing ones.
    protected long nextSchedule;
    protected boolean shouldResetSave;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        //1st : Setup Logger instance
        forgeLog = e.getModLog();
        //2nd : Load configuration while setting up the arguments for the process doing the compressing.
        Configuration config = new Configuration(e.getSuggestedConfigurationFile());
        config.load();
        final String CATEGORY = "Simply Backup";
        args.add(config.getString("program", CATEGORY, "zip", "The program to executr for compressing."));
        args.add(config.getString("arguments", CATEGORY, "-q -r -5", "The arguments/options passed in to the program referenced by the program config"));
        //Kind of a hacky way to get the minecraft directory. So if you, how are reading this and know a better way, then leave an issue.
        args.add(null);//Reserve space.
        outputDir = new File(config.getString("Backup output directory", CATEGORY, new File(e.getModConfigurationDirectory().getParent(), "backup").toString(), "The folder where to store the backups. Must be a string which can be translated into a directory by java.io.File"));
        args.add(config.getString("filter", CATEGORY, "./", "The filter used to choose what files to backup.\nNOTE : The program is executed inside the folder of the currently playing world."));
        autoTicks = config.getInt("autoticks", CATEGORY, secsToTicks * 60 * 60, 1, Integer.MAX_VALUE, "How many ticks to wait before we start a new automatic backup.");
        keep = config.getInt("keep", CATEGORY, -1, -1, Integer.MAX_VALUE, "How many backups to keep. Set to -1 to disable. Old backups created with this feature disabled will probably not count when you enable it.");
        backupOnExit = config.getBoolean("onexit", CATEGORY, true, "Whether to start a backup on exiting a world.");
        if (config.hasChanged()) config.save();
        //3rd : Do final preparations for the next steps (Mainly initialize procBuilder and check the sanity of the provided data).
        procBuilder = new ProcessBuilder(args);
        procBuilder.redirectErrorStream(true);//Potential Improvement : Handle log output from the process in a better way.
        procBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        if (outputDir.isDirectory()) {
            log(Level.DEBUG, "Backup directory seems okay.");
        } else if (outputDir.mkdirs()) {
            log(Level.DEBUG, "Created backup directory.");
        } else throw new RuntimeException("Could not create backup output directory.");
    }

    @Mod.EventHandler
    public void serverStart(FMLServerStartingEvent e) throws IOException {
        e.registerServerCommand(new BackupCommand());
        FMLCommonHandler.instance().bus().register(this);
        String worldName = e.getServer().getWorldName();
        outputDir = new File(outputDir, worldName);
        args.set(2, outputDir.getPath());
        procBuilder.directory(DimensionManager.getCurrentSaveRootDirectory().getCanonicalFile());
        resetTimers();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {
            if (getTicks() >= nextSchedule) {
                log(Level.INFO, "Starting automatic backup.");
                thread.triggerBackup(BackupTriggers.SCHEDULED);
            }
            if (shouldResetSave) {
                saveReset();
                shouldResetSave = false;
            }
        }
    }

    @Mod.EventHandler
    public void onServerStop(FMLServerStoppedEvent e) {
        outputDir = outputDir.getParentFile();
        if (backupOnExit) {
            log(Level.INFO, "Starting save on exit backup.");
            thread.triggerBackup(BackupTriggers.ONEXIT);
        }
    }

    protected long getTicks() {
        return MinecraftServer.getServer().getTickCounter();
    }

    protected void resetTimers() {
        nextSchedule = getTicks() + autoTicks;
    }


    private Level CHAT_THRESHOLD = Level.INFO;

    protected void log(Level l, String s) {
        forgeLog.log(l, s);
        if (l.isAtLeastAsSpecificAs(CHAT_THRESHOLD)) {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                ServerConfigurationManager manager = MinecraftServer.getServer().getConfigurationManager();
                if (manager != null) {
                    manager.sendChatMsg(new ChatComponentText(s));
                }
            }
        }
    }

    private BitSet state = new BitSet();

    protected void saveOffAndAll() {
        log(Level.INFO, "Saving to disk.");
        state.clear();
        MinecraftServer server = MinecraftServer.getServer();
        if (server.getConfigurationManager() != null) {
            server.getConfigurationManager().saveAllPlayerData();
        }
        WorldServer[] worldServers = server.worldServers;
        for (int i = 0; i < worldServers.length; i++) {
            WorldServer cur = worldServers[i];
            if (cur != null) {
                //Set save off
                if (cur.levelSaving) {
                    state.set(i);
                } else {
                    cur.levelSaving = true;
                }
                //Actually save
                try {
                    cur.saveAllChunks(true, null);
                    cur.saveChunkData();
                } catch (MinecraftException e) {
                    log(Level.ERROR, "Error occurred while saving chunks. Continuing...");
                    e.printStackTrace();
                }

            }
        }
    }

    protected void saveReset() {
        log(Level.DEBUG, "Resetting state.");
        MinecraftServer server = MinecraftServer.getServer();
        WorldServer[] worldServers = server.worldServers;
        for (int i = 0; i < worldServers.length; i++) {
            WorldServer cur = worldServers[i];
            cur.levelSaving = state.get(i);
        }
    }

    class BackupCommand extends CommandBase {
        @Override
        public String getCommandName() {
            return "backup";
        }

        @Override
        public String getCommandUsage(ICommandSender user) {
            return "/backup <start>";
        }

        @Override
        public List addTabCompletionOptions(ICommandSender user, String[] args) {
            return Collections.singletonList("start");
        }

        @Override
        public void processCommand(ICommandSender user, String[] args) {
            if (args.length > 0 && args[0].equals("start")) {
                log(Level.INFO, "Starting a manual backup...");
                thread.triggerBackup(BackupTriggers.MANUAL);
            } else {
                log(Level.INFO, (nextSchedule - getTicks()) + " ticks or " + ((nextSchedule - getTicks()) / 1200) + " minutes remaining until next scheduled backup.");
                log(Level.INFO, "Use /backup start to start a new manual backup.");
            }
        }
    }

    class BackupThread extends Thread {
        private BackupTriggers task;

        public BackupThread() {
            setName("BackupThread");
            setPriority(3);
            setDaemon(true);
            start();
        }

        protected void triggerBackup(BackupTriggers trig) {
            if (task != null) {
                log(Level.ERROR, "Cannot have more than one backup running at once!");
                return;
            }
            //No need to save if the server is shutting down.
            if (trig != BackupTriggers.ONEXIT) {
                saveOffAndAll();
            }
            task = trig;
            resetTimers();
            LockSupport.unpark(this);

        }

        @Override
        public void run() {
            while (true) {
                while (task == null) {
                    LockSupport.park();
                }
                //**************NYI**************
                try {
                    sleep(10000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                //**************NYI**************
                shouldResetSave = true;
                task = null;
            }
        }
    }

    enum BackupTriggers {SCHEDULED, MANUAL, ONEXIT}
}
