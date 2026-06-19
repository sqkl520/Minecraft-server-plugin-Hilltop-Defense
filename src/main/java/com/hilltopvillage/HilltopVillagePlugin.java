package com.hilltopvillage;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.hilltopvillage.ai.HookClawHunterGoal;
import com.hilltopvillage.command.HilltopCommandHandler;
import com.hilltopvillage.command.HilltopTabCompleter;
import com.hilltopvillage.command.SetupWizard;
import com.hilltopvillage.config.menu.NumericInputHandler;
import com.hilltopvillage.doc.CommandsDocumentGenerator;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.mechanics.FireballListener;
import com.hilltopvillage.mechanics.HammerListener;
import com.hilltopvillage.mechanics.NodeListener;
import com.hilltopvillage.util.LanguageManager;

public class HilltopVillagePlugin extends JavaPlugin implements Listener {

    private GameManager gameManager;
    private HammerListener hammerListener;
    private NodeListener nodeListener;
    private FireballListener fireballListener;
    private LanguageManager languageManager;
    private SetupWizard setupWizard;
    private HilltopCommandHandler commandHandler;
    private HilltopTabCompleter tabCompleter;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.languageManager = new LanguageManager(this);
        this.gameManager = new GameManager(this);
        this.hammerListener = new HammerListener(gameManager);
        this.nodeListener = new NodeListener(gameManager);
        this.fireballListener = new FireballListener(gameManager);
        this.setupWizard = new SetupWizard(this);
        this.commandHandler = new HilltopCommandHandler(this);
        this.tabCompleter = new HilltopTabCompleter();

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(hammerListener, this);
        Bukkit.getPluginManager().registerEvents(nodeListener, this);
        Bukkit.getPluginManager().registerEvents(fireballListener, this);
        Bukkit.getPluginManager().registerEvents(new NumericInputHandler(), this);

        getCommand("hilltop").setTabCompleter(tabCompleter);

        new CommandsDocumentGenerator(this).generate();

        getLogger().info("Hilltop Village has been enabled!");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.stopGame();
            gameManager.getNodeSystem().stopBuffTask();
            gameManager.getDisplayEntityManager().cleanupAll();
        }

        getLogger().info("Hilltop Village has been disabled.");
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public GameManager getGameManager() {
        return gameManager;
    }

    public SetupWizard getSetupWizard() {
        return setupWizard;
    }

    public HammerListener getHammerListener() {
        return hammerListener;
    }

    public FireballListener getFireballListener() {
        return fireballListener;
    }

    public HilltopCommandHandler getCommandHandler() {
        return commandHandler;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)) return;
        Snowball snowball = (Snowball) event.getEntity();

        if (!"hook_claw".equals(snowball.getCustomName())) return;

        if (event.getHitEntity() instanceof Player) {
            Player hitPlayer = (Player) event.getHitEntity();
            HookClawHunterGoal.onHookHit(hitPlayer, snowball, gameManager);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMobDeath(EntityDeathEvent event) {
        if (!gameManager.isGameRunning()) return;

        if (gameManager.getWaveManager().getActiveMobs().contains(event.getEntity())) {
            gameManager.getWaveManager().onMobDeath(event.getEntity());

            Player killer = event.getEntity().getKiller();
            if (killer != null) {
                gameManager.getPlayerData(killer).addKill();
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (gameManager.getState() == com.hilltopvillage.core.GameState.WAITING
                || gameManager.getState() == com.hilltopvillage.core.GameState.STARTING) {
            gameManager.addPlayer(player);
            player.sendMessage(languageManager.getFor(player, "join-queue"));
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (gameManager.getPlayers().contains(player.getUniqueId())) {
            gameManager.removePlayer(player);
        }
        hammerListener.cleanupPlayer(player);
        fireballListener.cleanupPlayer(player);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        return commandHandler.handle(sender, command, label, args);
    }

}
