package com.stepcounter.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Журнал еды по дням и список своих блюд (КБЖУ на порцию).
 * Два простых текстовых файла. Чистая Java, без Android.
 *
 * Формат строки: день|название|ккал|белки|жиры|углеводы  (у сохранённых блюд день равен "-").
 * Индекс строки в файле служит номером записи для удаления.
 */
final class FoodBook {

    static final class Entry {
        final String day;
        final String name;
        final int kcal, protein, fat, carbs;
        final int idx; // номер строки в файле, -1 если запись ещё не в файле

        Entry(String day, String name, int kcal, int protein, int fat, int carbs, int idx) {
            this.day = day;
            this.name = name;
            this.kcal = kcal;
            this.protein = protein;
            this.fat = fat;
            this.carbs = carbs;
            this.idx = idx;
        }

        /** Несколько порций: КБЖУ умножается, к названию добавляется "×N". */
        Entry scaled(double portions) {
            String suffix = portions == Math.rint(portions)
                    ? String.format(Locale.US, " ×%d", (long) portions)
                    : String.format(Locale.US, " ×%.1f", portions);
            if (portions == 1.0) suffix = "";
            return new Entry(day, name + suffix,
                    (int) Math.round(kcal * portions), (int) Math.round(protein * portions),
                    (int) Math.round(fat * portions), (int) Math.round(carbs * portions), -1);
        }
    }

    private static final String NO_DAY = "-";
    private final Path logPath;
    private final Path dishPath;

    FoodBook(File dir) {
        this.logPath = new File(dir, "meals.txt").toPath();
        this.dishPath = new File(dir, "dishes.txt").toPath();
    }

    // ---------- Журнал по дням ----------

    synchronized List<Entry> entries(String day) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : read(logPath)) if (e.day.equals(day)) out.add(e);
        return out;
    }

    synchronized void add(String day, String name, int kcal, int protein, int fat, int carbs) {
        append(logPath, day, name, kcal, protein, fat, carbs);
    }

    synchronized void remove(int idx) {
        removeLine(logPath, idx);
    }

    // ---------- Свои блюда ----------

    synchronized List<Entry> dishes() {
        return read(dishPath);
    }

    /** Сохраняет блюдо. Если блюдо с таким названием уже есть, заменяет его. */
    synchronized void saveDish(String name, int kcal, int protein, int fat, int carbs) {
        String clean = clean(name);
        for (Entry e : read(dishPath)) {
            if (e.name.equalsIgnoreCase(clean)) removeLine(dishPath, e.idx);
        }
        append(dishPath, NO_DAY, name, kcal, protein, fat, carbs);
    }

    synchronized void removeDish(int idx) {
        removeLine(dishPath, idx);
    }

    // ---------- Сводка ----------

    /** Суммы: {ккал, белки, жиры, углеводы}. */
    static int[] totals(List<Entry> list) {
        int[] t = new int[4];
        for (Entry e : list) {
            t[0] += e.kcal;
            t[1] += e.protein;
            t[2] += e.fat;
            t[3] += e.carbs;
        }
        return t;
    }

    // ---------- Файлы ----------

    static String clean(String name) {
        String s = name.replace('|', ' ').replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) s = "Без названия";
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static void append(Path path, String day, String name, int kcal, int p, int f, int c) {
        String line = day + "|" + clean(name) + "|" + kcal + "|" + p + "|" + f + "|" + c + "\n";
        try {
            Files.write(path, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось сохранить запись", e);
        }
    }

    private static List<String> lines(Path path) {
        if (!Files.exists(path)) return new ArrayList<>();
        try {
            return new ArrayList<>(Files.readAllLines(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать файл", e);
        }
    }

    private static List<Entry> read(Path path) {
        List<Entry> out = new ArrayList<>();
        List<String> all = lines(path);
        for (int i = 0; i < all.size(); i++) {
            String[] a = all.get(i).split("\\|", -1);
            if (a.length != 6) continue; // битая строка пропускается, но номера остальных не сдвигаются
            try {
                out.add(new Entry(a[0], a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]),
                        Integer.parseInt(a[4]), Integer.parseInt(a[5]), i));
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    private static void removeLine(Path path, int idx) {
        List<String> all = lines(path);
        if (idx < 0 || idx >= all.size()) return;
        all.remove(idx);
        try {
            Files.write(path, all, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось удалить запись", e);
        }
    }
}
