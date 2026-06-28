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

import android.annotation.SuppressLint;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;

import ooo.oxo.apps.earth.dao.Earth;
import ooo.oxo.apps.earth.dao.Settings;
import ooo.oxo.apps.earth.provider.EarthsContract;
import ooo.oxo.apps.earth.provider.SettingsContract;

public class EarthWallpaperService extends WallpaperService {

    private static final String DEBUG_FLAG_FILE = "debug_mode";

    /**
     * Write the debug flag to a file using synchronous I/O.
     * This ensures cross-process visibility before the ContentProvider observer fires.
     * Must be called BEFORE ContentProvider.update() in the caller.
     */
    public static void writeDebugFlag(Context context, boolean enabled) {
        try (java.io.FileOutputStream fos = context.openFileOutput(DEBUG_FLAG_FILE, Context.MODE_PRIVATE)) {
            fos.write(enabled ? new byte[]{'1'} : new byte[]{'0'});
            fos.getFD().sync();
        } catch (Exception e) {
            Log.w("EarthWallpaperService", "failed to write debug flag", e);
        }
    }

    /**
     * Read the debug flag from a file using synchronous I/O.
     * Returns false if the file doesn't exist (default).
     */
    public static boolean readDebugFlag(Context context) {
        try (java.io.FileInputStream fis = context.openFileInput(DEBUG_FLAG_FILE)) {
            int b = fis.read();
            return b == '1';
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public Engine onCreateEngine() {
        return new EarthWallpaperEngine(this);
    }

    private class EarthWallpaperEngine extends Engine {

        private static final String TAG = "EarthWallpaperEngine";

        private Rect region;

        private Paint paint;

        private int padding;

        private ViewGroup overlay;
        private TextView overlayFetchedAt;
        private TextView overlayImageSize;

        private final ContentObserver earthObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                Log.d(TAG, "new earth ready, drawing...");
                draw();
            }
        };

        private final ContentObserver settingsObserver = new ContentObserver(null) {
            @Override
            public void onChange(boolean selfChange) {
                Log.d(TAG, "settings changed, redrawing...");
                draw();
            }
        };

        @SuppressLint("InflateParams")
        EarthWallpaperEngine(Context context) {
            region = new Rect();

            paint = new Paint();
            paint.setFilterBitmap(true);

            padding = getResources().getDimensionPixelOffset(R.dimen.default_padding);

            overlay = (ViewGroup) LayoutInflater.from(context)
                    .inflate(R.layout.overlay_debug, null);

            overlayFetchedAt = overlay.findViewById(R.id.fetched_at);
            overlayImageSize = overlay.findViewById(R.id.image_size);
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            draw();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (visible) {
                draw();
                getContentResolver().registerContentObserver(
                        EarthsContract.LATEST_CONTENT_URI, false, earthObserver);
                getContentResolver().registerContentObserver(
                        SettingsContract.CONTENT_URI, false, settingsObserver);
            } else {
                getContentResolver().unregisterContentObserver(earthObserver);
                getContentResolver().unregisterContentObserver(settingsObserver);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (isVisible()) {
                draw();
            }
        }

        private void draw() {
            Bitmap earth = loadLatestEarth();
            Settings settings = loadSettings();

            if (earth == null) {
                Log.d(TAG, "earth not ready, fallback to preview");
                earth = loadPreview();
            }

            if (earth == null) {
                Log.e(TAG, "earth preview not ready, impossible!");
                return;
            }

            Canvas canvas = getSurfaceHolder().lockCanvas();

            if (canvas == null) {
                Log.e(TAG, "canvas not ready, why?");
                return;
            }

            final int canvasWidth = canvas.getWidth();
            final int canvasHeight = canvas.getHeight();

            region.set(0, 0, canvasWidth, canvasHeight);

            if (region.width() > region.height()) {
                region.inset((region.width() - region.height()) / 2, 0);
            } else {
                region.inset(0, (region.height() - region.width()) / 2);
            }

            final float size = region.width() / 2;
            final int outlinePadding = (int) (size - ((size - padding) * settings.scale));
            region.inset(outlinePadding, outlinePadding);

            final float offsetX, offsetY;
            if (canvas.getWidth() > canvas.getHeight()) {
                offsetX = settings.offsetLong;
                offsetY = settings.offsetShort;
            } else {
                offsetX = settings.offsetShort;
                offsetY = settings.offsetLong;
            }
            region.offset((int) offsetX, (int) offsetY);

            canvas.drawColor(Color.BLACK);
            canvas.drawBitmap(earth, null, region, paint);

            if (settings.debug) {
                String fetchedAt = "never";
                float imageSize = 0;

                Earth metadata = loadLatestEarthMetadata();
                if (metadata != null) {
                    java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(
                            java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
                    fetchedAt = df.format(metadata.fetchedAt);

                    final File file = new File(metadata.file);
                    if (file.isFile()) {
                        imageSize = (float) file.length() / 1024f;
                    }
                }

                overlayFetchedAt.setText(getString(R.string.overlay_fetched_at, fetchedAt));
                overlayImageSize.setText(getString(R.string.overlay_image_size, imageSize));

                overlay.measure(View.MeasureSpec.makeMeasureSpec(canvasWidth, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(canvasHeight, View.MeasureSpec.EXACTLY));
                overlay.layout(0, 0, canvasWidth, canvasHeight);
                overlay.draw(canvas);
            }

            getSurfaceHolder().unlockCanvasAndPost(canvas);

            earth.recycle();
        }

        @Nullable
        private Bitmap loadLatestEarth() {
            try {
                return BitmapFactory.decodeStream(getContentResolver()
                        .openInputStream(EarthsContract.LATEST_CONTENT_URI));
            } catch (FileNotFoundException e) {
                return null;
            }
        }

        @Nullable
        private Earth loadLatestEarthMetadata() {
            final Cursor cursor = getContentResolver().query(EarthsContract.LATEST_CONTENT_URI,
                    null, null, null, null);

            if (cursor == null) {
                return null;
            }

            final Earth earth = Earth.fromCursor(cursor);

            cursor.close();

            return earth;
        }

        private Settings loadSettings() {
            Cursor cursor = getContentResolver().query(
                    SettingsContract.CONTENT_URI, null, null, null, null);

            if (cursor == null) {
                throw new IllegalStateException();
            }

            Settings settings = Settings.fromCursor(cursor);

            cursor.close();

            // Read debug flag via synchronous file I/O.
            // SharedPreferences has per-process memory caching that causes stale reads
            // across processes (:wallpaper vs main). This file is written synchronously
            // by SettingsFragment BEFORE the ContentProvider update, so by the time the
            // settingsObserver triggers draw(), the file already has the new value.
            settings.debug = readDebugFlag(EarthWallpaperService.this);

            return settings;
        }

        @Nullable
        private Bitmap loadPreview() {
            return BitmapFactory.decodeResource(getResources(), R.drawable.preview);
        }

    }

}
