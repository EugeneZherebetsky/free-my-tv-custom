package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional crypto prices (Binance, every 5 min) and weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Neither source needs a key. Location and currency come from config.properties.
 */
final class HomeData {
    static final String[] COINS = {"BTC", "ETH", "SOL", "USDT"};
    static final String[] COIN_NAMES = {"Bitcoin", "Ethereum", "Solana", "Dollar"};
    private static final String CURRENCY = Config.CURRENCY.trim().toUpperCase(Locale.ROOT);
    /** Binance quotes dollars as USDT. */
    private static final String QUOTE = CURRENCY.equals("USD") ? "USDT" : CURRENCY;
    private static final String WEATHER_QUERY = "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset"
            + "&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=2";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        return "https://api.open-meteo.com/v1/forecast?latitude=" + Prefs.latitude() + "&longitude=" + Prefs.longitude() + WEATHER_QUERY;
    }
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 30 * 60_000L, FX_EVERY = 5 * 60_000L;

    /**
     * Exchange rates (config fx=EUR,USD,RUB and fx_base=HUF): what one of each costs in the base currency, with the
     * change since the previous close. Live from Yahoo Finance (a pair it doesn't list, like RUB/HUF, through the
     * dollar); if that fails, the day's rates from the free currency-api (two mirrors). No key. With them set, the
     * quotes card shows these instead of crypto.
     */
    static final String[] FX = fxList(Config.FX);
    static final String FX_BASE = Config.FX_BASE.trim().toUpperCase(Locale.ROOT);

    private static String[] fxList(String config) {
        List<String> out = new ArrayList<>();
        for (String one : config.split(",")) {
            String code = one.trim().toUpperCase(Locale.ROOT);
            if (code.matches("[A-Z]{3}") && !out.contains(code) && out.size() < 4) out.add(code);
        }
        return out.toArray(new String[0]);
    }

    static boolean fxMode() {
        return FX.length > 0;
    }

    /** Indexes in FX of the rates the user has on (their own switches in Home Settings, all on at first). */
    static int[] fxActive() {
        int n = 0;
        int[] tmp = new int[FX.length];
        for (int i = 0; i < FX.length; i++) if (Prefs.fx(i)) tmp[n++] = i;
        return java.util.Arrays.copyOf(tmp, n);
    }

    static int fxCount() {
        return fxMode() && Prefs.quotes() ? fxActive().length : 0;
    }

    private static String[] fxUrls(String date, String base) {
        String path = "/v1/currencies/" + base.toLowerCase(Locale.ROOT) + ".json";
        return new String[] {"https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@" + date + path,
                "https://" + date + ".currency-api.pages.dev" + path};
    }
    interface Listener { void onData(); }

    /** BTC, ETH and SOL; in Brazilian reais also the dollar (USDT/BRL), which Binance doesn't list for most currencies. */
    static int coinCount() {
        return Prefs.quotes() && !fxMode() ? active().length : 0;
    }

    private static volatile int[] activeCoins;
    private static volatile int activeVersion = -1;

    /** Indexes in COINS of the coins the user has on (and that Binance lists in this currency). */
    static int[] active() {
        int[] a = activeCoins;
        if (a != null && activeVersion == Prefs.version) return a;
        int n = 0;
        int[] tmp = new int[COINS.length];
        for (int i = 0; i < COINS.length; i++) if (Prefs.coin(i) && (i < 3 || CURRENCY.equals("BRL"))) tmp[n++] = i;
        a = java.util.Arrays.copyOf(tmp, n);
        activeCoins = a;
        activeVersion = Prefs.version;
        return a;
    }

    /** Settings changed (city, coins): fetch again now. */
    void settingsChanged() {
        pricesAt = 0;
        weatherAt = 0;
        fxAt = 0;
    }

    static String coinName(int i) {
        return i == 3 ? Ui.tr("Dólar", "Dollar") : COIN_NAMES[i];
    }

    private static String pricesUrl() {
        StringBuilder symbols = new StringBuilder();
        int[] coins = active();
        for (int i = 0; i < coins.length; i++) symbols.append(i == 0 ? "" : ",").append("%22").append(COINS[coins[i]]).append(QUOTE).append("%22");
        return "https://api.binance.com/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D";
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    /** 24 h change (%), in the same order. */
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    volatile long pricesAt, weatherAt, fxAt;
    /** Price of one of each FX currency in FX_BASE, and its change (%) since the day before. */
    final double[] fxRates = new double[FX.length], fxChanges = new double[FX.length];
    volatile String fxDate = "";
    /** When the live quotes were made (0 = daily rates, shown with their date). */
    volatile long fxTime;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather, fetchingFx;

    private HomeData(Context c) {
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        java.util.Arrays.fill(fxRates, Double.NaN);
        java.util.Arrays.fill(fxChanges, Double.NaN);
        try {
            if (fxMode() && FX_BASE.equals(cache.getString("fxBase", ""))) {
                String live = cache.getString("fxLive", null);
                String now = cache.getString("fx", null);
                if (live != null) readLive(new JSONObject(live));
                else if (now != null) parseFx(now, cache.getString("fxPrev", null));
                fxAt = cache.getLong("fxAt", 0);
            }
            if (Prefs.quotes()) {
                String p = cache.getString("prices", null);
                if (p != null) parsePrices(p);
                pricesAt = cache.getLong("pricesAt", 0);
            }
            String w = cache.getString("weather", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                weatherAt = cache.getLong("weatherAt", 0);
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > PRICES_EVERY || now < pricesAt)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body = get(pricesUrl());
                    parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        if (!fetchingFx && fxCount() > 0 && (now - fxAt > FX_EVERY || now < fxAt)) {
            fetchingFx = true;
            io.execute(() -> {
                try {
                    try {
                        JSONObject live = liveFx();
                        readLive(live);
                        fxAt = System.currentTimeMillis();
                        cache.edit().putString("fxLive", live.toString()).putString("fxBase", FX_BASE).putLong("fxAt", fxAt).apply();
                        notifyListeners();
                        return;
                    } catch (Exception e) {
                        AppLog.w("live rates: " + e.getMessage());
                    }
                    String latest = getFirst(fxUrls("latest", FX_BASE));
                    String prev = null;
                    try {
                        prev = getFirst(fxUrls(dayBefore(new JSONObject(latest).optString("date")), FX_BASE));
                    } catch (Exception e) {
                        AppLog.w("rates, the day before: " + e.getMessage());
                    }
                    parseFx(latest, prev);
                    fxAt = System.currentTimeMillis();
                    cache.edit().putString("fx", latest).putString("fxPrev", prev).remove("fxLive").putString("fxBase", FX_BASE)
                            .putLong("fxAt", fxAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    AppLog.w("rates: " + e.getMessage());
                } finally {
                    fetchingFx = false;
                }
            });
        }
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String body = get(weatherUrl());
                    weather = parseWeather(body);
                    weatherAt = System.currentTimeMillis();
                    cache.edit().putString("weather", body).putLong("weatherAt", weatherAt).putString("weatherPlace", weatherPlace()).apply();
                    notifyListeners();
                } catch (Exception e) {
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private void parsePrices(String body) throws Exception {
        JSONArray list = new JSONArray(body);
        String quote = QUOTE;
        int found = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            String symbol = o.getString("symbol");
            for (int k = 0; k < COINS.length; k++) {
                if (!symbol.equals(COINS[k] + quote)) continue;
                found++;
                double last = Double.parseDouble(o.optString("lastPrice", o.optString("price", "NaN")));
                double open = Double.parseDouble(o.optString("openPrice", "NaN"));
                prices[k] = last;
                changes[k] = open > 0 ? (last - open) / open * 100 : Double.NaN;
            }
        }
        if (found == 0) pricesAt = 0;   // cached in another currency (config changed): fetch again
    }

    /**
     * Live quotes for every FX currency: {"r": [price…], "c": [change %…], "time": epoch ms}. A pair Yahoo doesn't
     * list (RUB/HUF) goes through the dollar: USD/HUF ÷ USD/RUB, for the price and for the previous close.
     */
    private static JSONObject liveFx() throws Exception {
        Map<String, double[]> seen = new HashMap<>();
        JSONArray r = new JSONArray(), ch = new JSONArray();
        long time = Long.MAX_VALUE;
        for (String code : FX) {
            double[] q = code.equals(FX_BASE) ? new double[] {1, 1, System.currentTimeMillis()} : yahoo(code + FX_BASE, seen);
            if (q == null) {
                double[] base = FX_BASE.equals("USD") ? new double[] {1, 1, System.currentTimeMillis()} : yahoo("USD" + FX_BASE, seen);
                double[] other = yahoo("USD" + code, seen);
                if (base == null || other == null) throw new IOException("no quote for " + code + "/" + FX_BASE);
                q = new double[] {base[0] / other[0], base[1] / other[1], Math.min(base[2], other[2])};
            }
            r.put(q[0]);
            ch.put(q[1] > 0 ? (q[0] - q[1]) / q[1] * 100 : Double.NaN);
            time = Math.min(time, (long) q[2]);
        }
        return new JSONObject().put("r", r).put("c", ch).put("time", time == Long.MAX_VALUE ? 0 : time);
    }

    /** Yahoo Finance quote for "EURHUF": {price, previous close, time (ms)}, or null if Yahoo doesn't list it. */
    private static double[] yahoo(String pair, Map<String, double[]> seen) throws Exception {
        if (seen.containsKey(pair)) return seen.get(pair);
        IOException last = null;
        for (String host : new String[] {"query1", "query2"}) {
            String url = "https://" + host + ".finance.yahoo.com/v8/finance/chart/" + pair + "=X?range=1d&interval=1d";
            try {
                JSONObject meta = new JSONObject(get(url, "Mozilla/5.0 (Linux; Android 11) FreeMyTV"))
                        .getJSONObject("chart").getJSONArray("result").getJSONObject(0).getJSONObject("meta");
                double price = meta.getDouble("regularMarketPrice");
                double prev = meta.optDouble("chartPreviousClose", meta.optDouble("previousClose", Double.NaN));
                double[] q = {price, prev, meta.optLong("regularMarketTime") * 1000.0};
                seen.put(pair, q);
                return q;
            } catch (IOException e) {
                if (String.valueOf(e.getMessage()).contains("HTTP 404")) {
                    seen.put(pair, null);
                    return null;
                }
                last = e;
            }
        }
        throw last;
    }

    private void readLive(JSONObject o) {
        JSONArray r = o.optJSONArray("r"), ch = o.optJSONArray("c");
        for (int i = 0; i < FX.length; i++) {
            fxRates[i] = r != null && i < r.length() ? r.optDouble(i, Double.NaN) : Double.NaN;
            fxChanges[i] = ch != null && i < ch.length() ? ch.optDouble(i, Double.NaN) : Double.NaN;
        }
        fxTime = o.optLong("time");
        fxDate = "";
    }

    /** currency-api gives what one base unit buys; the card shows the other way round (one euro in forints). */
    private void parseFx(String latest, String prev) throws Exception {
        String base = FX_BASE.toLowerCase(Locale.ROOT);
        JSONObject now = new JSONObject(latest);
        JSONObject rates = now.getJSONObject(base);
        JSONObject before = null;
        if (prev != null) {
            try { before = new JSONObject(prev).getJSONObject(base); } catch (Exception ignored) {}
        }
        for (int i = 0; i < FX.length; i++) {
            String code = FX[i].toLowerCase(Locale.ROOT);
            double r = rates.optDouble(code, Double.NaN);
            fxRates[i] = r > 0 ? 1 / r : Double.NaN;
            double p = before == null ? Double.NaN : before.optDouble(code, Double.NaN);
            double was = p > 0 ? 1 / p : Double.NaN;
            fxChanges[i] = was > 0 && !Double.isNaN(fxRates[i]) ? (fxRates[i] - was) / was * 100 : Double.NaN;
        }
        fxDate = now.optString("date", "");
        fxTime = 0;
    }

    /** "2026-10-19" → "2026-10-18". */
    private static String dayBefore(String date) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(f.parse(date).getTime() - 24 * 3600_000L));
    }

    /** The first of the mirrors that answers. */
    private static String getFirst(String[] urls) throws IOException {
        IOException last = null;
        for (String u : urls) {
            try {
                return get(u);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no source");
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        TimeZone zone = TimeZone.getTimeZone(o.optString("timezone", TimeZone.getDefault().getID()));
        Weather w = new Weather();
        JSONObject current = o.getJSONObject("current");
        w.temp = current.getDouble("temperature_2m");
        w.code = current.getInt("weather_code");
        w.isDay = current.optInt("is_day", 1) == 1;
        JSONObject daily = o.getJSONObject("daily");
        w.max = daily.getJSONArray("temperature_2m_max").getDouble(0);
        w.min = daily.getJSONArray("temperature_2m_min").getDouble(0);
        w.sunrise = localTime(daily.getJSONArray("sunrise").getString(0), zone);
        w.sunset = localTime(daily.getJSONArray("sunset").getString(0), zone);
        JSONObject hourly = o.optJSONObject("hourly");
        if (hourly != null) {
            JSONArray times = hourly.getJSONArray("time");
            JSONArray temps = hourly.getJSONArray("temperature_2m");
            JSONArray codes = hourly.getJSONArray("weather_code");
            JSONArray days = hourly.optJSONArray("is_day");
            long now = System.currentTimeMillis();
            for (int i = 0; i < times.length() && w.hours.size() < 6; i++) {
                long at = localTime(times.getString(i), zone);
                if (at <= now) continue;
                w.hours.add(at);
                w.hourTemp.add(temps.getDouble(i));
                w.hourCode.add(codes.getInt(i));
                w.hourDay.add(days == null || days.optInt(i, 1) == 1);
            }
        }
        return w;
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    private static String get(String url) throws IOException {
        return get(url, "FreeMyTV");
    }

    private static String get(String url, String userAgent) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", userAgent);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        return String.format(locale(), "%.2f", v);
    }

    /** 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        if (v >= 100) return String.format(locale(), "%.0f", v);
        return String.format(locale(), "%.2f", v);
    }

    /** $108,234 · $185.20 · R$ 581.234 · R$ 5,61 (cents only below a thousand), in the configured currency. */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        return v >= 1000 ? String.format(locale(), symbol + "%,.0f", v) : String.format(locale(), symbol + "%.2f", v);
    }

    /** One euro in forints: "362.80 Ft"; other bases with their own symbol. */
    static String fxPrice(double v) {
        if (Double.isNaN(v)) return "—";
        String n = String.format(locale(), v >= 1000 ? "%,.0f" : "%.2f", v);
        switch (FX_BASE) {
            case "HUF": return n + " Ft";
            case "USD": return "$" + n;
            case "EUR": return "€" + n;
            case "GBP": return "£" + n;
            case "BRL": return "R$ " + n;
            default: return n + " " + FX_BASE;
        }
    }

    /** The currency's sign, for the round badge on its row. */
    static String fxSymbol(String code) {
        switch (code) {
            case "EUR": return "€";
            case "USD": return "$";
            case "RUB": return "₽";
            case "GBP": return "£";
            case "JPY": case "CNY": return "¥";
            case "CHF": return "Fr";
            case "PLN": return "zł";
            case "UAH": return "₴";
            case "HUF": return "Ft";
            case "BRL": return "R$";
            default: return code.substring(0, 1);
        }
    }

    static String fxName(String code) {
        switch (code) {
            case "EUR": return "Euro";
            case "USD": return Ui.tr("Dólar", "US Dollar", "Dólar");
            case "RUB": return Ui.tr("Rublo", "Russian Ruble", "Rublo");
            case "GBP": return Ui.tr("Libra", "British Pound", "Libra");
            case "CHF": return Ui.tr("Franco suíço", "Swiss Franc", "Franco suizo");
            default: return code;
        }
    }

    /** The rates' date, short: "19 Oct" (they change once a day). */
    static String fxDay(String date) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            return new SimpleDateFormat(Ui.en ? "d MMM" : "d 'de' MMM", Ui.locale()).format(in.parse(date));
        } catch (ParseException e) {
            return date;
        }
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (Ui.en) {
            if (code == 0) return "Clear";
            if (code == 1) return day ? "Mostly Sunny" : "Mostly Clear";
            if (code == 2) return "Partly Cloudy";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code >= 51 && code <= 57) return "Drizzle";
            if (code == 65 || code == 67 || code == 82) return "Heavy Rain";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
            if (code >= 95) return "Thunderstorms";
            return "Rain";
        }
        if (Ui.es) {
            if (code == 0) return day ? "Despejado" : "Noche despejada";
            if (code == 1) return "Mayormente despejado";
            if (code == 2) return "Parcialmente nublado";
            if (code == 3) return "Nublado";
            if (code == 45 || code == 48) return "Niebla";
            if (code >= 51 && code <= 57) return "Llovizna";
            if (code == 65 || code == 67 || code == 82) return "Lluvia fuerte";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Nieve";
            if (code >= 95) return "Tormenta";
            return "Lluvia";
        }
        if (code == 0) return day ? "Céu limpo" : "Noite limpa";
        if (code == 1) return "Poucas nuvens";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code == 65 || code == 67 || code == 82) return "Chuva forte";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neve";
        if (code >= 95) return "Tempestade";
        return "Chuva";
    }

    private static String currencySymbol() {
        switch (CURRENCY) {
            case "USD": return "$";
            case "BRL": return "R$ ";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            default: return CURRENCY + " ";
        }
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign). */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return (v >= 0 ? "+" : "−") + String.format(locale(), "%.1f%%", Math.abs(v));
    }

    static String hour(long at) {
        return Ui.en ? new SimpleDateFormat(Ui.h24 ? "HH:mm" : "h a", Locale.US).format(new java.util.Date(at))
                : new SimpleDateFormat(Ui.es ? "HH:mm" : "HH'h'", Ui.locale()).format(new java.util.Date(at));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        if (Ui.en) return minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
        if (Ui.es) return minutes < 60 ? "hace " + minutes + " min" : "hace " + (minutes / 60) + " h";
        return minutes < 60 ? "há " + minutes + " min" : "há " + (minutes / 60) + " h";
    }
}
