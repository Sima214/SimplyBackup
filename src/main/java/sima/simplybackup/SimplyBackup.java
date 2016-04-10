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
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The java file responsible for everything
 */
@Mod(modid = "simplybackup", version = "1.0", name = "Simply Backup", acceptedMinecraftVersions = "1.7.10", acceptableRemoteVersions = "*")
public class SimplyBackup {
    @Mod.Instance("simplybackup")
    public static SimplyBackup instance;
    //Required instances (Used mainly to do stuff than to store data)
    protected Logger log;
    protected ProcessBuilder procBuilder;
    protected BackupThread thread = new BackupThread();
    //Data-storing variables, mainly of the type loaded once, used a lot.
    protected ArrayList<String> args = new ArrayList<String>(4);
    protected File outputDir;
    protected int autoTicks;
    protected int intervalTime;
    protected int keep;
    protected boolean backupOnExit;
    //Constants
    protected static final int secsToTicks = 20;
    //Constantly(?) changing ones.
    protected long nextSchedule;
    protected long antiSpamTime;
    protected boolean shouldResetSave;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        //1st : Setup Logger instance
        log = e.getModLog();
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
        intervalTime = config.getInt("interval", CATEGORY, 60000, 0, Integer.MAX_VALUE, "How many milliseconds should we wait before we should create another backup. Mainly used for the purpose of avoiding the spam of backups.");
        keep = config.getInt("keep", CATEGORY, -1, -1, Integer.MAX_VALUE, "How many backups to keep. Set to -1 to disable. Old backups created with this feature disabled will probably not count when you enable it.");
        backupOnExit = config.getBoolean("onexit", CATEGORY, true, "Whether to start a backup on exiting a world.");
        if (config.hasChanged()) config.save();
        //3rd : Do final preparations for the next steps (Mainly initialize procBuilder and check the sanity of the provided data).
        procBuilder = new ProcessBuilder(args);
        procBuilder.redirectErrorStream(true);//TODO Handle log output from the process in a better way.
        procBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        if (outputDir.isDirectory()) {
            log.info("Backup directory seems okay.");
        } else if (outputDir.mkdirs()) {
            log.info("Created backup directory.");
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
        thread.start();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {
            if (getTicks() >= nextSchedule) {
                sendChatMessage("Starting automatic backup.");
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
            log.info("Starting save on exit backup.");
            thread.triggerBackup(BackupTriggers.ONEXIT);
        } else {
            thread.triggerBackup(null);
        }
    }

    protected long getTicks() {
        return MinecraftServer.getServer().getTickCounter();
    }

    protected void resetTimers() {
        antiSpamTime = System.currentTimeMillis() + intervalTime;
        nextSchedule = getTicks() + autoTicks;
    }

    protected String getAntiSpamString() {
        long millis = antiSpamTime - System.currentTimeMillis();
        return millis < 0 ? "A new backup request can be made." : "You must wait " + String.format("%d min, %d sec", TimeUnit.MILLISECONDS.toMinutes(millis), TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(millis))) + " before a new backup request";
    }

    protected static void sendChatMessage(String s) {
        ServerConfigurationManager manager = MinecraftServer.getServer().getConfigurationManager();
        if (manager != null) {
            manager.sendChatMsg(new ChatComponentText(s));
        }
    }

    protected void saveOff() {
        sendChatMessage("Turning autosave off.");
    }

    protected void saveAll() {
        sendChatMessage("Saving data to disk.");
    }

    protected void saveReset() {
        sendChatMessage("Reseting autosave state.");
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
                sendChatMessage("Starting a manual backup...");
                thread.triggerBackup(BackupTriggers.MANUAL);
            } else {
                sendChatMessage((nextSchedule - getTicks()) + " ticks or " + ((nextSchedule - getTicks()) / 1200) + " minutes remaining until next scheduled backup.");
                sendChatMessage(getAntiSpamString());
            }
        }
    }

    class BackupThread extends Thread {
        private boolean shouldContinue;
        private boolean working;
        private BackupTriggers task;

        public BackupThread() {
            setName("BackupThread");
            setPriority(3);
            setDaemon(true);
        }

        protected void triggerBackup(BackupTriggers trig) {
            //Check special cases.
            if (working) {
                sendChatMessage("Can not start two backups at once");
                return;
            }
            if (trig == null || (trig == BackupTriggers.ONEXIT && System.currentTimeMillis() < antiSpamTime)) {
                shouldContinue = false;
                interrupt();
                return;
            }
            if (System.currentTimeMillis() < antiSpamTime) {
                sendChatMessage(getAntiSpamString());
                return;
            }
            //Now if the conditions allow it, start a backup.
            if (trig == BackupTriggers.ONEXIT) {
                shouldContinue = false;
            } else {
                saveOff();
                saveAll();
            }
            task = trig;
            resetTimers();
            interrupt();
        }

        @Override
        public synchronized void start() {
            //Here reset the state.
            shouldContinue = true;
            working = false;
            resetTimers();
            log.info("Starting backup thread.");
            super.start();
        }

        @Override
        public void run() {
            while (shouldContinue) {
                try {
                    synchronized (this) {
                        wait();
                    }
                } catch (InterruptedException ignored) {
                }
                if (task != null) {
                    working = true;
                    task = null;//TEST START
                    log.info("Now let the backup thread handle things.");
                    try {
                        sleep(8000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    if (shouldContinue) shouldResetSave = true;
                    log.info("Now is the time that the backup will be done.");//TEST END
                    working = false;
                }
            }
            log.info("Backup thread shutting down.");
        }
    }

    enum BackupTriggers {SCHEDULED, MANUAL, ONEXIT}
}
