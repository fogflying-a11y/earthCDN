/*
 * Mantou Earth - Live your wallpaper with live earth
 * Copyright (C) 2015-2019 XiNGRZ <xxx@oxo.ooo>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ooo.oxo.apps.earth;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.model.stream.HttpGlideUrlLoader;
import com.bumptech.glide.request.FutureTarget;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

import ooo.oxo.apps.earth.cdn.CloudinaryClient;

public class EarthFetcher {

    private static final String TAG = "EarthFetcher";
    private static final String DEBUG_TAG = "WallpaperDebug";
    private static final int TILE_RETRY_COUNT = 3;
    private static final long TILE_RETRY_BASE_DELAY_MS = 1000; // backoff: 1s / 2s / 4s
    private static final int API_RETRY_COUNT = 3;
    private static final long API_RETRY_BASE_DELAY_MS = 1000;
    /** Glide HTTP timeout for tile downloads (connect + read). Default 2500ms is too short
     *  for the Himawari origin server (TLS jitter ~35%, TTFB 2-7s). */
    private static final int TILE_HTTP_TIMEOUT_MS = 30_000;

    private static final String ORIGIN_HOST = "himawari.asia";
    private static final String TILE_PATH_PREFIX = "img/D531106";

    private final Context context;
    private final RequestManager rm;
    private final CloudinaryClient cdnClient;

    private volatile boolean cancelled;
    private final List<FutureTarget<Bitmap>> tileRequests = new ArrayList<>();

    public EarthFetcher(Context context, CloudinaryClient cdnClient) {
        this.context = context;
        this.rm = Glide.with(context);
        this.cdnClient = cdnClient;
    }

    /**
     * Fetch the latest image timestamp from himawari.asia with retry.
     * Returns format: "2026/06/26/062000"
     * Returns null after all retries exhausted.
     */
    public static String getLatestImageId() {
        for (int attempt = 1; attempt <= API_RETRY_COUNT; attempt++) {
            try {
                String result = fetchLatestImageIdOnce();
                if (result != null) {
                    if (attempt > 1) {
                        Log.d(TAG, "API succeeded on attempt " + attempt);
                    }
                    return result;
                }
                Log.w(TAG, "API returned null on attempt " + attempt + "/" + API_RETRY_COUNT);
            } catch (Exception e) {
                Log.w(TAG, "API failed on attempt " + attempt + "/" + API_RETRY_COUNT + ": " + e.getMessage());
            }
            if (attempt < API_RETRY_COUNT) {
                long delay = API_RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                Log.d(TAG, "retrying API in " + delay + "ms...");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "API retry interrupted", ie);
                    return null;
                }
            }
        }
        Log.e(TAG, "API failed after " + API_RETRY_COUNT + " attempts");
        return null;
    }

    private static String fetchLatestImageIdOnce() throws Exception {
        java.net.URL url = new java.net.URL("https://himawari.asia/img/D531106/latest.json");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        if (conn.getResponseCode() != 200) {
            conn.disconnect();
            throw new Exception("HTTP " + conn.getResponseCode());
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(conn.getInputStream()))) {
            String json = reader.readLine();
            // {"date":"2026-06-26 06:20:00","file":"..."}
            int start = json.indexOf("\"date\":\"") + 8;
            int end = json.indexOf("\"", start);
            if (start < 8 || end < 0) {
                throw new Exception("malformed JSON: " + json);
            }
            String dateStr = json.substring(start, end);
            return dateStr.replace("-", "/")
                          .replace(":", "")
                          .replace(" ", "/");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Build the origin Himawari tile URL.
     * imageId format: "2026/05/29/130000"
     * Result: https://himawari.asia/img/D531106/{grid}d/550/{yyyy}/{MM}/{dd}/{HHMMSS}_{x}_{y}.png
     */
    private static String buildOriginUrl(String imageId, int grid, int x, int y) {
        String[] parts = imageId.split("/");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int day = Integer.parseInt(parts[2]);
        String hhmmss = parts[3]; // HHMMSS only, no date prefix

        return String.format(Locale.US,
                "https://%s/%s/%s/550/%04d/%02d/%02d/%s_%d_%d.png",
                ORIGIN_HOST, TILE_PATH_PREFIX, grid + "d", year, month, day, hhmmss, x, y);
    }

    /**
     * Fetch the latest Himawari satellite image via Cloudinary CDN.
     * Returns a File containing the PNG image at the requested resolution.
     */
    public File fetch(int resolution) throws Exception {
        cancelled = false;
        tileRequests.clear();

        // Step 1: Fetch latest timestamp from API
        String imageId = getLatestImageId();
        if (imageId == null) {
            throw new Exception("failed to fetch latest timestamp from API");
        }
        Log.d(TAG, "latest image ID: " + imageId);

        // Check file cache to avoid re-downloading the same frame
        File outFile = new File(context.getCacheDir(), "earth_" + imageId.replace("/", "_") + ".png");
        if (outFile.exists()) {
            Log.d(TAG, "reusing cached file: " + outFile.getAbsolutePath());
            return outFile;
        }

        // Step 2: Determine grid level based on resolution
        int grid = resolutionToGrid(resolution);

        Bitmap composed = fetchAndStitch(imageId, grid);

        // Step 3: Save to file
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            composed.compress(Bitmap.CompressFormat.PNG, 100, fos);
        }
        composed.recycle();

        Log.d(TAG, "saved earth image: " + outFile.getAbsolutePath() + " (" + resolution + "x" + resolution + ")");

        // Clean up old cached earth images (keep only last 48h)
        cleanOldCache(outFile.getName());

        return outFile;
    }

    private static final long CACHE_MAX_AGE_MS = 48L * 60 * 60 * 1000;

    private void cleanOldCache(String keepFileName) {
        final long cutoff = System.currentTimeMillis() - CACHE_MAX_AGE_MS;
        File cacheDir = context.getCacheDir();
        File[] cached = cacheDir.listFiles((dir, name) ->
                name.startsWith("earth_") && name.endsWith(".png") && !name.equals(keepFileName));
        if (cached == null) return;

        for (File f : cached) {
            if (f.lastModified() < cutoff) {
                if (f.delete()) {
                    Log.d(TAG, "cleaned old cache: " + f.getName());
                }
            }
        }
    }

    /**
     * Resolve the actual download URL for a tile.
     * CDN mode (cloud_name configured): wrap the origin URL via Cloudinary fetch.
     * Origin mode (cloud_name empty): use the himawari.asia origin URL directly.
     */
    private String resolveTileUrl(String originUrl) {
        if (cdnClient.hasValidCloudName()) {
            return cdnClient.buildFetchUrl(originUrl);
        }
        return originUrl;
    }

    private String modeName() {
        return cdnClient.hasValidCloudName() ? "cdn" : "origin";
    }

    /**
     * Fetch tiles sequentially (via Cloudinary CDN or directly from the origin)
     * and stitch them together. Sequential downloading reduces concurrent connection
     * pressure on the origin server (himawari.asia has ~35% TLS jitter).
     */
    private Bitmap fetchAndStitch(String imageId, int grid) throws Exception {
        Log.d(DEBUG_TAG, "fetchAndStitch(), imageId=" + imageId + ", grid=" + grid + ", mode=" + modeName());
        int fullSize = grid * 550;
        int tileCount = grid * grid;
        Bitmap[] tiles = new Bitmap[tileCount];

        for (int idx = 0; idx < tileCount; idx++) {
            if (cancelled) {
                throw new InterruptedException("fetch cancelled");
            }
            int x = idx % grid;
            int y = idx / grid;
            String originUrl = buildOriginUrl(imageId, grid, x, y);
            String tileUrl = resolveTileUrl(originUrl);
            Log.d(DEBUG_TAG, modeName() + " tile " + idx + "/" + (tileCount - 1) + " URL: " + tileUrl);

            tiles[idx] = downloadTileWithRetry(tileUrl, idx);
        }

        // Stitch all tiles into the final image
        Bitmap composed = Bitmap.createBitmap(fullSize, fullSize, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(composed);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int y = 0; y < grid; y++) {
            for (int x = 0; x < grid; x++) {
                canvas.drawBitmap(tiles[y * grid + x], x * 550f, y * 550f, paint);
            }
        }

        for (Bitmap t : tiles) {
            if (t != null) t.recycle();
        }

        return composed;
    }

    /**
     * Download a single tile with retry (exponential backoff: 1s / 2s / 4s).
     * Non-retryable errors (HTTP 4xx, DNS failure) abort immediately.
     */
    private Bitmap downloadTileWithRetry(String tileUrl, int idx) throws Exception {
        Throwable lastError = null;

        for (int attempt = 0; attempt <= TILE_RETRY_COUNT; attempt++) {
            if (attempt > 0) {
                // Backoff before retry: 1s / 2s / 4s
                long delay = TILE_RETRY_BASE_DELAY_MS * (1L << (attempt - 1));
                Log.d(TAG, "retrying tile " + idx + " in " + delay + "ms, attempt " + attempt + "/" + TILE_RETRY_COUNT);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedException("fetch cancelled");
                }
            }
            if (cancelled) {
                throw new InterruptedException("fetch cancelled");
            }
            try {
                FutureTarget<Bitmap> future = rm.asBitmap()
                        .load(tileUrl)
                        .override(550, 550)
                        .set(HttpGlideUrlLoader.TIMEOUT, TILE_HTTP_TIMEOUT_MS)
                        .submit();
                synchronized (tileRequests) {
                    tileRequests.add(future);
                }
                Bitmap bmp = future.get();
                if (bmp != null && bmp.getWidth() > 0) {
                    if (attempt > 0) {
                        Log.d(TAG, "tile " + idx + " succeeded on retry attempt " + attempt);
                    }
                    return bmp;
                }
                lastError = new Exception("empty bitmap");
                Log.w(TAG, "tile " + idx + " returned empty bitmap");
            } catch (CancellationException ce) {
                throw new InterruptedException("fetch cancelled");
            } catch (Exception e) {
                lastError = e;
                if (!isTransient(e)) {
                    throw new Exception("tile " + idx + " failed with non-retryable error, aborting: "
                            + e.getMessage(), e);
                }
                Log.w(TAG, "tile " + idx + " attempt " + attempt + " failed (transient): " + e.getMessage());
            }
        }

        throw new Exception("tile " + idx + " failed after " + TILE_RETRY_COUNT
                + " retries, aborting: " + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    /**
     * Transient-error detection aligned with ELS CloudinaryUpload.IsTransient:
     * - HTTP status -1 (no response / connection-level failure) → retryable
     * - HTTP 5xx (server error) → retryable
     * - Timeout / connect failure / connection reset / IOException → retryable
     * - HTTP 4xx (client error) → non-retryable, abort tile
     * - DNS failure (UnknownHostException) → non-retryable, abort tile
     * Walks the cause chain including GlideException.getRootCauses() since Glide
     * wraps network errors.
     */
    private static boolean isTransient(Throwable t) {
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof com.bumptech.glide.load.HttpException) {
                int code = ((com.bumptech.glide.load.HttpException) cause).getStatusCode();
                // status -1: connection-level failure (no HTTP response received) → transient
                // status >= 500: server error → transient
                // status 4xx: client error (bad cloud_name, missing tile) → non-retryable
                return code < 0 || code >= 500;
            }
            if (cause instanceof java.net.SocketTimeoutException    // Timeout
                    || cause instanceof java.net.SocketException) { // ConnectFailure / ConnectionClosed / reset
                return true;
            }
            if (cause instanceof java.net.UnknownHostException) {   // DNS resolution failure → non-retryable
                return false;
            }
            if (cause instanceof com.bumptech.glide.load.engine.GlideException) {
                for (Throwable root : ((com.bumptech.glide.load.engine.GlideException) cause).getRootCauses()) {
                    if (isTransient(root)) {
                        return true;
                    }
                }
            }
            if (cause instanceof java.io.IOException) {
                // Catch-all for network I/O errors (SSLException, EOFException, etc.)
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * Map resolution to grid size.
     * 550p → 1x1 grid, 1100p → 2x2 grid, 2200p/4400p → 4x4 grid.
     */
    private static int resolutionToGrid(int resolution) {
        if (resolution <= 550) return 1;
        if (resolution <= 1100) return 2;
        return 4;
    }

    public void clean() {
        cancelled = true;
        synchronized (tileRequests) {
            for (FutureTarget<Bitmap> f : tileRequests) {
                f.cancel(true);
            }
        }
    }
}
