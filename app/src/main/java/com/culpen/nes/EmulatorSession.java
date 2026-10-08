package com.culpen.nes;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** One owner thread for core, PCM, state, and SRAM. UI only publishes input. */
public final class EmulatorSession implements AutoCloseable {
    // JNI wraps one process-global core. A replacement Activity waits for cleanup.
    private static final Object CORE_OWNER = new Object();
    public interface Listener {
        void loaded(boolean resumed);
        void message(String text);
        void failed(String text);
        void performance(int frames);
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedBlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
    private final Thread worker;
    private volatile NesScreenView screen;
    private volatile int buttons;
    private volatile boolean muted;
    private boolean loaded, paused = true, foreground = true, closing;
    private AudioTrack audio;
    private File saveDir;
    private String key;
    private int[] pixels = new int[256 * 240];
    private final short[] samples = new short[8192];
    private long framePeriod = 16_639_267L;
    public EmulatorSession(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        worker = new Thread(() -> { synchronized (CORE_OWNER) { loop(); } }, "NES emulation"); worker.start();
    }
    public void setScreen(NesScreenView value) {
        screen = value;
        if (value != null) post(() -> {
            if (screen == value && loaded) {
                int w = NativeNes.width(), h = NativeNes.height();
                if (pixels.length != w * h) pixels = new int[w * h];
                NativeNes.copyVideo(pixels); value.setFrame(pixels, w, h);
            }
        });
    }
    public void setButtons(int value) { buttons = value; }
    public void setMuted(boolean value) { muted = value; }
    private void post(Runnable task) { commands.offer(task); }
    public void loadGame(LibraryStore.Game game, File saves) {
        post(() -> {
            persist(true); releaseAudio();
            if (loaded) NativeNes.unload();
            loaded = false; paused = true; buttons = 0; saveDir = saves; key = game.key;
            String error = NativeNes.load(game.file.getAbsolutePath(), context.getFilesDir().getAbsolutePath(), saves.getAbsolutePath());
            if (error != null) { fail(error); return; }
            loaded = true;
            File ram = file(".sav");
            try { if (ram.isFile()) NativeNes.loadRam(Files.readAllBytes(ram.toPath())); }
            catch (Exception e) { message("Battery save could not be read."); }
            boolean resumed = false;
            try { File state = file(".auto"); if (state.isFile()) resumed = NativeNes.loadState(Files.readAllBytes(state.toPath())); }
            catch (Exception e) { Log.w("PocketNES", "Resume state unavailable", e); }
            double fps = NativeNes.fps();
            if (!Double.isFinite(fps) || fps < 40 || fps > 70) fps = 60.0988;
            framePeriod = (long)(1_000_000_000d / fps);
            createAudio(); paused = false;
            if (foreground && audio != null) audio.play();
            final boolean didResume = resumed;
            main.post(() -> listener.loaded(didResume));
        });
    }
    public void setPaused(boolean value) {
        buttons = 0;
        post(() -> { paused = value; syncAudio(); if (value) persist(true); });
    }
    public void setForeground(boolean value) {
        buttons = 0;
        post(() -> { foreground = value; syncAudio(); if (!value) persist(true); });
    }
    public void stopGame() {
        buttons = 0;
        post(() -> { paused = true; persist(true); releaseAudio(); if (loaded) NativeNes.unload(); loaded = false; });
    }
    public void saveSlot() {
        post(() -> {
            if (!loaded) return;
            try { byte[] bytes = NativeNes.saveState(); if (bytes == null || bytes.length == 0) throw new Exception(); LibraryStore.writeAtomic(file(".state"), bytes); persist(false); message("Saved to your quick-save slot."); }
            catch (Exception e) { message("The game could not be saved."); }
        });
    }
    public void loadSlot() {
        buttons = 0;
        post(() -> {
            if (!loaded) return;
            if (!file(".state").isFile()) { message("No quick save yet. Tap Save first."); return; }
            try {
                boolean ok = NativeNes.loadState(Files.readAllBytes(file(".state").toPath()));
                flushAudio(); message(ok ? "Quick save restored." : "This save could not be restored.");
            } catch (Exception e) { message("This save could not be read."); }
        });
    }
    public void reset() { buttons = 0; post(() -> { if (loaded) { NativeNes.reset(); flushAudio(); message("Game restarted."); } }); }
    private File file(String suffix) { return new File(saveDir, key + suffix); }
    private void persist(boolean includeState) {
        if (!loaded || key == null) return;
        try {
            byte[] ram = NativeNes.saveRam();
            if (ram != null && ram.length > 0) LibraryStore.writeAtomic(file(".sav"), ram);
            if (includeState) { byte[] state = NativeNes.saveState(); if (state != null && state.length > 0) LibraryStore.writeAtomic(file(".auto"), state); }
        } catch (Exception e) { Log.e("PocketNES", "Save failed", e); message("Save failed. Check available phone storage."); }
    }
    private void createAudio() {
        int rate = NativeNes.sampleRate();
        try {
            int minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            audio = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(Math.max(4096, minimum * 2)).build();
            if (audio.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack uninitialized");
        } catch (Exception e) { releaseAudio(); message("Audio is unavailable on this device."); Log.w("PocketNES", "Audio unavailable", e); }
    }
    private void syncAudio() {
        if (audio == null) return;
        if (paused || !foreground) { audio.pause(); audio.flush(); }
        else audio.play();
    }
    private void flushAudio() { if (audio != null) { audio.pause(); audio.flush(); if (!paused && foreground) audio.play(); } }
    private void releaseAudio() { if (audio != null) { try { audio.pause(); audio.flush(); audio.release(); } catch (Exception ignored) {} audio = null; } }
    private void message(String text) { main.post(() -> listener.message(text)); }
    private void fail(String text) { main.post(() -> listener.failed(text)); }
    private void loop() {
        long next = System.nanoTime(), lastStats = next, lastSave = next;
        int frames = 0;
        try {
            while (!closing) {
                Runnable task;
                while ((task = commands.poll()) != null) task.run();
                if (closing) break;
                if (!loaded || paused || !foreground) {
                    task = commands.poll(100, TimeUnit.MILLISECONDS);
                    if (task != null) task.run();
                    next = System.nanoTime(); lastStats = next; frames = 0;
                    continue;
                }
                NativeNes.runFrame(buttons);
                int w = NativeNes.width(), h = NativeNes.height();
                if (w > 0 && h > 0 && w * h <= 1024 * 1024) {
                    if (pixels.length != w * h) pixels = new int[w * h];
                    NativeNes.copyVideo(pixels);
                    NesScreenView view = screen;
                    if (view != null) view.setFrame(pixels, w, h);
                }
                int count = NativeNes.drainAudio(samples);
                if (audio != null && count > 0) {
                    audio.setVolume(muted ? 0f : 1f);
                    int written = audio.write(samples, 0, count, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) { releaseAudio(); message("Audio stopped. Reopen the game to retry."); }
                }
                long now = System.nanoTime(); frames++;
                if (now - lastStats >= 1_000_000_000L) {
                    final int measured = Math.round(frames * 1_000_000_000f / (now - lastStats));
                    main.post(() -> listener.performance(measured)); frames = 0; lastStats = now;
                }
                if (now - lastSave > 5_000_000_000L) { persist(false); lastSave = now; }
                next += framePeriod;
                if (next < now - framePeriod * 3) next = now;
                if (next > now) LockSupport.parkNanos(next - now);
            }
        } catch (Exception | LinkageError e) { Log.e("PocketNES", "Emulation stopped", e); fail("Emulation stopped: " + e.getMessage()); }
        finally { persist(true); releaseAudio(); if (loaded) NativeNes.unload(); loaded = false; }
    }
    @Override public void close() { buttons = 0; post(() -> closing = true); }
}
