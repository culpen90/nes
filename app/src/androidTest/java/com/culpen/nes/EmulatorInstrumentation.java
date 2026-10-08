package com.culpen.nes;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real-device tests without a JUnit or AndroidX dependency. No Activity is launched. */
public final class EmulatorInstrumentation extends Instrumentation {
    private static final int TEST_COUNT = 14;
    private static final String FIRST_TEST_VERSION = "999999.0.1";
    private static final String SECOND_TEST_VERSION = "999999.0.2";
    private final short[] pcm = new short[32768];
    private File workDirectory;
    private File demo;
    private byte[] demoBytes;
    private int passed;
    private int failed;
    private int current;
    private final StringBuilder report = new StringBuilder();

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        new Thread(this::runTests, "NES device tests").start();
    }

    private void runTests() {
        try {
            workDirectory = new File(getTargetContext().getCacheDir(), "nes-device-tests");
            check(workDirectory.isDirectory() || workDirectory.mkdirs(), "Cannot create test cache");
            try (InputStream in = getTargetContext().getAssets().open("demo.nes")) {
                demoBytes = readAll(in);
            }
            demo = writeFile("original-demo.nes", demoBytes);
            test("demo produces video and stereo PCM", this::testVideoAndPcm);
            test("B button produces an audible chirp", this::testChirp);
            test("controller input changes the visible explorer", this::testMovement);
            test("save state replays identical video and restores stereo audio", this::testStateDeterminism);
            test("malformed ROM and truncated state are rejected", this::testBadInputs);
            test("unload and reload remain playable", this::testReload);
            test("multitouch slides and cancellation release buttons", this::testGamepad);
            test("release versions order numbered betas and stable versions", this::testReleaseVersions);
            test("release feed accepts only complete newer official releases", this::testReleaseFeed);
            test("malformed release feeds are rejected without partial results", this::testMalformedReleaseFeed);
            test("release rate limits delay retries without overflowing", this::testReleaseRateLimits);
            test("release scheduling persists and disabling cancels checks", this::testReleaseScheduling);
            test("release notifications coexist and each version alerts once", this::testReleaseDelivery);
            test("blocked or stopped release notifications remain pending", this::testBlockedReleaseDelivery);
        } catch (Throwable failure) {
            failed++;
            report.append("SETUP FAILED: ").append(stackTrace(failure));
            status(-2, "setup", "SETUP FAILED: " + stackTrace(failure));
        } finally {
            try { NativeNes.unload(); } catch (Throwable ignored) { }
            deleteWorkFiles();
            Bundle results = new Bundle();
            results.putInt("testsPassed", passed);
            results.putInt("testsFailed", failed);
            results.putInt("testsTotal", passed + failed);
            results.putString("stream", "\n" + report + "\nPassed " + passed + ", failed " + failed + ".\n");
            finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, results);
        }
    }

    private void test(String name, CheckedRunnable body) {
        current++;
        status(1, name, "RUN " + name + "\n");
        try {
            NativeNes.unload();
            body.run();
            passed++;
            report.append("PASS: ").append(name).append('\n');
            status(0, name, "PASS " + name + "\n");
        } catch (Throwable failure) {
            failed++;
            String detail = stackTrace(failure);
            report.append("FAIL: ").append(name).append('\n').append(detail);
            Bundle result = statusBundle(name, "FAIL " + name + "\n" + detail);
            result.putString("stack", detail);
            sendStatus(-2, result);
        } finally {
            NativeNes.unload();
        }
    }

    private void testVideoAndPcm() {
        loadDemo();
        check(NativeNes.width() == 256 && NativeNes.height() == 240,
                "NES framebuffer should have the complete 256 × 240 image");
        check(NativeNes.fps() > 49 && NativeNes.fps() < 61, "Invalid NES frame rate");
        check(NativeNes.sampleRate() >= 22050 && NativeNes.sampleRate() <= 192000,
                "Invalid PCM sample rate");
        long samples = 0;
        for (int i = 0; i < 90; i++) {
            NativeNes.runFrame(0);
            int count = NativeNes.drainAudio(pcm);
            check(count > 0 && count % 2 == 0, "A frame should emit complete stereo PCM pairs");
            samples += count;
        }
        double expected = 90 * NativeNes.sampleRate() * 2 / NativeNes.fps();
        check(samples > expected * .90 && samples < expected * 1.10,
                "Audio sample count does not match the emulated frame rate: " + samples);
        assertVisibleGame(copyVideo());
        check(NativeNes.drainAudio(pcm) == 0, "Draining audio should consume its samples");
        boolean rejected = false;
        try { NativeNes.copyVideo(new int[10]); }
        catch (IllegalArgumentException expectedFailure) { rejected = true; }
        check(rejected, "A undersized video destination must be rejected");
    }

    private void testChirp() {
        loadDemo();
        runFrames(90, 0);
        int loudest = 0;
        long totalEnergy = 0;
        long samples = 0;
        for (int frame = 0; frame < 16; frame++) {
            NativeNes.runFrame(frame == 0 ? GamepadView.B : 0);
            int count = NativeNes.drainAudio(pcm);
            samples += count;
            for (int i = 0; i < count; i++) {
                int magnitude = Math.abs((int) pcm[i]);
                loudest = Math.max(loudest, magnitude);
                totalEnergy += (long) magnitude * magnitude;
            }
        }
        check(samples > 0, "The chirp emitted no PCM");
        check(loudest > 64 && totalEnergy > 100000,
                "B input did not produce an audible waveform: peak " + loudest);
    }

    private void testMovement() {
        loadDemo();
        runFrames(90, 0);
        byte[] checkpoint = requireState();
        runFrames(16, 0);
        int[] still = copyVideo();
        check(NativeNes.loadState(checkpoint), "Cannot restore movement checkpoint");
        runFrames(16, GamepadView.RIGHT);
        int[] moved = copyVideo();
        check(differentPixels(still, moved) > 100,
                "Holding Right did not visibly move the demo explorer");
        check(NativeNes.loadState(checkpoint), "Cannot restore boost checkpoint");
        runFrames(16, GamepadView.RIGHT | GamepadView.A);
        int[] boosted = copyVideo();
        check(differentPixels(moved, boosted) > 100,
                "Holding A and Right did not visibly change movement speed");
        check(NativeNes.loadState(checkpoint), "Cannot restore opposite-direction checkpoint");
        runFrames(16, GamepadView.LEFT);
        check(differentPixels(moved, copyVideo()) > 100,
                "Left and Right produced the same visible movement");
    }

    private void testStateDeterminism() {
        loadDemo();
        runFrames(90, 0);
        byte[] checkpoint = requireState();
        List<FrameSample> expected = replayInputSequence();
        check(NativeNes.loadState(checkpoint), "A valid saved state did not restore");
        List<FrameSample> actual = replayInputSequence();
        check(expected.size() == actual.size(), "Replay lengths differ");
        long expectedSamples = 0, actualSamples = 0, restoredEnergy = 0;
        for (int i = 0; i < expected.size(); i++) {
            FrameSample first = expected.get(i);
            FrameSample second = actual.get(i);
            check(Arrays.equals(first.video, second.video),
                    "Restored video diverged at replay frame " + i);
            // Upstream serializes the APU, but not NeoFilterSound's fractional
            // mrindex or WaveHi FIR history (filter.c, sound.c). The contract is
            // restored game/video and sound, not sample-identical PCM replay.
            check(second.audio.length > 0 && second.audio.length % 2 == 0,
                    "Restored audio must contain complete stereo frames");
            double ideal = NativeNes.sampleRate() * 2 / NativeNes.fps();
            check(Math.abs(second.audio.length - ideal) <= 4,
                    "Restored PCM duration differs from one emulated frame at " + i);
            expectedSamples += first.audio.length; actualSamples += second.audio.length;
            for (short sample : second.audio) restoredEnergy += (long)sample * sample;
        }
        check(Math.abs(actualSamples - expectedSamples) <= 4,
                "Restored audio accumulated a timing error across the replay");
        check(restoredEnergy > 100000, "The restored B-button chirp produced no sound");
    }

    private List<FrameSample> replayInputSequence() {
        List<FrameSample> frames = new ArrayList<>();
        for (int frame = 0; frame < 64; frame++) {
            int input = 0;
            if (frame < 8) input |= GamepadView.RIGHT | GamepadView.A;
            if (frame >= 8 && frame < 16) input |= GamepadView.UP;
            if (frame == 10) input |= GamepadView.B;
            if (frame >= 18 && frame < 26) input |= GamepadView.DOWN | GamepadView.LEFT;
            NativeNes.runFrame(input);
            int count = NativeNes.drainAudio(pcm);
            frames.add(new FrameSample(copyVideo(), Arrays.copyOf(pcm, count)));
        }
        return frames;
    }

    private void testBadInputs() throws Exception {
        loadDemo();
        runFrames(30, 0);
        byte[] state = requireState();
        check(!NativeNes.loadState(null), "Null state was accepted");
        check(!NativeNes.loadState(new byte[8]), "Tiny state was accepted");
        check(!NativeNes.loadState(Arrays.copyOf(state, state.length - 1)),
                "A state truncated by one byte was accepted");
        byte[] wrongState = state.clone();
        wrongState[0] ^= 0x7f;
        check(!NativeNes.loadState(wrongState), "A state with invalid signature was accepted");
        check(NativeNes.loadState(state), "Rejected states damaged subsequent valid restoration");
        runFrames(2, 0);
        assertVisibleGame(copyVideo());

        byte[] wrongRom = demoBytes.clone();
        wrongRom[0] = 'X';
        expectInvalidRom(wrongRom, "wrong-signature.nes");
        expectInvalidRom(Arrays.copyOf(demoBytes, 16), "truncated-cartridge.nes");
        expectInvalidRom(new byte[2], "tiny-cartridge.nes");
        // Validation failure should leave the running cartridge usable.
        runFrames(3, GamepadView.LEFT);
        assertVisibleGame(copyVideo());
        LibraryStore.validateRom(demoBytes);
    }

    private void expectInvalidRom(byte[] bytes, String fileName) throws Exception {
        boolean rejected = false;
        try { LibraryStore.validateRom(bytes); }
        catch (IOException expected) { rejected = true; }
        check(rejected, "The import validator accepted " + fileName);
        File file = writeFile(fileName, bytes);
        String error = NativeNes.load(file.getAbsolutePath(), workDirectory.getAbsolutePath(),
                workDirectory.getAbsolutePath());
        check(error != null && !error.trim().isEmpty(), "The core accepted " + fileName);
    }

    private void testReload() {
        for (int attempt = 0; attempt < 3; attempt++) {
            loadDemo();
            runFrames(30, attempt == 1 ? GamepadView.LEFT : 0);
            assertVisibleGame(copyVideo());
            check(requireState().length > 16, "Reload lost save-state support");
            NativeNes.unload();
            NativeNes.runFrame(GamepadView.A | GamepadView.B);
            check(NativeNes.drainAudio(pcm) == 0, "Unload left stale audio queued");
            check(NativeNes.saveState() == null, "Unload left an active game state");
        }
    }

    private void testGamepad() {
        AtomicReference<Throwable> mainThreadFailure = new AtomicReference<>();
        runOnMainSync(() -> {
            try {
            Context context = getTargetContext();
            float density = context.getResources().getDisplayMetrics().density;
            GamepadView pad = new GamepadView(context);
            List<Integer> published = new ArrayList<>();
            pad.setOnButtonsChanged(published::add);
            pad.layout(0, 0, Math.round(360 * density), Math.round(232 * density));
            float[] right = nodeCenter(pad, 4);
            float[] up = nodeCenter(pad, 1);
            float[] a = nodeCenter(pad, 6);
            long downTime = SystemClock.uptimeMillis();
            touch(pad, downTime, MotionEvent.ACTION_DOWN, new int[]{7}, right);
            check(pad.getButtons() == GamepadView.RIGHT, "D-pad press was not received");
            touch(pad, downTime, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{7, 3}, right, a);
            check(pad.getButtons() == (GamepadView.RIGHT | GamepadView.A),
                    "Two fingers did not hold D-pad and A together");
            touch(pad, downTime, MotionEvent.ACTION_MOVE, new int[]{7, 3}, up, a);
            check(pad.getButtons() == (GamepadView.UP | GamepadView.A),
                    "Sliding the D-pad left a previous direction stuck");
            float[] diagonal = {right[0], up[1]};
            touch(pad, downTime, MotionEvent.ACTION_MOVE, new int[]{7, 3}, diagonal, a);
            check(pad.getButtons() == (GamepadView.UP | GamepadView.RIGHT | GamepadView.A),
                    "Diagonal D-pad input failed");
            touch(pad, downTime, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{7, 3}, diagonal, a);
            check(pad.getButtons() == (GamepadView.UP | GamepadView.RIGHT),
                    "Releasing A disturbed the remaining D-pad finger");
            touch(pad, downTime, MotionEvent.ACTION_UP, new int[]{7}, diagonal);
            check(pad.getButtons() == 0, "The final pointer release left a held button");

            touch(pad, downTime, MotionEvent.ACTION_DOWN, new int[]{9}, a);
            touch(pad, downTime, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{9, 2}, a, a);
            touch(pad, downTime, MotionEvent.ACTION_POINTER_UP, new int[]{9, 2}, a, a);
            check(pad.getButtons() == GamepadView.A, "Releasing one of two A fingers released both");
            touch(pad, downTime, MotionEvent.ACTION_CANCEL, new int[]{2}, a);
            check(pad.getButtons() == 0, "Cancellation left a held button");

            float[] select = nodeCenter(pad, 7);
            float[] start = nodeCenter(pad, 8);
            touch(pad, downTime, MotionEvent.ACTION_DOWN, new int[]{1}, select);
            touch(pad, downTime, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{1, 5}, select, start);
            check(pad.getButtons() == (GamepadView.SELECT | GamepadView.START),
                    "Select and Start touch targets overlap other controls");
            pad.releaseAll();
            check(pad.getButtons() == 0 && published.get(published.size() - 1) == 0,
                    "Explicit release did not publish an empty mask");
            touch(pad, downTime, MotionEvent.ACTION_DOWN, new int[]{1}, a);
            pad.setEnabled(false);
            check(pad.getButtons() == 0, "Disabling controls left a held button");

            // The compact landscape layout must retain useful, independent targets.
            pad.setEnabled(true);
            pad.layout(0, 0, Math.round(740 * density), Math.round(180 * density));
            right = nodeCenter(pad, 4);
            a = nodeCenter(pad, 6);
            touch(pad, downTime, MotionEvent.ACTION_DOWN, new int[]{0}, right);
            touch(pad, downTime, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    new int[]{0, 1}, right, a);
            check(pad.getButtons() == (GamepadView.RIGHT | GamepadView.A),
                    "Landscape controls did not preserve simultaneous input");
            pad.releaseAll();
            } catch (Throwable failure) {
                mainThreadFailure.set(failure);
            }
        });
        if (mainThreadFailure.get() != null) {
            throw new AssertionError("Controller integration test failed", mainThreadFailure.get());
        }
    }

    private void testReleaseVersions() {
        ReleaseVersion canonical = ReleaseVersion.parse("v1.2.3");
        check(canonical != null && "1.2.3".equals(canonical.versionName()),
                "The release tag prefix must not become part of the installed version");
        check(canonical.equals(ReleaseVersion.parse("1.2.3"))
                        && canonical.hashCode() == ReleaseVersion.parse("1.2.3").hashCode(),
                "Tags and installed version names must identify the same version");
        checkVersionBefore("1.0.0-beta.2", "1.0.0-beta.10");
        checkVersionBefore("1.0.0-beta.10", "1.0.0");
        checkVersionBefore("1.9.0", "1.10.0");
        checkVersionBefore("1.999.0", "2.0.0");
        checkVersionBefore("99999999999999999999.0.0", "100000000000000000000.0.0");
        for (String invalid : new String[]{null, "", "1", "01.0.0", "1.02.0", "1.0.00",
                "1.0.0-beta.0", "1.0.0-beta.01", "1.0.0-beta", "1.0.0-rc.1",
                "1.0.0+build", " v1.0.0", "v1.0.0\n", "V1.0.0"}) {
            check(ReleaseVersion.parse(invalid) == null, "Accepted noncanonical version: " + invalid);
        }
    }

    private static void checkVersionBefore(String earlier, String later) {
        ReleaseVersion first = ReleaseVersion.parse(earlier), second = ReleaseVersion.parse(later);
        check(first != null && second != null && first.compareTo(second) < 0
                        && second.compareTo(first) > 0,
                "Incorrect release ordering: " + earlier + " before " + later);
    }

    private void testReleaseFeed() throws Exception {
        JSONArray feed = new JSONArray();
        feed.put(releaseFixture("1.0.0-beta.10"));
        feed.put(releaseFixture("1.0.0"));
        feed.put(releaseFixture("1.0.0-beta.3"));
        feed.put(releaseFixture("1.0.0-beta.2")); // Installed version.
        feed.put(releaseFixture("1.0.0-beta.1")); // Older version.
        feed.put(releaseFixture("2.0.0").put("draft", true));
        feed.put(releaseFixture("2.1.0").put("assets", new JSONArray()));
        feed.put(releaseFixture("2.2.0").put("html_url", "https://github.com/other/nes/releases/tag/v2.2.0"));
        feed.put(releaseFixture("2.3.0").put("tag_name", "v2.3.00"));
        feed.put(releaseFixture("2.4.0").put("prerelease", true));
        feed.put(releaseFixture("2.5.0").put("published_at", "not a publication date"));
        JSONObject wrongApk = releaseFixture("2.6.0");
        wrongApk.getJSONArray("assets").getJSONObject(0)
                .put("browser_download_url", "https://example.test/untrusted.apk");
        feed.put(wrongApk);
        JSONObject uploadingApk = releaseFixture("2.7.0");
        uploadingApk.getJSONArray("assets").getJSONObject(0).put("state", "new");
        feed.put(uploadingApk);
        JSONObject emptyApk = releaseFixture("2.8.0");
        emptyApk.getJSONArray("assets").getJSONObject(0).put("size", 0);
        feed.put(emptyApk);
        feed.put(releaseFixture("1.0.0-beta.3")); // Duplicate published entry.
        feed.put(JSONObject.NULL).put("invalid entry");

        List<ReleaseFeed.Release> releases = ReleaseFeed.parse(feed.toString(), "1.0.0-beta.2");
        check(releases.size() == 3, "Incomplete, duplicate, older, or unofficial releases were accepted");
        check("1.0.0-beta.3".equals(releases.get(0).version)
                        && "1.0.0-beta.10".equals(releases.get(1).version)
                        && "1.0.0".equals(releases.get(2).version),
                "New beta and stable releases must arrive in numeric ascending order");
        check("v1.0.0-beta.3".equals(releases.get(0).tag)
                        && releaseUrl(releases.get(0).tag).equals(releases.get(0).url),
                "The accepted release must retain its official browser page");
        boolean immutable = false;
        try { releases.add(releases.get(0)); }
        catch (UnsupportedOperationException expected) { immutable = true; }
        check(immutable, "Callers must not be able to mutate a parsed feed");
        check(ReleaseFeed.parse(new JSONArray().put(releaseFixture("1.1.0-beta.1")).toString(), "1.0.0").size() == 1,
                "A newer minor beta must remain eligible after an installed stable release");
    }

    private void testMalformedReleaseFeed() throws Exception {
        for (String invalid : new String[]{null, "not json", "{}", "[", "[] trailing", "null"}) {
            boolean rejected = false;
            try { ReleaseFeed.parse(invalid, "1.0.0-beta.1"); }
            catch (IOException expected) { rejected = true; }
            check(rejected, "Malformed release response was accepted: " + invalid);
        }
        boolean rejectedVersion = false;
        try { ReleaseFeed.parse("[]", "not an installed version"); }
        catch (IOException expected) { rejectedVersion = true; }
        check(rejectedVersion, "An invalid installed version must fail the check");
        check(ReleaseFeed.parse("[1,null,\"invalid\"]", "1.0.0").isEmpty(),
                "Malformed individual entries must not fabricate a release");
    }

    private void testReleaseRateLimits() {
        long now = 1760000000000L, minimum = now + TimeUnit.MINUTES.toMillis(1);
        check(ReleaseFeed.retryAtMillis(null, null, null, now) == minimum,
                "A rate-limited response without valid headers must wait at least one minute");
        check(ReleaseFeed.retryAtMillis("120", null, null, now) == now + 120000,
                "Retry-After seconds must delay the next check");
        String future = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(now + 180000).atZone(ZoneOffset.UTC));
        check(ReleaseFeed.retryAtMillis(future, null, null, now) == now + 180000,
                "Retry-After HTTP dates must delay the next check");
        String reset = Long.toString((now + 240000) / 1000);
        check(ReleaseFeed.retryAtMillis(null, "0", reset, now) == now + 240000,
                "An exhausted GitHub rate limit must wait for its reset");
        check(ReleaseFeed.retryAtMillis(null, "1", reset, now) == minimum,
                "A non-exhausted rate limit must not introduce a reset delay");
        check(ReleaseFeed.retryAtMillis("120", "0", reset, now) == now + 240000,
                "Conflicting retry headers must honor the later deadline");
        for (String invalid : new String[]{"", "garbage", "-3", "23.5", "9223372036854775807000"}) {
            check(ReleaseFeed.retryAtMillis(invalid, "0", invalid, now) == minimum,
                    "An invalid or overflowing retry header must fall back safely: " + invalid);
        }
        String expired = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(now - 60000).atZone(ZoneOffset.UTC));
        check(ReleaseFeed.retryAtMillis(expired, "0", Long.toString((now - 60000) / 1000), now) == minimum,
                "Expired server deadlines must retain the minimum retry delay");
    }

    private void testReleaseScheduling() throws Exception {
        try (ReleaseTestState state = new ReleaseTestState()) {
            state.preferences.edit().putBoolean("enabled", true).commit();
            ReleaseNotifications.createChannel(state.context);
            requireNotificationsAllowed(state.context);
            check(ReleaseNotifications.schedule(state.context), "Could not schedule background release checks");
            JobInfo periodic = state.scheduler.getPendingJob(ReleaseNotifications.PERIODIC_JOB_ID);
            check(periodic != null && periodic.isPeriodic() && periodic.isPersisted()
                            && periodic.getIntervalMillis() == TimeUnit.HOURS.toMillis(6)
                            && periodic.getNetworkType() == JobInfo.NETWORK_TYPE_ANY,
                    "Background checks must survive reboot and wait for a network every six hours");
            PersistableBundle marker = new PersistableBundle();
            marker.putBoolean("preserve-existing-job", true);
            JobInfo markedPeriodic = new JobInfo.Builder(periodic.getId(), periodic.getService())
                    .setPeriodic(periodic.getIntervalMillis()).setPersisted(true)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setBackoffCriteria(periodic.getInitialBackoffMillis(), periodic.getBackoffPolicy())
                    .setExtras(marker).build();
            check(state.scheduler.schedule(markedPeriodic) == JobScheduler.RESULT_SUCCESS,
                    "Cannot attach a scheduling preservation marker");
            check(ReleaseNotifications.schedule(state.context), "Repeat scheduling failed");
            int periodicJobs = 0;
            for (JobInfo job : state.scheduler.getAllPendingJobs()) {
                if (job.getId() == ReleaseNotifications.PERIODIC_JOB_ID) periodicJobs++;
            }
            check(periodicJobs == 1, "Repeat scheduling created duplicate background checks");
            check(state.scheduler.getPendingJob(ReleaseNotifications.PERIODIC_JOB_ID)
                            .getExtras().getBoolean("preserve-existing-job"),
                    "Launching again must preserve the existing periodic check");

            // Keep the immediate job pending so observing its configuration is not a race with execution.
            state.preferences.edit().putLong("retry-at", System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1)).commit();
            check(ReleaseNotifications.requestCheck(state.context, true), "Could not request a release check");
            JobInfo immediate = state.scheduler.getPendingJob(ReleaseNotifications.CHECK_JOB_ID);
            check(immediate != null && !immediate.isPeriodic() && immediate.isPersisted()
                            && immediate.getNetworkType() == JobInfo.NETWORK_TYPE_ANY
                            && immediate.getMinLatencyMillis() > TimeUnit.MINUTES.toMillis(59),
                    "On-demand checks must retain the network constraint and server retry delay");
            state.scheduler.cancel(ReleaseNotifications.CHECK_JOB_ID);
            state.preferences.edit().putLong("last-successful-check", System.currentTimeMillis()).commit();
            check(!ReleaseNotifications.requestCheck(state.context, false)
                            && state.scheduler.getPendingJob(ReleaseNotifications.CHECK_JOB_ID) == null,
                    "Repeated app launches must not check again immediately after a successful check");
            check(ReleaseNotifications.requestCheck(state.context, true)
                            && state.scheduler.getPendingJob(ReleaseNotifications.CHECK_JOB_ID) != null,
                    "An explicit user check must bypass the app-launch throttle");
            ReleaseNotifications.setEnabled(state.context, false);
            check(!ReleaseNotifications.isEnabled(state.context)
                            && state.scheduler.getPendingJob(ReleaseNotifications.PERIODIC_JOB_ID) == null
                            && state.scheduler.getPendingJob(ReleaseNotifications.CHECK_JOB_ID) == null,
                    "Turning release alerts off must cancel both pending checks");
            check(!ReleaseNotifications.schedule(state.context)
                            && !ReleaseNotifications.requestCheck(state.context, true),
                    "Disabled alerts must not create a new check");
        }
    }

    private void testReleaseDelivery() throws Exception {
        try (ReleaseTestState state = new ReleaseTestState()) {
            state.preferences.edit().putBoolean("enabled", true).commit();
            ReleaseNotifications.createChannel(state.context);
            requireNotificationsAllowed(state.context);
            List<ReleaseFeed.Release> releases = fixtureReleases(FIRST_TEST_VERSION, SECOND_TEST_VERSION);
            String installed = state.context.getPackageManager().getPackageInfo(state.context.getPackageName(), 0).versionName;
            checkVersionBefore(installed, FIRST_TEST_VERSION);
            check(ReleaseNotifications.notifyReleases(state.context, releases) == 2,
                    "Each newly published version must create its own notification");
            waitForReleaseNotifications(state.manager, releases);
            for (ReleaseFeed.Release release : releases) {
                StatusBarNotification posted = findReleaseNotification(state.manager, release.tag);
                check(posted != null && posted.getId() == 1
                                && ReleaseNotifications.CHANNEL_ID.equals(posted.getNotification().getChannelId()),
                        "Different release notifications must coexist on the new-versions channel");
                Notification notification = posted.getNotification();
                check(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains(release.version)
                                && notification.contentIntent != null && notification.contentIntent.isActivity(),
                        "An alert must identify its version and open a browser activity");
                if (Build.VERSION.SDK_INT >= 31) {
                    check(notification.contentIntent.isImmutable(), "The release browser action must be immutable");
                }
                Intent browser = ReleaseNotifications.browserIntent(release.url);
                check(Intent.ACTION_VIEW.equals(browser.getAction())
                                && release.url.equals(browser.getDataString()) && browser.getSelector() != null
                                && browser.getSelector().hasCategory(Intent.CATEGORY_APP_BROWSER),
                        "An alert must open its own official release in a browser");
            }
            check(!findReleaseNotification(state.manager, releases.get(0).tag).getNotification().contentIntent
                            .equals(findReleaseNotification(state.manager, releases.get(1).tag).getNotification().contentIntent),
                    "Each release must retain its own browser destination");
            check(ReleaseNotifications.notifyReleases(state.context, releases) == 0,
                    "Checking the same published versions twice must not alert again");
            JSONObject alias = releaseFixture(FIRST_TEST_VERSION).put("tag_name", FIRST_TEST_VERSION)
                    .put("html_url", releaseUrl(FIRST_TEST_VERSION));
            alias.getJSONArray("assets").getJSONObject(0).put("browser_download_url",
                    "https://github.com/culpen90/nes/releases/download/" + FIRST_TEST_VERSION
                            + "/pocket-nes-" + FIRST_TEST_VERSION + ".apk");
            List<ReleaseFeed.Release> aliasRelease = ReleaseFeed.parse(new JSONArray().put(alias).toString(), "0.0.0-beta.1");
            check(aliasRelease.size() == 1 && ReleaseNotifications.notifyReleases(state.context, aliasRelease) == 0,
                    "A tag alias for an already notified version must not alert again");
            Set<String> recorded = state.preferences.getStringSet("notified-tags", new HashSet<>());
            check(recorded.contains(releases.get(0).tag) && recorded.contains(releases.get(1).tag),
                    "Posted versions must be remembered across future checks");

            // Parse against an earlier fixture baseline to exercise the delivery guard independently of feed filtering.
            checkVersionBefore("0.0.0", installed);
            List<ReleaseFeed.Release> installedAndOlder = fixtureReleases("0.0.0", installed);
            check(ReleaseNotifications.notifyReleases(state.context, installedAndOlder) == 0,
                    "Delivery must reject installed and older versions even if a caller supplies them");
            for (ReleaseFeed.Release release : installedAndOlder) {
                check(!state.preferences.getStringSet("notified-tags", new HashSet<>()).contains(release.tag),
                        "An installed or older version must not be recorded as newly notified");
            }
        }
    }

    private void testBlockedReleaseDelivery() throws Exception {
        try (ReleaseTestState state = new ReleaseTestState()) {
            state.preferences.edit().putBoolean("enabled", true).commit();
            ReleaseNotifications.createChannel(state.context);
            requireNotificationsAllowed(state.context);
            List<ReleaseFeed.Release> releases = fixtureReleases(FIRST_TEST_VERSION);
            if (Build.VERSION.SDK_INT >= 33) {
                Context deniedPermission = new ContextWrapper(state.context) {
                    @Override public int checkSelfPermission(String permission) {
                        return android.Manifest.permission.POST_NOTIFICATIONS.equals(permission)
                                ? PackageManager.PERMISSION_DENIED : super.checkSelfPermission(permission);
                    }
                };
                check(!ReleaseNotifications.canNotify(deniedPermission)
                                && ReleaseNotifications.notifyReleases(deniedPermission, releases) == 0,
                        "Denied notification permission must prevent delivery");
                assertReleaseUnconsumed(state, releases.get(0));
            }
            state.preferences.edit().putBoolean("enabled", false).commit();
            check(ReleaseNotifications.notifyReleases(state.context, releases) == 0,
                    "Turning alerts off must prevent delivery");
            assertReleaseUnconsumed(state, releases.get(0));
            state.preferences.edit().putBoolean("enabled", true).commit();
            check(ReleaseNotifications.notifyReleases(state.context, releases, () -> false,
                            ReleaseNotifications.CHANNEL_ID) == 0,
                    "A stopped job must not post or consume a pending release");
            assertReleaseUnconsumed(state, releases.get(0));

            // A separate channel exercises the user-blocked path without changing the actual channel's settings.
            String blockedChannel = "release-tests-blocked-" + SystemClock.uptimeMillis();
            try {
                state.manager.createNotificationChannel(new NotificationChannel(blockedChannel,
                        "Blocked release test", NotificationManager.IMPORTANCE_NONE));
                check(!ReleaseNotifications.canNotify(state.context, blockedChannel)
                                && ReleaseNotifications.notifyReleases(state.context, releases, () -> true,
                                blockedChannel) == 0,
                        "A disabled notification channel must prevent delivery");
                assertReleaseUnconsumed(state, releases.get(0));
            } finally {
                state.manager.deleteNotificationChannel(blockedChannel);
            }
        }
    }

    private static void requireNotificationsAllowed(Context context) {
        check(ReleaseNotifications.canNotify(context),
                "Notification tests need app and channel alerts enabled; on Android 13+ grant POST_NOTIFICATIONS before instrumentation");
    }

    private static void assertReleaseUnconsumed(ReleaseTestState state, ReleaseFeed.Release release) {
        check(!state.preferences.getStringSet("notified-tags", new HashSet<>()).contains(release.tag),
                "A blocked release was consumed instead of remaining available for a later check");
        check(findReleaseNotification(state.manager, release.tag) == null,
                "A blocked release unexpectedly posted a notification");
    }

    private static StatusBarNotification findReleaseNotification(NotificationManager manager, String tag) {
        for (StatusBarNotification notification : manager.getActiveNotifications()) {
            if (("release:" + tag).equals(notification.getTag())) return notification;
        }
        return null;
    }

    private static void waitForReleaseNotifications(NotificationManager manager, List<ReleaseFeed.Release> releases) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean allPosted = true;
            for (ReleaseFeed.Release release : releases) allPosted &= findReleaseNotification(manager, release.tag) != null;
            if (allPosted) return;
            SystemClock.sleep(50);
        }
        throw new AssertionError("Android did not retain every posted release notification");
    }

    private static List<ReleaseFeed.Release> fixtureReleases(String... versions) throws Exception {
        JSONArray json = new JSONArray();
        for (String version : versions) json.put(releaseFixture(version));
        List<ReleaseFeed.Release> releases = ReleaseFeed.parse(json.toString(), "0.0.0-beta.1");
        check(releases.size() == versions.length, "Notification fixtures did not produce all requested releases");
        return releases;
    }

    private static JSONObject releaseFixture(String version) throws Exception {
        String tag = "v" + version, name = "pocket-nes-" + version + ".apk";
        JSONObject asset = new JSONObject().put("name", name).put("state", "uploaded").put("size", 123)
                .put("browser_download_url", "https://github.com/culpen90/nes/releases/download/" + tag + "/" + name);
        return new JSONObject().put("tag_name", tag).put("draft", false)
                .put("prerelease", version.contains("-beta."))
                .put("published_at", "2026-10-08T15:00:00Z").put("html_url", releaseUrl(tag))
                .put("assets", new JSONArray().put(asset));
    }

    private static String releaseUrl(String tag) {
        return "https://github.com/culpen90/nes/releases/tag/" + tag;
    }

    private final class ReleaseTestState implements AutoCloseable {
        final Context context = getTargetContext();
        final SharedPreferences preferences = ReleaseNotifications.prefs(context);
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final NotificationManager manager = context.getSystemService(NotificationManager.class);
        final Map<String, Object> savedPreferences = new HashMap<>();
        final List<JobInfo> savedJobs = new ArrayList<>();
        final List<StatusBarNotification> savedNotifications = new ArrayList<>();
        final boolean hadChannel;

        ReleaseTestState() {
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
                Object value = entry.getValue();
                savedPreferences.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value);
            }
            for (JobInfo job : scheduler.getAllPendingJobs()) {
                if (job.getId() == ReleaseNotifications.PERIODIC_JOB_ID || job.getId() == ReleaseNotifications.CHECK_JOB_ID) {
                    savedJobs.add(job);
                }
            }
            for (StatusBarNotification notification : manager.getActiveNotifications()) {
                if (notification.getTag() != null && notification.getTag().startsWith("release:")) savedNotifications.add(notification);
            }
            hadChannel = manager.getNotificationChannel(ReleaseNotifications.CHANNEL_ID) != null;
            scheduler.cancel(ReleaseNotifications.PERIODIC_JOB_ID);
            scheduler.cancel(ReleaseNotifications.CHECK_JOB_ID);
            check(preferences.edit().clear().commit(), "Cannot isolate release test preferences");
        }

        @Override public void close() {
            scheduler.cancel(ReleaseNotifications.PERIODIC_JOB_ID);
            scheduler.cancel(ReleaseNotifications.CHECK_JOB_ID);
            manager.cancel("release:v" + FIRST_TEST_VERSION, 1);
            manager.cancel("release:v" + SECOND_TEST_VERSION, 1);
            long cancelDeadline = SystemClock.uptimeMillis() + 5000;
            boolean notificationsRemoved = true;
            while (findReleaseNotification(manager, "v" + FIRST_TEST_VERSION) != null
                    || findReleaseNotification(manager, "v" + SECOND_TEST_VERSION) != null) {
                if (SystemClock.uptimeMillis() >= cancelDeadline) {
                    notificationsRemoved = false;
                    break;
                }
                SystemClock.sleep(50);
            }
            SharedPreferences.Editor restore = preferences.edit().clear();
            for (Map.Entry<String, Object> entry : savedPreferences.entrySet()) {
                String key = entry.getKey(); Object value = entry.getValue();
                if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else if (value instanceof String) restore.putString(key, (String) value);
                else if (value instanceof Integer) restore.putInt(key, (Integer) value);
                else if (value instanceof Long) restore.putLong(key, (Long) value);
                else if (value instanceof Float) restore.putFloat(key, (Float) value);
                else if (value instanceof Set) {
                    Set<String> strings = new HashSet<>();
                    for (Object item : (Set<?>) value) strings.add((String) item);
                    restore.putStringSet(key, strings);
                }
            }
            check(restore.commit(), "Cannot restore release preferences after testing");
            for (StatusBarNotification notification : savedNotifications) {
                if (findReleaseNotification(manager, notification.getTag().substring("release:".length())) == null) {
                    manager.notify(notification.getTag(), notification.getId(), notification.getNotification());
                }
            }
            if (!hadChannel) manager.deleteNotificationChannel(ReleaseNotifications.CHANNEL_ID);
            for (JobInfo job : savedJobs) {
                check(scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS, "Cannot restore original release job");
            }
            check(notificationsRemoved, "Cannot remove test release notifications");
        }
    }

    private static float[] nodeCenter(GamepadView pad, int id) {
        AccessibilityNodeInfo node = pad.getAccessibilityNodeProvider().createAccessibilityNodeInfo(id);
        check(node != null, "Missing accessible controller button " + id);
        Rect bounds = new Rect();
        node.getBoundsInParent(bounds);
        check(bounds.width() > 0 && bounds.height() > 0, "Controller button has no touch geometry");
        node.recycle();
        return new float[]{bounds.exactCenterX(), bounds.exactCenterY()};
    }

    private static void touch(GamepadView pad, long downTime, int action, int[] ids, float[]... positions) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = ids[i];
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = positions[i][0];
            coordinates[i].y = positions[i][1];
            coordinates[i].pressure = 1;
            coordinates[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, ids.length,
                properties, coordinates, 0, 0, 1, 1, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { check(pad.dispatchTouchEvent(event), "Controller did not consume a touch event"); }
        finally { event.recycle(); }
    }

    private void loadDemo() {
        String error = NativeNes.load(demo.getAbsolutePath(), workDirectory.getAbsolutePath(),
                workDirectory.getAbsolutePath());
        check(error == null, "Original demo failed to load: " + error);
    }

    private void runFrames(int count, int input) {
        for (int frame = 0; frame < count; frame++) {
            NativeNes.runFrame(input);
            NativeNes.drainAudio(pcm);
        }
    }

    private int[] copyVideo() {
        int[] image = new int[NativeNes.width() * NativeNes.height()];
        NativeNes.copyVideo(image);
        return image;
    }

    private byte[] requireState() {
        byte[] state = NativeNes.saveState();
        check(state != null && state.length > 16, "Core did not produce a save state");
        return state;
    }

    private static void assertVisibleGame(int[] image) {
        Set<Integer> colors = new HashSet<>();
        int nonBlack = 0;
        for (int pixel : image) {
            check((pixel >>> 24) == 255, "Video output contains transparent pixels");
            colors.add(pixel);
            if ((pixel & 0x00ffffff) != 0) nonBlack++;
        }
        check(colors.size() >= 4 && nonBlack > 1000, "Demo framebuffer is blank or trivial");
    }

    private static int differentPixels(int[] first, int[] second) {
        check(first.length == second.length, "Frame dimensions changed unexpectedly");
        int different = 0;
        for (int i = 0; i < first.length; i++) if (first[i] != second[i]) different++;
        return different;
    }

    private File writeFile(String name, byte[] bytes) throws IOException {
        File file = new File(workDirectory, name);
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(bytes); }
        return file;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int count;
        while ((count = in.read(bytes)) != -1) output.write(bytes, 0, count);
        return output.toByteArray();
    }

    private void deleteWorkFiles() {
        if (workDirectory == null) return;
        File[] files = workDirectory.listFiles();
        if (files != null) for (File file : files) if (file.isFile()) file.delete();
        workDirectory.delete();
    }

    private void status(int code, String name, String message) {
        sendStatus(code, statusBundle(name, message));
    }

    private Bundle statusBundle(String name, String message) {
        Bundle result = new Bundle();
        result.putString("id", "EmulatorInstrumentation");
        result.putString("class", getClass().getName());
        result.putString("test", name);
        result.putInt("numtests", TEST_COUNT);
        result.putInt("current", current);
        result.putString("stream", message);
        return result;
    }

    private static String stackTrace(Throwable failure) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface CheckedRunnable { void run() throws Exception; }

    private static final class FrameSample {
        final int[] video;
        final short[] audio;
        FrameSample(int[] video, short[] audio) { this.video = video; this.audio = audio; }
    }
}
