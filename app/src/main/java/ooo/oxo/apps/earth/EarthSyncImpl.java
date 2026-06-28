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

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.PowerManager;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.net.ConnectivityManagerCompat;

import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import ooo.oxo.apps.earth.dao.Earth;
import ooo.oxo.apps.earth.dao.Settings;
import ooo.oxo.apps.earth.provider.EarthsContract;
import ooo.oxo.apps.earth.provider.SettingsContract;
import ooo.oxo.apps.earth.cdn.CloudinaryClient;

public class EarthSyncImpl {

    private static final String TAG = "EarthSyncImpl";

    public static final String RESULT_ERROR = "error";
    public static final String RESULT_ERROR_MESSAGE = "error_message";
    public static final String RESULT_DELAY_UNTIL = "delay_until";
    public static final String RESULT_INSERTS = "inserts";
    public static final String RESULT_DELETES = "deletes";

    public static final long ERROR_DB = 1L;
    public static final long ERROR_IO = 2L;
    public static final long ERROR_CDN_NOT_CONFIGURED = 3L;
    public static final long ERROR_API_FAILED = 4L;
    public static final long ERROR_TILE_FETCH_FAILED = 5L;
    public static final long ERROR_SKIPPED_METERED = 6L;

    private final Context context;

    private final PowerManager pm;
    private final ConnectivityManager cm;
    private final ContentResolver resolver;

    private EarthFetcher fetcher;

    public EarthSyncImpl(Context context) {
        this.context = context;

        this.pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.resolver = context.getContentResolver();
    }

    private EarthFetcher createFetcher(String cloudName) {
        CloudinaryClient cdnClient = new CloudinaryClient(cloudName);
        return new EarthFetcher(context, cdnClient);
    }

    public Map<String, Long> sync(boolean manual) {
        return sync(ContentInterface.from(context.getContentResolver()), manual);
    }

    public Map<String, Long> sync(ContentProviderClient client, boolean manual) {
        return sync(ContentInterface.from(client), manual);
    }

    private Map<String, Long> sync(ContentInterface content, boolean manual) {
        final Map<String, Long> result = new HashMap<>();
        final Settings settings = loadSettings();

        Log.d(TAG, "sync() started, manual=" + manual);

        if (settings == null) {
            result.put(RESULT_ERROR, ERROR_DB);
            result.put(RESULT_ERROR_MESSAGE, 0L);
            Log.e(TAG, "skipped sync since impossible null settings");
            return result;
        }

        final boolean metered = ConnectivityManagerCompat.isActiveNetworkMetered(cm);

        if (!manual && settings.wifiOnly && metered) {
            result.put(RESULT_ERROR, ERROR_SKIPPED_METERED);
            Log.d(TAG, "skipped sync until in non-metered connection (wifiOnly=" + settings.wifiOnly + ", metered=" + metered + ")");
            return result;
        }

        if (manual) {
            settings.interval = TimeUnit.MINUTES.toMillis(10);
        }

        if (NetworkStateUtil.shouldConsiderSavingData(cm)) {
            settings.interval = TimeUnit.MINUTES.toMillis(120);
            settings.resolution = 550;
        }

        if (settings.cdnCloudName == null || settings.cdnCloudName.trim().isEmpty()) {
            result.put(RESULT_ERROR, ERROR_CDN_NOT_CONFIGURED);
            Log.w(TAG, "skipped sync: cloud_name is not configured. Please set it in Settings → Cloudinary CDN.");
            return result;
        }

        File fetched = null;
        fetcher = createFetcher(settings.cdnCloudName);

        try {
            Log.d(TAG, "about to fetch with resolution=" + settings.resolution + ", cloudName=" + settings.cdnCloudName);
            fetched = fetcher.fetch(settings.resolution);
            Log.d(TAG, "fetch returned: " + (fetched != null ? fetched.getAbsolutePath() : "null"));

            final Earth earth = new Earth(fetched.getAbsolutePath());
            content.insert(EarthsContract.CONTENT_URI, earth.toContentValues());

            final int cleaned = content.delete(EarthsContract.OUTDATED_CONTENT_URI);

            result.put(RESULT_INSERTS, 1L);
            result.put(RESULT_DELETES, (long) cleaned);

            final long syncUntil = System.currentTimeMillis() + settings.interval;
            result.put(RESULT_DELAY_UNTIL, TimeUnit.MILLISECONDS.toSeconds(syncUntil));

            EarthAppWidgetProvider.triggerUpdate(context);

            Log.d(TAG, "done fetching earth, with " + cleaned + " outdated cleaned. next sync: "
                    + new Date(syncUntil));
        } catch (InterruptedException e) {
            result.put(RESULT_ERROR, ERROR_IO);
            Thread.currentThread().interrupt();
            Log.w(TAG, "sync interrupted", e);
        } catch (Exception e) {
            String msg = e.getMessage();
            long errorCode;
            if (msg != null && msg.contains("latest timestamp")) {
                errorCode = ERROR_API_FAILED;
            } else if (msg != null && msg.contains("tile")) {
                errorCode = ERROR_TILE_FETCH_FAILED;
            } else {
                errorCode = ERROR_IO;
            }
            result.put(RESULT_ERROR, errorCode);
            Log.e(TAG, "failed fetching earth (code=" + errorCode + "): " + msg, e);
        }

        return result;
    }

    public void cancel() {
        if (fetcher != null) {
            fetcher.clean();
        }
    }

    @Nullable
    private Settings loadSettings() {
        Log.d(TAG, "loadSettings() from process: " + android.os.Process.myPid());
        final Cursor cursor = resolver.query(SettingsContract.CONTENT_URI,
                null, null, null, null);

        if (cursor == null) {
            Log.w(TAG, "loadSettings() cursor is null");
            return null;
        }

        Settings settings = Settings.fromCursor(cursor);
        cursor.close();

        if (settings == null) {
            // DB has no row yet (first launch in :sync process before main process initializes it).
            // Return a safe default so we can still attempt sync (will fail gracefully if cloud_name is missing).
            Log.d(TAG, "loadSettings() no row in DB, using legacy defaults");
            settings = Settings.fromLegacySharedState(context);
        }

        Log.d(TAG, "loadSettings() interval=" + settings.interval
                + ", resolution=" + settings.resolution
                + ", cdnCloudName='" + settings.cdnCloudName + "'");

        return settings;
    }

    @SuppressWarnings({"UnusedReturnValue", "SameParameterValue"})
    static abstract class ContentInterface {

        abstract Cursor query(Uri uri);

        abstract Uri insert(Uri uri, ContentValues values);

        abstract int delete(Uri uri);

        static ContentInterface from(ContentResolver resolver) {
            return new ContentInterface() {
                @Override
                Cursor query(Uri uri) {
                    return resolver.query(uri, null, null, null, null);
                }

                @Override
                Uri insert(Uri uri, ContentValues values) {
                    return resolver.insert(uri, values);
                }

                @Override
                int delete(Uri uri) {
                    return resolver.delete(uri, null, null);
                }
            };
        }

        static ContentInterface from(ContentProviderClient client) {
            return new ContentInterface() {
                @Override
                Cursor query(Uri uri) {
                    try {
                        return client.query(uri, null, null, null, null);
                    } catch (RemoteException e) {
                        return null;
                    }
                }

                @Override
                Uri insert(Uri uri, ContentValues values) {
                    try {
                        return client.insert(uri, values);
                    } catch (RemoteException e) {
                        return null;
                    }
                }

                @Override
                int delete(Uri uri) {
                    try {
                        return client.delete(uri, null, null);
                    } catch (RemoteException e) {
                        return 0;
                    }
                }
            };
        }

    }

}
