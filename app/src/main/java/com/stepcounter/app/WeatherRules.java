package com.stepcounter.app;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Решает, насколько плохая погода в дневное окно (09:00-21:00) указанной даты.
 * Только объективные пороги по данным Open-Meteo, никакого "уговаривания": одни и те же
 * числа всегда дают один и тот же ответ. Чистая Java, проверяется тестами без сети.
 */
final class WeatherRules {
    private WeatherRules() { }

    enum Level { NONE, SOFT, FULL }

    static final class Assessment {
        final Level level;
        final String reasons; // без ';' и '|', пригодно для журнала
        Assessment(Level level, String reasons) { this.level = level; this.reasons = reasons; }
    }

    static final int FROM_HOUR = 9;
    static final int TO_HOUR = 21;

    // Полный отказ от цели
    static final double FULL_COLD = -32, FULL_GUST = 25, FULL_SNOW_CM = 12, FULL_AQI = 100;
    // Цель вдвое меньше
    static final double SOFT_COLD = -22, SOFT_HEAT = 32, SOFT_GUST = 15, SOFT_RAIN_MM = 6,
            SOFT_SNOW_CM = 3, SOFT_AQI = 75, SOFT_PM_FULL = 100, SOFT_PM = 45;
    // Сырая погода (дождь со снегом, слякоть): осадки от 3 мм при ощущаемой от -5 до +3 °C
    static final double WET_MM = 3, WET_FROM = -5, WET_TO = 3;

    private static final Set<Integer> FULL_CODES = new HashSet<>(Arrays.asList(95, 96, 99, 56, 57, 66, 67));
    private static final Set<Integer> SOFT_CODES = new HashSet<>(Arrays.asList(65, 82, 75, 86));

    /** airJson может быть null (сервис качества воздуха недоступен). Бросает IllegalArgumentException, если данных нет. */
    static Assessment assess(String forecastJson, String airJson, LocalDate target) {
        Map<String, Object> hourly = hourly(forecastJson);
        List<?> time = list(hourly, "time");
        List<?> app = list(hourly, "apparent_temperature");
        List<?> pr = list(hourly, "precipitation");
        List<?> sn = list(hourly, "snowfall");
        List<?> gu = list(hourly, "wind_gusts_10m");
        List<?> wc = list(hourly, "weather_code");

        int hours = 0;
        double minApp = Double.POSITIVE_INFINITY, maxApp = Double.NEGATIVE_INFINITY;
        double maxGust = 0, rain = 0, snow = 0;
        Set<Integer> codes = new HashSet<>();
        for (int k = 0; k < time.size(); k++) {
            if (!inWindow(time.get(k), target)) continue;
            hours++;
            Double a = num(app, k);
            if (a != null) { minApp = Math.min(minApp, a); maxApp = Math.max(maxApp, a); }
            Double g = num(gu, k);
            if (g != null) maxGust = Math.max(maxGust, g);
            Double p = num(pr, k);
            if (p != null) rain += p;
            Double s = num(sn, k);
            if (s != null) snow += s;
            Double c = num(wc, k);
            if (c != null) codes.add((int) Math.round(c));
        }
        if (hours < 8) throw new IllegalArgumentException("В прогнозе нет данных на " + target);

        double aqi = Double.NaN, pm = Double.NaN;
        if (airJson != null) {
            try {
                Map<String, Object> ah = hourly(airJson);
                List<?> at = list(ah, "time");
                List<?> ae = ah.get("european_aqi") instanceof List ? list(ah, "european_aqi") : null;
                List<?> ap = ah.get("pm2_5") instanceof List ? list(ah, "pm2_5") : null;
                for (int k = 0; k < at.size(); k++) {
                    if (!inWindow(at.get(k), target)) continue;
                    Double e = ae == null ? null : num(ae, k);
                    if (e != null) aqi = Double.isNaN(aqi) ? e : Math.max(aqi, e);
                    Double m = ap == null ? null : num(ap, k);
                    if (m != null) pm = Double.isNaN(pm) ? m : Math.max(pm, m);
                }
            } catch (IllegalArgumentException ignored) {
                // воздух необязателен: решаем по остальному
            }
        }

        List<String> full = new ArrayList<>();
        List<String> soft = new ArrayList<>();
        for (int c : codes) {
            if (FULL_CODES.contains(c)) full.add(codeName(c));
            else if (SOFT_CODES.contains(c)) soft.add(codeName(c));
        }
        if (minApp <= FULL_COLD) full.add(f("сильный мороз, ощущается %.0f °C", minApp));
        else if (minApp <= SOFT_COLD) soft.add(f("мороз, ощущается %.0f °C", minApp));
        if (rain + snow * 0.8 >= WET_MM && minApp <= WET_TO && maxApp >= WET_FROM
                && minApp > SOFT_COLD && rain >= 1) {
            soft.add(f("сырая погода, осадки %.0f мм около нуля", rain));
        }
        if (maxApp >= SOFT_HEAT) soft.add(f("жара, ощущается %.0f °C", maxApp));
        if (maxGust >= FULL_GUST) full.add(f("шквальный ветер %.0f м/с", maxGust));
        else if (maxGust >= SOFT_GUST) soft.add(f("сильный ветер %.0f м/с", maxGust));
        if (snow >= FULL_SNOW_CM) full.add(f("снегопад %.0f см", snow));
        else if (snow >= SOFT_SNOW_CM) soft.add(f("снег %.0f см", snow));
        if (rain >= SOFT_RAIN_MM) soft.add(f("осадки %.0f мм", rain));
        if (!Double.isNaN(aqi)) {
            if (aqi >= FULL_AQI) full.add(f("очень грязный воздух, индекс %.0f", aqi));
            else if (aqi >= SOFT_AQI) soft.add(f("грязный воздух, индекс %.0f", aqi));
        } else if (!Double.isNaN(pm)) {
            if (pm >= SOFT_PM_FULL) full.add(f("очень грязный воздух, PM2.5 %.0f", pm));
            else if (pm >= SOFT_PM) soft.add(f("грязный воздух, PM2.5 %.0f", pm));
        }

        if (!full.isEmpty()) {
            List<String> all = new ArrayList<>(full);
            all.addAll(soft);
            return new Assessment(Level.FULL, String.join(", ", all));
        }
        if (!soft.isEmpty()) return new Assessment(Level.SOFT, String.join(", ", soft));
        return new Assessment(Level.NONE, "погода обычная");
    }

    private static String f(String fmt, double v) { return String.format(Locale.US, fmt, v); }

    private static String codeName(int c) {
        switch (c) {
            case 95: case 96: case 99: return "гроза";
            case 56: case 57: return "ледяная морось";
            case 66: case 67: return "ледяной дождь";
            case 65: return "сильный дождь";
            case 82: return "сильный ливень";
            case 75: return "сильный снегопад";
            case 86: return "сильный снежный заряд";
            default: return "код погоды " + c;
        }
    }

    private static boolean inWindow(Object t, LocalDate target) {
        if (!(t instanceof String)) return false;
        String s = (String) t; // 2026-11-11T09:00
        if (s.length() < 13 || !s.startsWith(target + "T")) return false;
        int h = Integer.parseInt(s.substring(11, 13));
        return h >= FROM_HOUR && h <= TO_HOUR;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> hourly(String json) {
        Object root = MiniJson.parse(json);
        if (!(root instanceof Map)) throw new IllegalArgumentException("Ответ погоды не объект");
        Object h = ((Map<String, Object>) root).get("hourly");
        if (!(h instanceof Map)) throw new IllegalArgumentException("В ответе нет почасовых данных");
        return (Map<String, Object>) h;
    }

    private static List<?> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (!(v instanceof List)) throw new IllegalArgumentException("В ответе нет поля " + key);
        return (List<?>) v;
    }

    private static Double num(List<?> l, int k) {
        if (k >= l.size()) return null;
        Object v = l.get(k);
        return v instanceof Double ? (Double) v : null;
    }
}
