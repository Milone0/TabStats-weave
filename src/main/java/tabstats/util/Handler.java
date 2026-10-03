package tabstats.util;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Handler {
    private static final ExecutorService executorService = Executors.newFixedThreadPool(16,
        new ThreadFactoryBuilder().setNameFormat("TabStats-%d").setDaemon(true).build()
    );
    /**
     * Only keeps time: a delayed task is handed to the pool once it is due, so waiting for a
     * retry never occupies one of the pool threads that the actual requests run on.
     */
    private static final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        new ThreadFactoryBuilder().setNameFormat("TabStats-Scheduler").setDaemon(true).build()
    );
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    public static void asExecutor(Runnable runnable) {
        executorService.execute(logged(runnable));
    }

    /** Runs the task on the pool after {@code delayMs}, without blocking a pool thread meanwhile. */
    public static void schedule(Runnable runnable, long delayMs) {
        if (delayMs <= 0L) {
            asExecutor(runnable);
            return;
        }

        scheduler.schedule(() -> asExecutor(runnable), delayMs, TimeUnit.MILLISECONDS);
    }

    public static Gson getGson() {
        return GSON;
    }

    /** An exception escaping a pool task would otherwise vanish without a trace. */
    private static Runnable logged(Runnable runnable) {
        return () -> {
            try {
                runnable.run();
            } catch (Throwable t) {
                Log.error("Uncaught exception in a background task", t);
            }
        };
    }
}
