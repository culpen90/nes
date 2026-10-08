package com.culpen.nes;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Real-device tests without a JUnit or AndroidX dependency. No Activity is launched. */
public final class EmulatorInstrumentation extends Instrumentation {
    private static final int TEST_COUNT = 7;
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
