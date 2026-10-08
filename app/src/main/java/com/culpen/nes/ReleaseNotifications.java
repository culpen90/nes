package com.culpen.nes;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Opt-in release checks. Only public release metadata leaves the device. */
public final class ReleaseNotifications {
    static final String CHANNEL_ID = "new-releases";
    static final int PERIODIC_JOB_ID = 7301, CHECK_JOB_ID = 7302;
    static final long CHECK_INTERVAL = TimeUnit.HOURS.toMillis(6);
    private static final long LAUNCH_INTERVAL = TimeUnit.HOURS.toMillis(1);

    private ReleaseNotifications() { }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("release-notifications", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) { return prefs(context).getBoolean("enabled", false); }

    static synchronized void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("enabled", enabled).apply();
        if (enabled) { createChannel(context); schedule(context); requestCheck(context, true); }
        else {
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);
            scheduler.cancel(PERIODIC_JOB_ID); scheduler.cancel(CHECK_JOB_ID);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            for (android.service.notification.StatusBarNotification notification : manager.getActiveNotifications()) {
                if (notification.getTag() != null && notification.getTag().startsWith("release:"))
                    manager.cancel(notification.getTag(), notification.getId());
            }
        }
    }

    static void createChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "New versions", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("New Pocket NES beta and stable releases");
        context.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    static boolean canNotify(Context context) { return canNotify(context, CHANNEL_ID); }

    static boolean canNotify(Context context, String channelId) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return manager.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    static synchronized boolean schedule(Context context) {
        if (!isEnabled(context)) return false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        // Replacing a periodic job on every launch would keep moving its next check.
        if (scheduler.getPendingJob(PERIODIC_JOB_ID) != null) return true;
        try {
            return scheduler.schedule(job(context, PERIODIC_JOB_ID).setPeriodic(CHECK_INTERVAL)
                    .setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException failure) {
            Log.w("ReleaseNotifications", "Could not schedule release checks", failure);
            return false;
        }
    }

    static synchronized boolean requestCheck(Context context, boolean force) {
        if (!isEnabled(context) || !canNotify(context)) return false;
        long now = System.currentTimeMillis();
        SharedPreferences preferences = prefs(context);
        long last = preferences.getLong("last-successful-check", 0);
        if (!force && now >= last && now - last < LAUNCH_INTERVAL) return false;
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(CHECK_JOB_ID) != null) return true;
        long delay = Math.max(0, preferences.getLong("retry-at", 0) - now);
        try {
            return scheduler.schedule(job(context, CHECK_JOB_ID).setMinimumLatency(delay)
                    .setPersisted(true).build()) == JobScheduler.RESULT_SUCCESS;
        } catch (RuntimeException failure) {
            Log.w("ReleaseNotifications", "Could not request a release check", failure);
            return false;
        }
    }

    private static JobInfo.Builder job(Context context, int id) {
        return new JobInfo.Builder(id, new ComponentName(context, ReleaseCheckService.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(TimeUnit.MINUTES.toMillis(30), JobInfo.BACKOFF_POLICY_EXPONENTIAL);
    }

    static Intent browserIntent(String url) {
        Intent intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER);
        intent.setAction(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setData(Uri.parse(url));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    static int notifyReleases(Context context, List<ReleaseFeed.Release> releases) {
        return notifyReleases(context, releases, () -> true, CHANNEL_ID);
    }

    static synchronized int notifyReleases(Context context, List<ReleaseFeed.Release> releases,
                                          BooleanSupplier running, String channelId) {
        String installed;
        try { installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName; }
        catch (PackageManager.NameNotFoundException impossible) { return 0; }
        ReleaseVersion current = ReleaseVersion.parse(installed);
        if (current == null) return 0;
        SharedPreferences preferences = prefs(context);
        Set<String> notified = new HashSet<>(preferences.getStringSet("notified-tags", new HashSet<>()));
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        int count = 0;
        for (ReleaseFeed.Release release : releases) {
            if (!running.getAsBoolean() || Thread.currentThread().isInterrupted()
                    || !isEnabled(context) || !canNotify(context, channelId)) break;
            ReleaseVersion version = ReleaseVersion.parse(release.version);
            if (version == null || version.compareTo(current) <= 0 || notified.contains(release.tag)
                    || notified.contains("version:" + release.version)) continue;
            // Android limits rapid posts and the number of active notifications. Keep
            // the remainder pending, including when the user has a large backlog.
            if (count >= 3 || manager.getActiveNotifications().length >= 45) break;
            PendingIntent open = PendingIntent.getActivity(context, 0, browserIntent(release.url),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, channelId)
                    .setSmallIcon(R.drawable.ic_release_notification)
                    .setContentTitle("Pocket NES " + release.version + " is available")
                    .setContentText("Tap to view the release and download the update.")
                    .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_STATUS).build();
            if (!running.getAsBoolean() || Thread.currentThread().isInterrupted()) break;
            try { manager.notify("release:" + release.tag, 1, notification); }
            catch (SecurityException blocked) { break; }
            notified.add(release.tag);
            notified.add("version:" + release.version);
            // Commit on this background thread so a killed job does not forget an alert.
            if (!preferences.edit().putStringSet("notified-tags", notified).commit()) break;
            count++;
        }
        return count;
    }
}
