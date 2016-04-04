package sima.simplybackup;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.common.config.Configuration;

/**
 * The java file responsible for everything
 */
@Mod(modid = "simplybackup", version = "1.0", name = "Simply Backup", acceptedMinecraftVersions = "1.7.10")
public class SimplyBackup {
    @Mod.Instance("simplybackup")
    public static SimplyBackup instance;
    protected Configuration config;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        config = new Configuration(e.getSuggestedConfigurationFile());
        config.load();
        config.get("General", "command", "zip");
        if (config.hasChanged()) {
            config.save();
        }
    }
}
