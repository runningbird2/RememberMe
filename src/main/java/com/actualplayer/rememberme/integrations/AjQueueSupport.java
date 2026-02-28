package com.actualplayer.rememberme.integrations;

import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class AjQueueSupport {

    public enum QueueAttemptResult {
        QUEUED,
        ALREADY_QUEUED,
        NOT_READY,
        NOT_QUEUE_SERVER,
        FAILED
    }

    private final Logger logger;
    private volatile boolean warningLogged = false;

    public AjQueueSupport(Logger logger) {
        this.logger = logger;
    }

    public boolean isJoinable(UUID playerId, String serverName) {
        try {
            Class<?> apiClass = Class.forName("us.ajg0702.queue.api.AjQueueAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                logWarning("ajQueue API is not ready. Falling back to Velocity try order for remembered joins.", null);
                return false;
            }

            Object platformMethods = apiClass.getMethod("getPlatformMethods").invoke(api);
            if (platformMethods == null) {
                logWarning("ajQueue PlatformMethods is unavailable. Falling back to Velocity try order for remembered joins.", null);
                return false;
            }

            Class<?> platformMethodsClass = Class.forName("us.ajg0702.queue.api.PlatformMethods");
            Method getPlayerMethod = platformMethodsClass.getMethod("getPlayer", UUID.class);
            Object adaptedPlayer = getPlayerMethod.invoke(platformMethods, playerId);
            if (adaptedPlayer == null) {
                logWarning("ajQueue did not resolve an AdaptedPlayer. Falling back to Velocity try order for remembered joins.", null);
                return false;
            }

            Object queueManager = apiClass.getMethod("getQueueManager").invoke(api);
            if (queueManager == null) {
                logWarning("ajQueue QueueManager is unavailable. Falling back to Velocity try order for remembered joins.", null);
                return false;
            }

            Class<?> queueManagerClass = Class.forName("us.ajg0702.queue.api.QueueManager");
            Object queueServer = queueManagerClass.getMethod("findServer", String.class).invoke(queueManager, serverName);
            if (queueServer == null) {
                // If ajQueue doesn't know this server, preserve original RememberMe behavior.
                return true;
            }

            Class<?> queueServerClass = Class.forName("us.ajg0702.queue.api.queues.QueueServer");
            Class<?> adaptedPlayerClass = Class.forName("us.ajg0702.queue.api.players.AdaptedPlayer");
            Object result = queueServerClass.getMethod("isJoinable", adaptedPlayerClass).invoke(queueServer, adaptedPlayer);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException e) {
            logWarning(
                    "Failed to query ajQueue joinability for remembered server '" + serverName + "'. " +
                            "Falling back to Velocity try order.",
                    e
            );
            return false;
        }
    }

    public boolean addToQueue(UUID playerId, String serverName) {
        try {
            Class<?> apiClass = Class.forName("us.ajg0702.queue.api.AjQueueAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                logWarning("ajQueue API is not ready. Could not auto-queue remembered fallback.", null);
                return false;
            }

            Object platformMethods = apiClass.getMethod("getPlatformMethods").invoke(api);
            if (platformMethods == null) {
                logWarning("ajQueue PlatformMethods is unavailable. Could not auto-queue remembered fallback.", null);
                return false;
            }

            Class<?> platformMethodsClass = Class.forName("us.ajg0702.queue.api.PlatformMethods");
            Method getPlayerMethod = platformMethodsClass.getMethod("getPlayer", UUID.class);
            Object adaptedPlayer = getPlayerMethod.invoke(platformMethods, playerId);
            if (adaptedPlayer == null) {
                logWarning("ajQueue did not resolve an AdaptedPlayer. Could not auto-queue remembered fallback.", null);
                return false;
            }

            Object queueManager = apiClass.getMethod("getQueueManager").invoke(api);
            if (queueManager == null) {
                logWarning("ajQueue QueueManager is unavailable. Could not auto-queue remembered fallback.", null);
                return false;
            }

            Class<?> queueManagerClass = Class.forName("us.ajg0702.queue.api.QueueManager");
            Class<?> adaptedPlayerClass = Class.forName("us.ajg0702.queue.api.players.AdaptedPlayer");
            Object result = queueManagerClass
                    .getMethod("addToQueue", adaptedPlayerClass, String.class)
                    .invoke(queueManager, adaptedPlayer, serverName);
            return Boolean.TRUE.equals(result);
        } catch (ReflectiveOperationException e) {
            logWarning(
                    "Failed to add player to ajQueue for remembered server '" + serverName + "'.",
                    e
            );
            return false;
        }
    }

    public QueueAttemptResult queueWhenReady(UUID playerId, String serverName) {
        try {
            Class<?> apiClass = Class.forName("us.ajg0702.queue.api.AjQueueAPI");
            Object api = apiClass.getMethod("getInstance").invoke(null);
            if (api == null) {
                return QueueAttemptResult.NOT_READY;
            }

            Object platformMethods = apiClass.getMethod("getPlatformMethods").invoke(api);
            if (platformMethods == null) {
                return QueueAttemptResult.NOT_READY;
            }

            Class<?> platformMethodsClass = Class.forName("us.ajg0702.queue.api.PlatformMethods");
            Method getPlayerMethod = platformMethodsClass.getMethod("getPlayer", UUID.class);
            Object adaptedPlayer = getPlayerMethod.invoke(platformMethods, playerId);
            if (adaptedPlayer == null) {
                return QueueAttemptResult.NOT_READY;
            }

            Class<?> adaptedPlayerClass = Class.forName("us.ajg0702.queue.api.players.AdaptedPlayer");
            Object connected = adaptedPlayerClass.getMethod("isConnected").invoke(adaptedPlayer);
            if (!Boolean.TRUE.equals(connected)) {
                return QueueAttemptResult.NOT_READY;
            }

            Object currentServer = adaptedPlayerClass.getMethod("getServerName").invoke(adaptedPlayer);
            if (!(currentServer instanceof String) || ((String) currentServer).trim().isEmpty()) {
                return QueueAttemptResult.NOT_READY;
            }

            Object queueManager = apiClass.getMethod("getQueueManager").invoke(api);
            if (queueManager == null) {
                return QueueAttemptResult.NOT_READY;
            }

            Class<?> queueManagerClass = Class.forName("us.ajg0702.queue.api.QueueManager");
            Object queueServer = queueManagerClass.getMethod("findServer", String.class).invoke(queueManager, serverName);
            if (queueServer == null) {
                return QueueAttemptResult.NOT_QUEUE_SERVER;
            }

            if (isAlreadyQueued(queueManagerClass, queueManager, adaptedPlayerClass, adaptedPlayer, serverName)) {
                return QueueAttemptResult.ALREADY_QUEUED;
            }

            Object queued = queueManagerClass
                    .getMethod("addToQueue", adaptedPlayerClass, String.class)
                    .invoke(queueManager, adaptedPlayer, serverName);
            if (Boolean.TRUE.equals(queued)) {
                return QueueAttemptResult.QUEUED;
            }

            if (isAlreadyQueued(queueManagerClass, queueManager, adaptedPlayerClass, adaptedPlayer, serverName)) {
                return QueueAttemptResult.ALREADY_QUEUED;
            }
            return QueueAttemptResult.FAILED;
        } catch (ReflectiveOperationException e) {
            logWarning(
                    "Failed to auto-queue remembered player for server '" + serverName + "'.",
                    e
            );
            return QueueAttemptResult.FAILED;
        }
    }

    private boolean isAlreadyQueued(
            Class<?> queueManagerClass,
            Object queueManager,
            Class<?> adaptedPlayerClass,
            Object adaptedPlayer,
            String serverName
    ) throws ReflectiveOperationException {
        Object queues = queueManagerClass.getMethod("getPlayerQueues", adaptedPlayerClass).invoke(queueManager, adaptedPlayer);
        if (!(queues instanceof List<?>)) {
            return false;
        }

        Class<?> queueServerClass = Class.forName("us.ajg0702.queue.api.queues.QueueServer");
        Method getNameMethod = queueServerClass.getMethod("getName");
        String target = serverName.toLowerCase(Locale.ROOT);
        for (Object queue : (List<?>) queues) {
            if (queue == null) {
                continue;
            }
            Object name = getNameMethod.invoke(queue);
            if (name instanceof String && ((String) name).toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    private void logWarning(String message, Throwable throwable) {
        if (warningLogged) {
            return;
        }

        synchronized (this) {
            if (warningLogged) {
                return;
            }
            warningLogged = true;
        }

        if (throwable == null) {
            logger.warn(message);
        } else {
            logger.warn(message, throwable);
        }
    }
}
