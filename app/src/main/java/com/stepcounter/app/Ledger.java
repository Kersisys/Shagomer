package com.stepcounter.app;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Журнал только на добавление. Каждая строка содержит хэш предыдущей, поэтому правка или
 * удаление записи в середине ломает цепочку, и это замечается (TamperedException).
 *
 * Честное ограничение: это защита от случайных ошибок и неумелой правки, а не от того, кто
 * умеет пересчитать цепочку или удалить хвост файла. Настоящую неизменяемость даёт только
 * сервер или банк, где хранится копия.
 *
 * Формат строки: номер|поле;поле;...|хэш
 */
final class Ledger {
    static final class TamperedException extends RuntimeException {
        TamperedException(String m) { super(m); }
    }

    private static final String GENESIS = "GENESIS";
    private final Path path;

    Ledger(File file) {
        this.path = file.toPath();
    }

    /** Добавляет запись. Поля не должны содержать символы ; | и перевод строки. */
    synchronized void append(String... fields) {
        for (String f : fields) {
            if (f.indexOf(';') >= 0 || f.indexOf('|') >= 0 || f.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Недопустимый символ в поле журнала");
            }
        }
        List<String> lines = readLines();
        String prev = GENESIS;
        if (!lines.isEmpty()) {
            String[] last = lines.get(lines.size() - 1).split("\\|", -1);
            prev = last.length == 3 ? last[2] : GENESIS;
        }
        verifyLines(lines); // не дописываем в уже испорченный журнал
        int seq = lines.size() + 1;
        String payload = String.join(";", fields);
        String line = seq + "|" + payload + "|" + hash(prev, seq, payload) + "\n";
        try {
            Files.write(path, line.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось записать журнал", e);
        }
    }

    /** Все записи (поля каждой записи). Бросает TamperedException, если цепочка нарушена. */
    synchronized List<String[]> records() {
        List<String> lines = readLines();
        verifyLines(lines);
        List<String[]> out = new ArrayList<>();
        for (String l : lines) {
            String payload = l.split("\\|", -1)[1];
            out.add(payload.split(";", -1));
        }
        return out;
    }

    private List<String> readLines() {
        if (!Files.exists(path)) return Collections.emptyList();
        try {
            List<String> all = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>();
            for (String l : all) if (!l.isEmpty()) out.add(l);
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось прочитать журнал", e);
        }
    }

    private void verifyLines(List<String> lines) {
        String prev = GENESIS;
        int expected = 1;
        for (String l : lines) {
            String[] p = l.split("\\|", -1);
            if (p.length != 3) throw new TamperedException("Битая строка " + expected);
            int seq;
            try { seq = Integer.parseInt(p[0]); } catch (NumberFormatException e) {
                throw new TamperedException("Битый номер в строке " + expected);
            }
            if (seq != expected) throw new TamperedException("Нарушен порядок на строке " + expected);
            if (!hash(prev, seq, p[1]).equals(p[2])) throw new TamperedException("Запись " + seq + " изменена");
            prev = p[2];
            expected++;
        }
    }

    private static String hash(String prev, int seq, String payload) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((prev + "|" + seq + "|" + payload).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
