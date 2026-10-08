package com.culpen.nes;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Android owns wakeups and connectivity constraints; gameplay never waits for a check. */
public final class ReleaseCheckService extends JobService {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Check> checks = new HashMap<>();

    private static final class Check {
        volatile boolean stopped;
        Future<?> future;
    }

    @Override public boolean onStartJob(JobParameters parameters) {
        if (!ReleaseNotifications.isEnabled(this) || !ReleaseNotifications.canNotify(this)) return false;
        Check check = new Check();
        checks.put(parameters.getJobId(), check);
        check.future = worker.submit(() -> {
            boolean retry = false;
            try {
                if (System.currentTimeMillis() < ReleaseNotifications.prefs(this).getLong("retry-at", 0)) {
                    // Keep the job pending with scheduler backoff until the server
                    // permits requests, rather than consuming an early retry.
                    retry = true;
                    return;
                }
                String installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                List<ReleaseFeed.Release> releases = new ReleaseFeed().fetchNewer(installed);
                ReleaseNotifications.notifyReleases(this, releases, () -> !check.stopped, ReleaseNotifications.CHANNEL_ID);
                if (!check.stopped && ReleaseNotifications.isEnabled(this))
                    ReleaseNotifications.prefs(this).edit().putLong("last-successful-check", System.currentTimeMillis()).remove("retry-at").commit();
            } catch (ReleaseFeed.RateLimitedException limited) {
                ReleaseNotifications.prefs(this).edit().putLong("retry-at", limited.retryAtMillis).commit();
                retry = true;
            } catch (IOException failure) {
                Log.i("ReleaseCheckService", "Release check will retry: " + failure.getMessage());
                retry = true;
            } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
                Log.w("ReleaseCheckService", "Installed version is unavailable", impossible);
            } finally {
                final boolean needsRetry = retry;
                main.post(() -> {
                    if (checks.get(parameters.getJobId()) == check) {
                        checks.remove(parameters.getJobId());
                        if (!check.stopped) jobFinished(parameters, needsRetry);
                    }
                });
            }
        });
        return true;
    }

    @Override public boolean onStopJob(JobParameters parameters) {
        Check check = checks.remove(parameters.getJobId());
        if (check != null) stop(check);
        return ReleaseNotifications.isEnabled(this);
    }

    private static void stop(Check check) {
        // Use the same gate as posting: after cancellation returns, this run
        // cannot post or record any further notifications.
        synchronized (ReleaseNotifications.class) { check.stopped = true; }
        check.future.cancel(true);
    }

    @Override public void onDestroy() {
        for (Check check : checks.values()) stop(check);
        checks.clear(); worker.shutdownNow(); super.onDestroy();
    }
}
