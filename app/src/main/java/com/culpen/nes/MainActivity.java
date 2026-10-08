package com.culpen.nes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements EmulatorSession.Listener {
    private static final int BG = 0xff15171c, CARD = 0xff22252c, FG = 0xffefeadd, DIM = 0xff999da8, ACCENT = 0xffff6b4a;
    private static final int IMPORT = 41;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LibraryStore library;
    private EmulatorSession session;
    private LibraryStore.Game playing;
    private GamepadView pad;
    private NesScreenView screen;
    private TextView status;
    private Button pauseButton, importButton;
    private boolean paused, muted, ready, importing, destroyed;
    private int touchButtons, keyButtons, axisButtons;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        library = new LibraryStore(this);
        muted = getPreferences(0).getBoolean("muted", false);
        session = new EmulatorSession(this, this); session.setMuted(muted);
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        showLibrary();
        io.execute(() -> {
            try { library.ensureDemo(); runOnUiThread(() -> { if (!destroyed) { ready = true; if (playing == null) showLibrary(); } }); }
            catch (IOException e) { runOnUiThread(() -> message("Demo could not be prepared: " + e.getMessage())); }
        });
    }
    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); t.setFontFeatureSettings("kern"); return t;
    }
    private TextView bold(String value, int size, int color) { TextView t = text(value, size, color); t.setTypeface(null, Typeface.BOLD); return t; }
    private Button button(String value, boolean primary, Runnable action) {
        Button b = new Button(this); b.setText(value); b.setTextSize(14); b.setAllCaps(false);
        b.setTypeface(null, Typeface.BOLD); b.setTextColor(primary ? BG : FG); b.setMinHeight(0); b.setMinimumHeight(0); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(14), dp(8), dp(14), dp(8)); b.setBackground(shape(primary ? ACCENT : CARD, 12));
        b.setOnClickListener(v -> action.run()); return b;
    }
    private LinearLayout column() { LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); return c; }
    private void addGap(LinearLayout parent, int size) { View gap = new View(this); parent.addView(gap, new LinearLayout.LayoutParams(1, dp(size))); }
    private LinearLayout root(boolean immersive) {
        Window window = getWindow(); window.setStatusBarColor(BG); window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        WindowInsetsController controller = android.os.Build.VERSION.SDK_INT >= 30 ? window.getInsetsController() : null;
        if (android.os.Build.VERSION.SDK_INT >= 30 && controller != null) {
            controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            if (immersive) controller.hide(WindowInsets.Type.systemBars()); else controller.show(WindowInsets.Type.systemBars());
        }
        LinearLayout r = column(); r.setBackgroundColor(BG);
        if (android.os.Build.VERSION.SDK_INT >= 30) r.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(immersive ? WindowInsets.Type.displayCutout() : WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        else {
            r.setFitsSystemWindows(true);
            if (immersive) window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
        setContentView(r); r.requestApplyInsets(); return r;
    }
    private void showLibrary() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        session.setScreen(null); screen = null; pad = null;
        LinearLayout root = root(false);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); root.addView(scroll);
        LinearLayout page = column(); page.setPadding(dp(24), dp(22), dp(24), dp(24)); scroll.addView(page);
        LinearLayout brand = new LinearLayout(this); brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView wordmark = bold("POCKET  /  NES", 16, FG); wordmark.setLetterSpacing(.16f);
        brand.addView(wordmark, new LinearLayout.LayoutParams(0, dp(42), 1)); wordmark.setGravity(Gravity.CENTER_VERTICAL);
        Button about = button("Info", false, this::about); brand.addView(about, new LinearLayout.LayoutParams(dp(66), dp(40))); page.addView(brand);
        addGap(page, 26);
        page.addView(bold("Small screen.\nBig nostalgia.", 36, FG));
        addGap(page, 12);
        TextView intro = text("Your NES library, wherever you go.\nPick a cartridge and settle in.", 16, DIM); intro.setLineSpacing(dp(4), 1); page.addView(intro);
        addGap(page, 26);
        importButton = button(importing ? "Importing cartridge…" : "+  Import a game", true, this::pickRom);
        importButton.setEnabled(!importing); page.addView(importButton, new LinearLayout.LayoutParams(-1, dp(54)));
        addGap(page, 8); TextView hint = text(".nes cartridges or a ZIP containing one game", 12, DIM); hint.setGravity(Gravity.CENTER); page.addView(hint);
        addGap(page, 30);
        LinearLayout libraryHeader = new LinearLayout(this); libraryHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = bold("YOUR CARTRIDGES", 12, DIM); label.setLetterSpacing(.14f); libraryHeader.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        libraryHeader.addView(text(String.format(java.util.Locale.getDefault(), "%02d", library.games().size()), 12, DIM)); page.addView(libraryHeader);
        addGap(page, 14);
        for (LibraryStore.Game game : library.games()) {
            LinearLayout card = new LinearLayout(this); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(15), dp(18), dp(15), dp(18)); card.setBackground(shape(CARD, 16));
            CartridgeArt art = new CartridgeArt(game.demo); card.addView(art, new LinearLayout.LayoutParams(dp(60), dp(76)));
            LinearLayout copy = column(); LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1); cp.setMargins(dp(16), 0, dp(10), 0); card.addView(copy, cp);
            TextView title = bold(game.name, 18, FG); title.setMaxLines(2); copy.addView(title);
            addGap(copy, 6); copy.addView(text(game.demo ? "ORIGINAL DEMO" : "NES CARTRIDGE", 10, game.demo ? ACCENT : DIM));
            addGap(copy, 7);
            boolean resume = new File(library.saves, game.key + ".auto").isFile();
            copy.addView(text(game.demo ? "Collect stars. Find your rhythm." : (resume ? "Your place is saved." : "Ready when you are."), 12, DIM));
            TextView play = bold(resume ? "↗" : "▶", 22, ACCENT); card.addView(play, new LinearLayout.LayoutParams(dp(26), -2));
            card.setContentDescription((resume ? "Resume " : "Play ") + game.name); card.setFocusable(true); card.setClickable(true);
            card.setOnClickListener(v -> { if (!game.demo || ready) startGame(game); else message("Preparing the demo. Try again in a moment."); });
            page.addView(card, new LinearLayout.LayoutParams(-1, -2)); addGap(page, 12);
        }
        addGap(page, 16);
        TextView note = text("BUILT FOR THE WAY YOU PLAY", 10, DIM); note.setLetterSpacing(.12f); page.addView(note); addGap(page, 9);
        page.addView(text("Touch controls · Bluetooth gamepads\nAutomatic resume · Quick saves · Offline play", 13, DIM));
    }
    private void pickRom() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/octet-stream", "application/zip", "application/x-nes", "application/vnd.nintendo.nes"});
        try { startActivityForResult(intent, IMPORT); } catch (Exception e) { message("No file picker is available on this device."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request != IMPORT || result != RESULT_OK || intent == null || intent.getData() == null) return;
        Uri uri = intent.getData(); importing = true; showLibrary();
        io.execute(() -> {
            try { LibraryStore.Game game = library.importGame(uri); runOnUiThread(() -> { importing = false; if (!destroyed) { showLibrary(); message(game.name + " added to your library."); } }); }
            catch (Exception e) { runOnUiThread(() -> { importing = false; if (!destroyed) { showLibrary(); message(e.getMessage() == null ? "This game could not be imported." : e.getMessage()); } }); }
        });
    }
    private void startGame(LibraryStore.Game game) {
        playing = game; paused = false; releaseInput(); showGame(); session.loadGame(game, library.saves);
    }
    private void showGame() {
        if (paused) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = root(true);
        LinearLayout top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(10), dp(4), dp(10), dp(4));
        top.addView(button("‹ Library", false, this::leaveGame), new LinearLayout.LayoutParams(dp(94), dp(40)));
        TextView title = bold(playing.name, 14, FG); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setGravity(Gravity.CENTER);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1));
        status = text(paused ? "Paused" : "Playing", 11, DIM); status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); top.addView(status, new LinearLayout.LayoutParams(dp(74), dp(40))); root.addView(top);
        screen = new NesScreenView(this); session.setScreen(screen);
        pad = new GamepadView(this); pad.setOnButtonsChanged(value -> { touchButtons = value; updateInput(); });
        LinearLayout tools = new LinearLayout(this); tools.setPadding(dp(10), dp(6), dp(10), dp(6));
        pauseButton = button(paused ? "Play" : "Pause", false, this::togglePause);
        addTool(tools, pauseButton); addTool(tools, button("Save", false, session::saveSlot)); addTool(tools, button("Load", false, session::loadSlot));
        addTool(tools, button("•••", false, this::gameOptions));
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); root.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
            row.addView(screen, new LinearLayout.LayoutParams(0, -1, 1));
            LinearLayout right = column(); row.addView(right, new LinearLayout.LayoutParams(dp(320), -1));
            right.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1)); right.addView(tools, new LinearLayout.LayoutParams(-1, dp(52)));
        } else {
            root.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(tools, new LinearLayout.LayoutParams(-1, dp(52)));
            root.addView(pad, new LinearLayout.LayoutParams(-1, dp(242)));
        }
        TextView footer = text("POCKET NES     /     YOUR PLACE IS SAVED AUTOMATICALLY", 9, DIM); footer.setGravity(Gravity.CENTER); footer.setLetterSpacing(.05f);
        root.addView(footer, new LinearLayout.LayoutParams(-1, dp(25)));
    }
    private void addTool(LinearLayout row, Button b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.setMargins(dp(3), 0, dp(3), 0); row.addView(b, lp);
    }
    private void togglePause() {
        paused = !paused; releaseInput(); session.setPaused(paused);
        if (paused) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pauseButton.setText(paused ? "Play" : "Pause"); status.setText(paused ? "Paused" : "Playing");
    }
    private void gameOptions() {
        releaseInput(); session.setPaused(true);
        final boolean[] secondary = {false};
        new AlertDialog.Builder(this).setTitle(playing.name)
                .setItems(new String[]{muted ? "Turn sound on" : "Mute sound", "Restart cartridge", "Controller help"}, (dialog, item) -> {
                    if (item == 0) { muted = !muted; session.setMuted(muted); getPreferences(0).edit().putBoolean("muted", muted).apply(); message(muted ? "Sound muted." : "Sound on."); }
                    else if (item == 1) { secondary[0] = true; new AlertDialog.Builder(this).setTitle("Restart cartridge?").setMessage("Your quick-save slot will remain available.").setPositiveButton("Restart", (d, w) -> session.reset()).setNegativeButton("Cancel", null).setOnDismissListener(d -> session.setPaused(paused)).show(); }
                    else { secondary[0] = true; new AlertDialog.Builder(this).setTitle("Ready, player one").setMessage("Touch: hold the D-pad and A or B together.\n\nGamepad: D-pad / left stick, A, B, Start, Select.\n\nKeyboard: arrows move, X is A, Z is B, Enter is Start, Shift is Select.\n\nStar Garden: collect gold stars. Hold A to move faster. B plays a tone. Start resets the demo.").setPositiveButton("Got it", null).setOnDismissListener(d -> session.setPaused(paused)).show(); }
                }).setOnDismissListener(d -> { if (!secondary[0]) session.setPaused(paused); }).show();
    }
    private void leaveGame() { releaseInput(); session.stopGame(); playing = null; paused = false; showLibrary(); }
    private void about() {
        String version;
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { version = ""; }
        new AlertDialog.Builder(this).setTitle("Pocket NES " + version)
                .setMessage("A pocket-sized home for your NES cartridges.\n\nImport .nes files with the Android file picker. ZIP archives may contain one .nes game. Games and saves stay on your phone.\n\nStar Garden is an original included demo.\n\nEmulation: FCEUmm / libretro, licensed under GPL version 2 or later. Pocket NES is distributed under the same license.\n\nNintendo and NES are trademarks of Nintendo. This is an independent project.")
                .setPositiveButton("Done", null).setNeutralButton("License", (d, w) -> showLicense()).show();
    }
    private void showLicense() {
        String license;
        try (InputStream in = getAssets().open("COPYING")) { license = new String(LibraryStore.readLimited(in), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { license = "GNU General Public License, version 2 or later. See the COPYING file in the project source."; }
        ScrollView scroll = new ScrollView(this); TextView content = text(license, 12, FG); content.setPadding(dp(20), dp(12), dp(20), dp(12)); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("GNU GPL").setView(scroll).setPositiveButton("Done", null).show();
    }
    private void updateInput() { session.setButtons(touchButtons | keyButtons | axisButtons); }
    private void releaseInput() { touchButtons = keyButtons = axisButtons = 0; if (pad != null) pad.releaseAll(); session.setButtons(0); }
    private int mappedKey(int code) {
        return switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP -> 1 << 4;
            case KeyEvent.KEYCODE_DPAD_DOWN -> 1 << 5;
            case KeyEvent.KEYCODE_DPAD_LEFT -> 1 << 6;
            case KeyEvent.KEYCODE_DPAD_RIGHT -> 1 << 7;
            case KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_X -> 1 << 8;
            case KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_Z -> 1;
            case KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> 1 << 3;
            case KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 1 << 2;
            default -> 0;
        };
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int mask = playing == null ? 0 : mappedKey(event.getKeyCode());
        if (mask != 0) { if (event.getAction() == KeyEvent.ACTION_DOWN) keyButtons |= mask; else if (event.getAction() == KeyEvent.ACTION_UP) keyButtons &= ~mask; updateInput(); return true; }
        return super.dispatchKeyEvent(event);
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (playing != null && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK && event.getAction() == MotionEvent.ACTION_MOVE) {
            float x = event.getAxisValue(MotionEvent.AXIS_HAT_X), y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(x) < .5f) x = event.getAxisValue(MotionEvent.AXIS_X);
            if (Math.abs(y) < .5f) y = event.getAxisValue(MotionEvent.AXIS_Y);
            axisButtons = (x < -.45f ? 1 << 6 : x > .45f ? 1 << 7 : 0) | (y < -.45f ? 1 << 4 : y > .45f ? 1 << 5 : 0);
            updateInput(); return true;
        }
        return super.dispatchGenericMotionEvent(event);
    }
    private void handleBack() { if (playing != null) leaveGame(); else finish(); }
    // API 26–32 use this entry point; API 33+ register the native back callback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }
    @Override public void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); releaseInput(); if (playing != null) showGame(); else showLibrary(); }
    @Override protected void onResume() { super.onResume(); if (session != null) session.setForeground(true); }
    @Override protected void onPause() { releaseInput(); session.setForeground(false); super.onPause(); }
    @Override protected void onDestroy() { destroyed = true; session.close(); io.shutdown(); super.onDestroy(); }
    @Override public void onWindowFocusChanged(boolean focused) { super.onWindowFocusChanged(focused); if (!focused && session != null) releaseInput(); }
    @Override public void loaded(boolean resumed) { if (playing != null && status != null) status.setText(resumed ? "Resumed" : "Playing"); }
    @Override public void message(String text) { if (!destroyed) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override public void failed(String text) { if (!destroyed) { playing = null; showLibrary(); message(text); } }
    @Override public void performance(int frames) { if (!destroyed && playing != null && !paused && status != null) status.setText(frames + " fps"); }

    private final class CartridgeArt extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean demo;
        CartridgeArt(boolean demo) { super(MainActivity.this); this.demo = demo; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight(); p.setColor(0xff454952); canvas.drawRoundRect(0, 0, w, h, dp(5), dp(5), p);
            p.setColor(0xff30343b); for (int i = 0; i < 4; i++) canvas.drawRect(w * .68f, h * (.06f + .07f*i), w * .92f, h * (.085f + .07f*i), p);
            p.setColor(demo ? 0xff274f43 : 0xff3e455c); canvas.drawRect(w * .12f, h * .36f, w * .88f, h * .85f, p);
            p.setColor(demo ? 0xffffd56f : ACCENT);
            if (demo) {
                float unit = w / 12f, x = w * .5f, y = h * .59f;
                canvas.drawRect(x-unit, y-3*unit, x+unit, y+3*unit, p); canvas.drawRect(x-3*unit, y-unit, x+3*unit, y+unit, p);
                canvas.drawRect(x-2*unit, y-2*unit, x+2*unit, y+2*unit, p);
            } else { p.setTypeface(Typeface.MONOSPACE); p.setTextSize(w * .42f); p.setTextAlign(Paint.Align.CENTER); canvas.drawText("8", w*.5f, h*.73f, p); }
            p.setColor(0xffaeb2ba); canvas.drawRect(w*.26f, h*.93f, w*.74f, h, p);
        }
    }
}
