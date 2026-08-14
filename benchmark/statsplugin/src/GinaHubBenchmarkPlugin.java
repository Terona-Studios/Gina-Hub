import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Criteria;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Collection;

/**
 * Phase "final proof" representative Hub workload: TAB header/footer, per-player sidebar
 * scoreboard, a lightweight action bar, and cosmetic particles for a fraction of players --
 * modeling the continuous background cost of the eventual real Gina Hub plugin, not a
 * finished feature set. Update intervals are deliberately non-aggressive (every 1-2s for
 * scoreboard/TAB, every 5s for action bar, every 2s for cosmetics) to stay representative
 * rather than intentionally worst-case. Not shipped in production; benchmark-only.
 */
public class GinaHubBenchmarkPlugin extends JavaPlugin {
    private Scoreboard sharedScoreboard;
    private Objective sidebar;
    private int tick = 0;

    @Override
    public void onEnable() {
        this.sharedScoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        this.sidebar = this.sharedScoreboard.registerNewObjective("hub", Criteria.DUMMY, Component.text("Gina Hub"));
        this.sidebar.setDisplaySlot(DisplaySlot.SIDEBAR);

        // Scoreboard + TAB update every 40 ticks (2s) -- realistic Hub refresh rate, not per-tick.
        new BukkitRunnable() {
            @Override
            public void run() {
                updateScoreboardAndTab();
            }
        }.runTaskTimer(this, 40L, 40L);

        // Action bar every 100 ticks (5s) -- one lightweight persistent Hub message.
        new BukkitRunnable() {
            @Override
            public void run() {
                Component msg = Component.text("Gina Hub - " + Bukkit.getOnlinePlayers().size() + " online");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.sendActionBar(msg);
                }
            }
        }.runTaskTimer(this, 100L, 100L);

        // Cosmetic particles every 40 ticks (2s) for ~25% of players (deterministic by UUID).
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if ((p.getUniqueId().hashCode() & 0x3) == 0) {
                        Location loc = p.getLocation().add(0, 2.2, 0);
                        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, loc, 3, 0.3, 0.3, 0.3, 0);
                    }
                }
            }
        }.runTaskTimer(this, 40L, 40L);

        getLogger().info("GinaHubBenchmarkPlugin enabled: TAB+scoreboard@2s, actionbar@5s, cosmetics@2s for ~25% of players");
    }

    private void updateScoreboardAndTab() {
        tick++;
        int online = Bukkit.getOnlinePlayers().size();
        Component header = Component.text("Gina Hub Network");
        Component footer = Component.text("play.ginahub.example  |  " + online + " online");

        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendPlayerListHeaderAndFooter(header, footer);
            if (p.getScoreboard() != this.sharedScoreboard) {
                p.setScoreboard(this.sharedScoreboard);
            }
        }

        for (String entry : this.sidebar.getScoreboard().getEntries()) {
            this.sidebar.getScoreboard().resetScores(entry);
        }
        this.sidebar.getScore("§bGina Hub Network").setScore(6);
        this.sidebar.getScore("§7Online: §f" + online).setScore(5);
        this.sidebar.getScore(" ").setScore(4);
        this.sidebar.getScore("§7Rank: §fMember").setScore(3);
        this.sidebar.getScore("§7Coins: §f0").setScore(2);
        this.sidebar.getScore("§7Lobby: §f1").setScore(1);
        this.sidebar.getScore("§bplay.ginahub.example").setScore(0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        World world = Bukkit.getWorlds().get(0);
        int players = Bukkit.getOnlinePlayers().size();
        Collection<org.bukkit.entity.Entity> entities = world.getEntities();
        int loadedChunks = world.getLoadedChunks().length;
        ThreadMXBean tmx = ManagementFactory.getThreadMXBean();
        int threads = tmx.getThreadCount();
        Runtime rt = Runtime.getRuntime();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();

        getLogger().info(String.format(
            "GINABENCH players=%d entities=%d loadedChunks=%d threads=%d heapUsedMB=%d heapMaxMB=%d",
            players, entities.size(), loadedChunks, threads, heapUsed / 1024 / 1024, heapMax / 1024 / 1024
        ));
        return true;
    }
}
