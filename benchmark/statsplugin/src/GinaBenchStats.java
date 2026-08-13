import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

/**
 * Development-only stats probe used by benchmark/scripts to get a consistent,
 * comparable snapshot of server-side state at each load point. Not shipped in
 * the production jar; loaded only into benchmark/server-test.
 *
 * Emits a single greppable log line: GINABENCH <key>=<value> ...
 */
public class GinaBenchStats extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        World world = Bukkit.getWorlds().get(0);
        int players = Bukkit.getOnlinePlayers().size();
        int entities = world.getEntities().size();
        int loadedChunks = world.getLoadedChunks().length;
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        int threads = tmx.getThreadCount();
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();

        getLogger().info(String.format(
            "GINABENCH players=%d entities=%d loadedChunks=%d threads=%d heapUsedMB=%d heapMaxMB=%d",
            players, entities, loadedChunks, threads, heapUsed / 1024 / 1024, heapMax / 1024 / 1024
        ));
        return true;
    }
}
