package com.actualplayer.rememberme;

import com.actualplayer.rememberme.handlers.*;
import com.actualplayer.rememberme.integrations.AjQueueSupport;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import lombok.Getter;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

@Plugin(
        id = "@ID@",
        name = "@NAME@",
        version = "@VERSION@",
        description = "@DESCRIPTION@",
        authors = {"ActualPlayer"},
        dependencies = {
                @Dependency(id = "luckperms", optional = true),
                @Dependency(id = "ajqueue", optional = true)
        }
)
public class RememberMe {

    private static final int AJQUEUE_QUEUE_RETRY_ATTEMPTS = 20;
    private static final long AJQUEUE_QUEUE_RETRY_DELAY_MILLIS = 200L;

    @Getter
    private final ProxyServer server;

    @Getter
    private final Logger logger;

    @Inject
    @DataDirectory
    @Getter
    private Path dataFolderPath;

    private IRememberMeHandler handler;

    private boolean hasLuckPerms;
    private boolean hasAjQueue;

    private AjQueueSupport ajQueueSupport;
    private final Map<UUID, String> pendingQueueTargets = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> pendingQueueRetryAttempts = new ConcurrentHashMap<>();

    @Inject()
    public RememberMe(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Inject(optional = true)
    public void initLuckPerms(@Named("luckperms")PluginContainer luckPermsContainer) {
        this.hasLuckPerms = luckPermsContainer != null;
    }

    @Inject(optional = true)
    public void initAjQueue(@Named("ajqueue") PluginContainer ajQueueContainer) {
        this.hasAjQueue = ajQueueContainer != null;
    }

    /**
     * If LuckPerms is present, use the User meta tags to save last server
     * @param event Velocity init event
     */
    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        if (hasLuckPerms) {
            LuckPerms api = LuckPermsProvider.get();
            handler = new LuckPermsHandler(api);
            getLogger().info("LuckPerms is installed, using LuckPerms meta-data to store last server info");
        } else {
            handler = new FileHandler(this);
            getLogger().info("Using file-based storage");
        }
        if (hasAjQueue) {
            ajQueueSupport = new AjQueueSupport(getLogger());
            getLogger().info("ajQueue is installed, remembered targets will be routed through ajQueue before direct joins");
        }
    }

    @Subscribe
    public void onServerChooseEvent(PlayerChooseInitialServerEvent chooseServerEvent) {
        // Ignore plugin when user has notransfer permission
        if (!chooseServerEvent.getPlayer().hasPermission("rememberme.notransfer")) {
            handler.getLastServerName(chooseServerEvent.getPlayer().getUniqueId()).thenAcceptAsync(lastServerName -> {
                if (lastServerName != null) {
                    getServer().getServer(lastServerName).ifPresent((registeredServer) -> {
                        try {
                            registeredServer.ping().join();
                        } catch (CancellationException | CompletionException exception) {
                            return;
                        }
                        if (hasAjQueue && ajQueueSupport != null) {
                            UUID playerId = chooseServerEvent.getPlayer().getUniqueId();
                            String targetServerName = registeredServer.getServerInfo().getName();
                            pendingQueueTargets.put(playerId, targetServerName);
                            pendingQueueRetryAttempts.remove(playerId);
                            return;
                        }
                        chooseServerEvent.setInitialServer(registeredServer);
                    });
                }
            }).join();
        }
    }

    @Subscribe
    public void onServerChange(ServerConnectedEvent serverConnectedEvent) {
        UUID playerId = serverConnectedEvent.getPlayer().getUniqueId();
        String currentServer = serverConnectedEvent.getServer().getServerInfo().getName();
        handler.setLastServerName(playerId, currentServer);

        if (!hasAjQueue || ajQueueSupport == null) {
            return;
        }

        String pendingTarget = pendingQueueTargets.get(playerId);
        if (pendingTarget == null) {
            return;
        }

        // If the player somehow landed on the target server directly, there is nothing to queue.
        if (pendingTarget.equalsIgnoreCase(currentServer)) {
            clearPendingQueueState(playerId);
            return;
        }

        startPendingQueueRetry(playerId, pendingTarget);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent disconnectEvent) {
        clearPendingQueueState(disconnectEvent.getPlayer().getUniqueId());
    }

    private void startPendingQueueRetry(UUID playerId, String targetServerName) {
        if (pendingQueueRetryAttempts.putIfAbsent(playerId, 0) != null) {
            return;
        }
        attemptPendingQueue(playerId, targetServerName, 0);
    }

    private void attemptPendingQueue(UUID playerId, String expectedTargetServerName, int attemptNumber) {
        if (!hasAjQueue || ajQueueSupport == null) {
            clearPendingQueueState(playerId);
            return;
        }

        String pendingTarget = pendingQueueTargets.get(playerId);
        if (pendingTarget == null || !pendingTarget.equalsIgnoreCase(expectedTargetServerName)) {
            pendingQueueRetryAttempts.remove(playerId);
            return;
        }

        boolean playerOnline = getServer().getPlayer(playerId).isPresent();
        if (!playerOnline) {
            clearPendingQueueState(playerId);
            return;
        }

        String currentServerName = getServer()
                .getPlayer(playerId)
                .flatMap(player -> player.getCurrentServer().map(connection -> connection.getServerInfo().getName()))
                .orElse(null);

        if (currentServerName != null && expectedTargetServerName.equalsIgnoreCase(currentServerName)) {
            clearPendingQueueState(playerId);
            return;
        }

        AjQueueSupport.QueueAttemptResult queueAttemptResult = ajQueueSupport.queueWhenReady(playerId, expectedTargetServerName);
        if (queueAttemptResult == AjQueueSupport.QueueAttemptResult.QUEUED ||
                queueAttemptResult == AjQueueSupport.QueueAttemptResult.ALREADY_QUEUED) {
            clearPendingQueueState(playerId);
            return;
        }

        if (queueAttemptResult == AjQueueSupport.QueueAttemptResult.NOT_QUEUE_SERVER) {
            clearPendingQueueState(playerId);
            connectDirectlyToRememberedTarget(playerId, expectedTargetServerName);
            return;
        }

        if (attemptNumber + 1 >= AJQUEUE_QUEUE_RETRY_ATTEMPTS) {
            clearPendingQueueState(playerId);
            getLogger().warn(
                    "rememberme failed to auto-queue player {} for target {} after {} attempts (last result: {})",
                    playerId,
                    expectedTargetServerName,
                    AJQUEUE_QUEUE_RETRY_ATTEMPTS,
                    queueAttemptResult
            );
            return;
        }

        int nextAttempt = attemptNumber + 1;
        pendingQueueRetryAttempts.put(playerId, nextAttempt);
        getServer().getScheduler()
                .buildTask(this, () -> attemptPendingQueue(playerId, expectedTargetServerName, nextAttempt))
                .delay(AJQUEUE_QUEUE_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                .schedule();
    }

    private void clearPendingQueueState(UUID playerId) {
        pendingQueueTargets.remove(playerId);
        pendingQueueRetryAttempts.remove(playerId);
    }

    private void connectDirectlyToRememberedTarget(UUID playerId, String targetServerName) {
        getServer().getPlayer(playerId).ifPresent(player ->
                getServer().getServer(targetServerName).ifPresent(targetServer ->
                        player.createConnectionRequest(targetServer).fireAndForget()
                )
        );
    }
}
