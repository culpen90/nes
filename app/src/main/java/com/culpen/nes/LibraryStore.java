package com.culpen.nes;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Copies imports into private storage; play never depends on a picker URI. */
public final class LibraryStore {
    private static final int LIMIT = 32 * 1024 * 1024;
    private final Context context;
    public final File roms, saves;
    public static final class Game {
        public final String key, name;
        public final File file;
        public final boolean demo;
        Game(String key, String name, File file, boolean demo) {
            this.key = key; this.name = name; this.file = file; this.demo = demo;
        }
    }
    public LibraryStore(Context context) {
        this.context = context.getApplicationContext();
        roms = new File(context.getFilesDir(), "roms"); roms.mkdirs();
        saves = new File(context.getFilesDir(), "saves"); saves.mkdirs();
    }
    public Game ensureDemo() throws IOException {
        File file = new File(roms, "demo.nes");
        if (!file.isFile()) try (InputStream in = context.getAssets().open("demo.nes")) {
            writeAtomic(file, readLimited(in));
        }
        return new Game("demo", "Star Garden", file, true);
    }
    public synchronized List<Game> games() {
        List<Game> result = new ArrayList<>();
        result.add(new Game("demo", "Star Garden", new File(roms, "demo.nes"), true));
        try {
            JSONArray json = new JSONArray(context.getSharedPreferences("library", 0).getString("games", "[]"));
            for (int i = 0; i < json.length(); i++) {
                JSONObject row = json.getJSONObject(i);
                String key = row.getString("key");
                if (!key.matches("[a-f0-9]{64}")) continue;
                File file = new File(roms, key + ".nes");
                if (file.isFile()) result.add(new Game(key, row.getString("name"), file, false));
            }
        } catch (Exception ignored) { }
        return result;
    }
    public Game importGame(Uri uri) throws Exception {
        String name = "NES game";
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) name = cursor.getString(0);
        }
        byte[] data;
        try (InputStream raw = context.getContentResolver().openInputStream(uri)) {
            if (raw == null) throw new IOException("This file could not be opened.");
            BufferedInputStream in = new BufferedInputStream(raw);
            in.mark(4);
            int first = in.read(), second = in.read(); in.reset();
            if (first == 'P' && second == 'K') {
                data = null;
                try (ZipInputStream zip = new ZipInputStream(in)) {
                    ZipEntry entry;
                    int inspected = 0, expanded = 0;
                    while ((entry = zip.getNextEntry()) != null) {
                        if (++inspected > 128) throw new IOException("This ZIP has too many entries. Import the .nes file directly.");
                        if (!entry.isDirectory() && entry.getName().toLowerCase(Locale.ROOT).endsWith(".nes")) {
                            if (data != null) throw new IOException("This ZIP has multiple games. Import one .nes file at a time.");
                            data = readLimited(zip, LIMIT - expanded);
                            expanded += data.length;
                            name = new File(entry.getName()).getName();
                        } else if (!entry.isDirectory()) {
                            // Bound skipped entries too, so compressed archives cannot exhaust the importer.
                            expanded += readLimited(zip, LIMIT - expanded).length;
                        }
                        zip.closeEntry();
                    }
                }
                if (data == null) throw new IOException("No .nes game was found in this ZIP.");
            } else data = readLimited(in);
        }
        validateRom(data);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder key = new StringBuilder();
        for (byte b : hash) key.append(String.format(Locale.ROOT, "%02x", b & 255));
        File file = new File(roms, key + ".nes");
        writeAtomic(file, data);
        name = name.replaceFirst("(?i)\\.nes$", "").replace('_', ' ').trim();
        if (name.isEmpty()) name = "NES game";
        Game game = new Game(key.toString(), name, file, false);
        synchronized (this) {
            JSONArray rows = new JSONArray();
            for (Game old : games()) if (!old.demo && !old.key.equals(game.key)) {
                rows.put(new JSONObject().put("key", old.key).put("name", old.name));
            }
            rows.put(new JSONObject().put("key", game.key).put("name", game.name));
            context.getSharedPreferences("library", 0).edit().putString("games", rows.toString()).commit();
        }
        return game;
    }
    static void validateRom(byte[] data) throws IOException {
        if (data.length < 16 || data[0] != 'N' || data[1] != 'E' || data[2] != 'S' || data[3] != 0x1a)
            throw new IOException("Choose an iNES or NES 2.0 .nes cartridge file.");
        boolean nes2 = (data[7] & 0x0c) == 0x08;
        long prg = data[4] & 255, chr = data[5] & 255;
        if (nes2) {
            int highPrg = data[9] & 15, highChr = (data[9] >>> 4) & 15;
            prg = highPrg == 15 ? exponentSize(data[4]) : ((highPrg << 8) | (data[4] & 255)) * 16384L;
            chr = highChr == 15 ? exponentSize(data[5]) : ((highChr << 8) | (data[5] & 255)) * 8192L;
        } else { prg *= 16384; chr *= 8192; }
        long required = 16L + ((data[6] & 4) != 0 ? 512 : 0) + prg + chr;
        if (prg == 0 || required > data.length) throw new IOException("This cartridge file is incomplete or damaged.");
    }
    private static long exponentSize(byte value) {
        int n = value & 255, exponent = n >>> 2;
        return exponent > 30 ? Long.MAX_VALUE / 4 : (1L << exponent) * ((n & 3) * 2L + 1);
    }
    static byte[] readLimited(InputStream in) throws IOException {
        return readLimited(in, LIMIT);
    }
    private static byte[] readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384]; int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() + count > limit) throw new IOException("ROM files and expanded ZIP archives must be smaller than 32 MB.");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    static void writeAtomic(File file, byte[] bytes) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { out.write(bytes); out.getFD().sync(); }
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
