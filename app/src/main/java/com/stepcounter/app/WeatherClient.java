package com.stepcounter.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Загрузка прогноза Open-Meteo. Вместе с данными берётся время сервера из заголовка Date. */
final class WeatherClient {
    private WeatherClient() { }

    static final double LAT = 56.0153, LON = 92.8932; // Красноярск
    private static final String TZ = "Asia%2FKrasnoyarsk";

    static final class Data {
        String forecast;
        String air;       // null, если сервис воздуха не ответил
        long serverMs;    // время сервера (мс с 1970)
    }

    /** Блокирующий вызов: только из фонового потока. */
    static Data fetch() throws IOException {
        Data d = new Data();
        long[] date = new long[1];
        d.forecast = get("https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                + "&hourly=apparent_temperature,precipitation,snowfall,wind_gusts_10m,weather_code"
                + "&wind_speed_unit=ms&timezone=" + TZ + "&forecast_days=3", date);
        if (date[0] == 0) throw new IOException("Сервер не прислал время");
        d.serverMs = date[0];
        try {
            d.air = get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + LAT
                    + "&longitude=" + LON + "&hourly=pm2_5,european_aqi&timezone=" + TZ
                    + "&forecast_days=3", new long[1]);
        } catch (IOException e) {
            d.air = null;
        }
        return d;
    }

    /** Время сервера (мс), для проверки кодов. Блокирующий вызов. */
    static long serverNow() throws IOException {
        long[] d = new long[1];
        get("https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                + "&hourly=weather_code&forecast_days=1", d);
        if (d[0] == 0) throw new IOException("Сервер не прислал время");
        return d[0];
    }

    private static String get(String url, long[] dateOut) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            dateOut[0] = c.getDate();
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 2_000_000) throw new IOException("Ответ слишком большой");
                }
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }
}
