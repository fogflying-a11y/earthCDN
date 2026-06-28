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
import com.bumptech.glide.request.FutureTarget;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ooo.oxo.apps.earth.cdn.CloudinaryClient;

public class EarthFetcher {

    private static final String TAG = "EarthFetcher";
    private static final String DEBUG_TAG = "WallpaperDebug";
    private static final int TILE_RETRY_COUNT = 3;
    private static final int API_RETRY_COUNT = 3;
    private static final long API_RETRY_BASE_DELAY_MS = 1000;

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
     * Fetch tiles via Cloudinary CDN and stitch them together.
     */
    private Bitmap fetchAndStitch(String imageId, int grid) throws Exception {
        Log.d(DEBUG_TAG, "fetchAndStitch(), imageId=" + imageId + ", grid=" + grid);
        int fullSize = grid * 550;
        Bitmap[] tiles = new Bitmap[grid * grid];

        if (!cdnClient.hasValidCloudName()) {
            throw new Exception("cloud_name not configured, please set it in Settings");
        }

        for (int y = 0; y < grid; y++) {
            for (int x = 0; x < grid; x++) {
                String originUrl = buildOriginUrl(imageId, grid, x, y);
                String cdnUrl = cdnClient.buildFetchUrl(originUrl);
                Log.d(DEBUG_TAG, "Cloudinary Fetch URL: " + cdnUrl);

                FutureTarget<Bitmap> f = rm.asBitmap()
                        .load(cdnUrl)
                        .override(550, 550)
                        .submit();
                synchronized (tileRequests) {
                    tileRequests.add(f);
                }
            }
        }

        return collectAndStitch(tiles, grid, fullSize, imageId);
    }

    /**
     * Collect downloaded tiles, retry any failures, then stitch.
     * Throws if any tile cannot be retrieved after retries.
     */
    private Bitmap collectAndStitch(Bitmap[] tiles, int grid, int fullSize, String imageId) throws Exception {
        List<FutureTarget<Bitmap>> futures;
        synchronized (tileRequests) {
            futures = new ArrayList<>(tileRequests);
        }

        List<Integer> failedIndices = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            if (cancelled) {
                throw new InterruptedException("fetch cancelled");
            }
            try {
                Bitmap tile = futures.get(i).get();
                if (tile != null && tile.getWidth() > 0) {
                    tiles[i] = tile;
                } else {
                    Log.w(TAG, "tile " + i + " returned empty bitmap");
                    failedIndices.add(i);
                }
            } catch (Exception e) {
                Log.w(TAG, "tile " + i + " failed: " + e.getMessage());
                failedIndices.add(i);
            }
        }

        for (int idx : failedIndices) {
            if (cancelled) {
                throw new InterruptedException("fetch cancelled");
            }
            String originUrl = buildOriginUrl(imageId, grid, idx % grid, idx / grid);
            String cdnUrl = cdnClient.buildFetchUrl(originUrl);
            Bitmap tile = null;
            for (int attempt = 1; attempt <= TILE_RETRY_COUNT; attempt++) {
                try {
                    Log.d(TAG, "retrying tile " + idx + ", attempt " + attempt + "/" + TILE_RETRY_COUNT);
                    FutureTarget<Bitmap> retry = rm.asBitmap()
                            .load(cdnUrl)
                            .override(550, 550)
                            .submit();
                    synchronized (tileRequests) {
                        tileRequests.add(retry);
                    }
                    tile = retry.get();
                    if (tile != null && tile.getWidth() > 0) {
                        Log.d(TAG, "tile " + idx + " succeeded on attempt " + attempt);
                        break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "retry " + attempt + "/" + TILE_RETRY_COUNT + " for tile " + idx + " failed: " + e.getMessage());
                }
            }
            if (tile != null && tile.getWidth() > 0) {
                tiles[idx] = tile;
            } else {
                throw new Exception("tile " + idx + " failed after " + TILE_RETRY_COUNT + " retries, aborting");
            }
        }

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
