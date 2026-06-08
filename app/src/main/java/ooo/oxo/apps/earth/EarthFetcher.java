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
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import ooo.oxo.apps.earth.cdn.CloudinaryClient;

public class EarthFetcher {

    private static final String TAG = "EarthFetcher";
    private static final String DEBUG_TAG = "WallpaperDebug";
    private static final int TILE_RETRY_COUNT = 3;

    private static final String TILE_URL_TEMPLATE =
            "https://himawari8.nict.go.jp/img/D531106/%dd/550/%04d/%02d/%02d/%s_%d_%d.png";

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
     * Calculate the quantized Himawari target timestamp.
     * UTC - 1.5 hours, then floor to the nearest whole hour.
     * Returns format: "2026/05/29/130000"
     */
    public static String getQuantizedImageId() {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.add(Calendar.MINUTE, -90); // UTC - 1.5 hours
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        // Format: yyyy/MM/dd/HH0000
        return String.format(Locale.US, "%04d/%02d/%02d/%02d0000",
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH),
                cal.get(Calendar.HOUR_OF_DAY));
    }

    /**
     * Build the origin Himawari tile URL.
     * imageId format: "2026/05/29/130000"
     * Result: https://himawari8.nict.go.jp/img/D531106/{grid}d/550/{yyyy}/{MM}/{dd}/{HHMMSS}_{x}_{y}.png
     */
    private static String buildOriginUrl(String imageId, int grid, int x, int y) {
        String[] parts = imageId.split("/");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);
        int day = Integer.parseInt(parts[2]);
        String hhmmss = parts[3]; // HHMMSS only, no date prefix

        // grid level must match the Nd path prefix
        String gridPath = grid + "d";

        return String.format(Locale.US,
                "https://himawari8.nict.go.jp/img/D531106/%s/550/%04d/%02d/%02d/%s_%d_%d.png",
                gridPath, year, month, day, hhmmss, x, y);
    }

    /**
     * Fetch the latest Himawari satellite image via Cloudinary CDN.
     * Returns a File containing the PNG image at the requested resolution.
     */
    public File fetch(int resolution) throws Exception {
        cancelled = false;
        tileRequests.clear();

        // Step 1: Calculate quantized timestamp
        String imageId = getQuantizedImageId();
        Log.d(TAG, "quantized image ID: " + imageId);

        // Check file cache to avoid re-downloading within the same hour
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

        return outFile;
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
