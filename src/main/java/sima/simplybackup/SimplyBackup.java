package sima.simplybackup;

import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ChatComponentText;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.DigestOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.locks.LockSupport;

import static sima.simplybackup.SimplyBackup.OSType.*;

/**
 * The java file responsible for everything
 */
@Mod(modid = SimplyBackup.MODID, version = SimplyBackup.VERSION, name = SimplyBackup.NAME, acceptedMinecraftVersions = "1.8.9", acceptableRemoteVersions = "*")
public class SimplyBackup {
    public static final String MODID = "simplybackup";
    public static final String VERSION = "1.3.1";
    public static final String NAME = "Simply Backup";
    @Mod.Instance(MODID)
    public static SimplyBackup instance;
    //Required instances (Used mainly to do stuff than to store data)
    protected Logger forgeLog;
    protected ProcessBuilder procBuilder;
    protected ProcessBuilder testBuilder;
    protected BackupThread thread = new BackupThread();
    //Data-storing variables, mainly of the type loaded once, used a lot.
    protected List<String> args;
    protected List<String> testArgs;
    protected int outputIndex;
    protected int inputIndex;
    protected File backupDir;
    protected String extension;
    protected String worldName;
    protected int autoTicks;
    protected boolean check;
    protected int keep;
    protected boolean backupOnExit;
    protected boolean debug;
    protected WindowsSupport windowsSupport;
    //Constants
    protected static final int secsToTicks = 20;
    protected static final String OUTPUT = "OUTPUT";
    protected static final String INPUT = "INPUT";
    //Constantly(?) changing ones.
    protected long nextSchedule;
    protected boolean shouldResetSave;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        //1st : Setup Logger instance
        forgeLog = event.getModLog();
        //2nd : Load configuration while setting up the arguments for the process doing the compressing.
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        String[] cmd;
        String[] testCmd = new String[]{"unzip", "-tqq", INPUT};
        if (OS == MAC) {
            cmd = new String[]{"zip", "-q", "-r", "-X", "-5", OUTPUT, "./"};
        } else {
            cmd = new String[]{"zip", "-q", "-r", "-5", OUTPUT, "./"};
        }
        if (OS == WINDOWS) {
            cmd[0] = cmd[0] + ".exe";
            testCmd[0] = testCmd[0] + ".exe";
        }
        args = Arrays.asList(config.getStringList("args", NAME, cmd, "The arguments used to launch the process for compressing.\nThe process is launched with its path set to the world folder currently getting backed up.\nAt the provided list there has to be exactly one \"OUTPUT\" element, or else this won't work.\n"));
        outputIndex = args.indexOf(OUTPUT);
        if (outputIndex == -1)
            throw new IllegalArgumentException("There is not an OUTPUT element! Please check your config: " + config.getConfigFile().getName());
        check = config.getBoolean("check", NAME, true, "Enables extra checking before the backup, with the purpose of deleting corrupted backups.\nMight not be available for all compressors.");
        if (check) {
            testArgs = Arrays.asList(config.getStringList("test_args", NAME, testCmd, "The arguments used to launch the process for testing.\nThe provided list must have exactly one \"INPUT\" element, which is going to be replaced with the archive being tested."));
            inputIndex = testArgs.indexOf(INPUT);
            if (inputIndex == -1)
                throw new IllegalArgumentException("There is not an INPUT element! Please check your config: " + config.getConfigFile().getName());
        }
        backupDir = new File(config.getString("Backup output directory", NAME, new File(".", "backup").getPath(), "The folder where to store the backups. Must be a string which can be translated into a directory by java.io.File")).getAbsoluteFile();
        extension = config.getString("extension", NAME, "zip", "The extensions the process uses.");
        autoTicks = config.getInt("auto_ticks", NAME, secsToTicks * 60 * 60, -1, Integer.MAX_VALUE, "How many ticks to wait before we start a new automatic backup. Set to -1 to disable automatic backups.");
        keep = config.getInt("keep", NAME, -1, -1, Integer.MAX_VALUE, "How many backups to keep. Set to -1 to disable. Old backups created with this feature disabled will probably not count when you enable it.");
        backupOnExit = config.getBoolean("on_exit", NAME, false, "Whether to create a backup on exiting a world.");
        debug = config.getBoolean("debug", NAME, true, "Enables some extra logging, necessary for debugging. Mostly harmless if left activated.");
        if (OS == WINDOWS && config.getBoolean("causeWindows", NAME, true, "My last attempt at supporting Windows. Needs internet access. Downloads the required zip executables from the project's GitHub repository.")) {
            windowsSupport = new WindowsSupport();
        }
        if (config.hasChanged()) config.save();
        //3rd : Do final preparations for the next steps (Mainly initialize procBuilder and check the sanity of the provided data).
        procBuilder = new ProcessBuilder(args);
        procBuilder.redirectErrorStream(true);//Potential Improvement : Handle log output from the process in a better way.
        procBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        if (check) {
            testBuilder = new ProcessBuilder(testArgs);
            testBuilder.redirectErrorStream(true);
            testBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        }
        if (backupDir.isDirectory()) {
            log(Level.DEBUG, "Backup directory seems okay.");
        } else if (backupDir.mkdirs()) {
            log(Level.DEBUG, "Created backup directory.");
        } else throw new RuntimeException("Could not create backup output directory.");
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent ev) {
        MinecraftForge.EVENT_BUS.register(this);
        if (windowsSupport != null) {
            while (windowsSupport.getState() != Thread.State.TERMINATED) {
                log(Level.INFO, "Waiting for " + windowsSupport.getName() + " thread to terminate.");
                try {
                    windowsSupport.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            windowsSupport = null;
        }
    }

    @Mod.EventHandler
    public void serverStart(FMLServerStartingEvent e) throws IOException {
        e.registerServerCommand(new BackupCommand());
        worldName = e.getServer().getWorldName();
        procBuilder.directory(DimensionManager.getCurrentSaveRootDirectory().getCanonicalFile());
        resetTimers();
    }

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.START) {
            if (autoTicks > 0 && getTicks() >= nextSchedule) {
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
        if (backupOnExit) {
            log(Level.INFO, "Starting save on exit backup.");
            thread.triggerBackup(BackupTriggers.ONEXIT);
        }
    }

    protected long getTicks() {
        return MinecraftServer.getServer().getTickCounter();
    }

    protected void resetTimers() {
        if (autoTicks > 0)
            nextSchedule = getTicks() + autoTicks;
    }

    private Level CHAT_THRESHOLD = Level.INFO;

    protected void log(Level l, String s) {
        if (debug) {
            forgeLog.log(l, s);
        }
        if (l.isAtLeastAsSpecificAs(CHAT_THRESHOLD)) {
            MinecraftServer server = MinecraftServer.getServer();
            if (server != null) {
                ServerConfigurationManager manager = MinecraftServer.getServer().getConfigurationManager();
                if (manager != null) {
                    manager.sendChatMsg(new ChatComponentText(getColorByLevel(l.intLevel()) + s));
                }
            }
        }
    }

    private String getColorByLevel(int level) {
        switch (level) {
            case 1:
                return "§4";
            case 2:
                return "§c";
            case 3:
                return "§5";
            case 4:
                return "§9";
            case 5:
                return "§2";
            default:
                return "§f";
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
                if (cur.disableLevelSaving) {
                    state.set(i);
                } else {
                    cur.disableLevelSaving = true;
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
            cur.disableLevelSaving = state.get(i);
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
        public List<String> addTabCompletionOptions(ICommandSender sender, String[] args, BlockPos pos) {
            return Collections.singletonList("start");
        }

        @Override
        public void processCommand(ICommandSender user, String[] args) {
            if (args.length > 0 && args[0].equals("start")) {
                if (user != null) {
                    log(Level.INFO, user.getName() + " started a manual backup...");
                } else {
                    log(Level.INFO, "Starting a manual backup...");
                }
                thread.triggerBackup(BackupTriggers.MANUAL);
            } else {
                if (autoTicks > 0) {
                    log(Level.INFO, (((nextSchedule - getTicks()) / 1200) + " minutes (" + (nextSchedule - getTicks()) + " ticks) until next scheduled backup."));
                } else {
                    log(Level.INFO, "Automatic backups are disabled by config.");
                }
            }
        }
    }

    private static SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");

    class BackupThread extends Thread {
        private BackupTriggers task;

        public BackupThread() {
            setName("BackupThread");
            setPriority(3);
            setDaemon(true);
            start();
        }

        protected void triggerBackup(BackupTriggers trig) {
            if (task != null || shouldResetSave) {
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
                handleFiles();
                Process process;
                try {
                    process = procBuilder.start();
                    if (task != BackupTriggers.ONEXIT) {
                        if (process.waitFor() != 0) {
                            log(Level.ERROR, "An error occurred with the process. Backup failed.");
                        } else {
                            log(Level.INFO, "Backup completed successfully.");
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
                if (task != BackupTriggers.ONEXIT) {
                    shouldResetSave = true;
                }
                task = null;
            }
        }

        private void handleFiles() {
            File persist = new File(backupDir, worldName + "_list.txt");
            LinkedList<File> list = new LinkedList<>();
            if (persist.exists()) {//Then load it.
                try (BufferedReader in = new BufferedReader(new FileReader(persist))) {
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (!"".equals(line)) {
                            File temp = new File(backupDir, line);
                            if (temp.exists() && extraCheck(temp)) {
                                list.add(temp);
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            File newFile = new File(backupDir, worldName + '-' + dateFormat.format(new Date()) + '.' + extension);
            list.addLast(newFile);
            if (keep > 0) {
                ListIterator<File> iterator = list.listIterator();
                while (iterator.hasNext() && list.size() > keep) {
                    File cur = iterator.next();
                    if (cur.delete()) {
                        iterator.remove();
                        log(Level.DEBUG, "Successfully deleted: " + cur.getName());
                    } else {
                        log(Level.ERROR, "Could not delete: " + cur.getName());
                    }
                }
            }
            try (BufferedWriter out = new BufferedWriter(new FileWriter(persist))) {
                File cur;
                while ((cur = list.removeFirst()) != null) {
                    out.write(cur.getName());
                    if (list.size() == 0) break;
                    out.newLine();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            args.set(outputIndex, newFile.getPath());
        }

        private boolean extraCheck(File input) {
            if (check) {
                try {
                    testArgs.set(inputIndex, input.getAbsolutePath());
                    Process process = testBuilder.start();
                    if (process.waitFor() != 0) {
                        log(Level.DEBUG, "Deleting corrupted archive: " + input.getName());
                        input.delete();
                        return false;
                    }
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            }
            return true;
        }
    }

    class WindowsSupport extends Thread {
        private final File cacheFolder = new File(".", "simplybackup_cache");

        public WindowsSupport() {
            setName("Zip exe downloader(SimplyBackup)");
            setDaemon(true);
            if (!cacheFolder.isDirectory()) {
                cacheFolder.mkdirs();
            }
            start();
        }

        @Override
        public void run() {
            //First step: try and download info file.
            BufferedReader infoReader = null;
            boolean streamOpened = false;
            boolean internetConnection = false;
            File infoCacheFile = new File(cacheFolder, "simplybackup_zip.info");
            try {
                URL info = new URL("https://raw.githubusercontent.com/Sima214/SimplyBackup/master/causeWindows/simplybackup_zip.info");
                URLConnection connection = info.openConnection();
                connection.setConnectTimeout(1000);
                infoReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                infoReader.mark(8192);
                internetConnection = true;
                streamOpened = true;
                BufferedWriter writer = new BufferedWriter(new FileWriter(infoCacheFile));
                char[] buffer = new char[1024];
                int lastRead;
                while ((lastRead = infoReader.read(buffer)) != -1) {
                    writer.write(buffer, 0, lastRead);
                }
                writer.close();
            } catch (MalformedURLException e) {
                log(Level.DEBUG, "Could not create url.");
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
            //Else try and load  the info file from the cache folder.
            try {
                if (!streamOpened) {
                    infoReader = new BufferedReader(new FileReader(infoCacheFile));
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
                log(Level.FATAL, "Could not load: " + infoCacheFile.getName());
                return;
            }
            //Now download the executables, if necessary.
            try {
                if (internetConnection) {
                    infoReader.reset();
                }
                String line;
                String[] lineTokens;
                while ((line = infoReader.readLine()) != null && line.length() > 0) {
                    lineTokens = line.split(" ");
                    File curFile = new File(cacheFolder, lineTokens[0]);
                    if (curFile.isFile()) {
                        //Check if hash of cached file matches the one provided from the info file.
                        FileInputStream inputStream = new FileInputStream(curFile);
                        if (DigestUtils.md5Hex(inputStream).equalsIgnoreCase(lineTokens[1])) {
                            inputStream.close();
                            log(Level.INFO, "Using " + lineTokens[0] + " from cache.");
                            continue;
                            //If the check succeeds, then just move on the next file.
                        }
                        inputStream.close();
                    }
                    //When we reach this point, we need to download from the link and check the hashes at the same time.
                    URL curUrl = new URL(lineTokens[2]);
                    URLConnection connection = curUrl.openConnection();
                    connection.setConnectTimeout(1000);
                    BufferedInputStream source = new BufferedInputStream(connection.getInputStream());
                    DigestOutputStream output = new DigestOutputStream(new FileOutputStream(curFile), DigestUtils.getMd5Digest());
                    byte[] buffer = new byte[1024];
                    int lastRead;
                    while ((lastRead = source.read(buffer)) != -1) {
                        output.write(buffer, 0, lastRead);
                    }
                    if (Hex.encodeHexString(output.getMessageDigest().digest()).equalsIgnoreCase(lineTokens[1])) {
                        log(Level.DEBUG, "Downloaded successfully: " + lineTokens[0]);
                    } else {
                        log(Level.ERROR, "Hashes do not match for: " + lineTokens[0]);
                        return;
                    }
                    source.close();
                    output.close();
                }
                infoReader.close();
                args.set(0, new File(cacheFolder, args.get(0)).getCanonicalPath());
                testArgs.set(0, new File(cacheFolder, testArgs.get(0)).getCanonicalPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    enum BackupTriggers {SCHEDULED, MANUAL, ONEXIT}

    enum OSType {
        LINUX, MAC, WINDOWS, UNKNOWN;
        static final OSType OS = getOperatingSystem();

        private static OSType getOperatingSystem() {
            String name = System.getProperty("os.name", "generic").toLowerCase(Locale.ENGLISH);
            if (name.startsWith("windows"))
                return WINDOWS;
            if (name.startsWith("linux"))
                return LINUX;
            if (name.startsWith("mac") || name.startsWith("darwin"))
                return MAC;
            return UNKNOWN;
        }
    }
}
