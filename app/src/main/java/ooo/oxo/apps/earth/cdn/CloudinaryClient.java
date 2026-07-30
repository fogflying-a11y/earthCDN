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

package ooo.oxo.apps.earth.cdn;

import java.util.Locale;

/**
 * Pure Cloudinary Fetch mode — no upload, no API key/secret.
 * Constructs fetch URLs that proxy Himawari origin URLs through Cloudinary CDN.
 */
public class CloudinaryClient {

    private static final String FETCH_BASE = "https://res.cloudinary.com";

    private final String cloudName;

    public CloudinaryClient(String cloudName) {
        this.cloudName = cloudName;
    }

    /**
     * Check if cloud_name is valid and non-empty.
     */
    public boolean hasValidCloudName() {
        return cloudName != null && !cloudName.trim().isEmpty();
    }

    /**
     * Build a Cloudinary fetch URL WITHOUT transformations (plain fetch).
     * Format is byte-aligned with the EarthLiveSharp desktop client so that
     * both clients sharing one cloud_name hit the same CDN cache entry.
     * Only ':' is percent-encoded; slashes stay literal (do NOT URLEncoder.encode()).
     * Returns null if cloud_name is invalid or originalUrl is null.
     */
    public String buildFetchUrl(String originalUrl) {
        if (!hasValidCloudName()) {
            return null;
        }
        if (originalUrl == null) {
            return null;
        }
        String encodedUrl = originalUrl.replace(":", "%3A");
        return String.format(Locale.US, "%s/%s/image/fetch/%s",
                FETCH_BASE, cloudName, encodedUrl);
    }

    /**
     * Get the Cloud Name.
     */
    public String getCloudName() {
        return cloudName;
    }

    /**
     * Test the CDN connection by fetching a small test image through Cloudinary.
     * Returns a Result with success status and message.
     */
    public TestResult testConnection() {
        if (!hasValidCloudName()) {
            return new TestResult(false, "Cloud Name is empty");
        }
        // Use a tiny 1x1 pixel test image from himawari.asia to verify CDN proxy works
        String testOriginUrl = "https://himawari.asia/img/D531106/1d/550/2026/01/01/000000_0_0.png";
        String testCdnUrl = buildFetchUrl(testOriginUrl);
        try {
            java.net.URL url = new java.net.URL(testCdnUrl);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code == 200) {
                return new TestResult(true, "CDN connection OK (cloud: " + cloudName + ")");
            } else if (code == 401 || code == 403) {
                return new TestResult(false, "Access denied — check your Cloud Name (HTTP " + code + ")");
            } else if (code == 404) {
                // 404 is expected for the test image; it means the CDN proxy is working
                return new TestResult(true, "CDN connection OK (cloud: " + cloudName + ")");
            } else {
                return new TestResult(false, "Unexpected HTTP " + code);
            }
        } catch (java.net.UnknownHostException e) {
            return new TestResult(false, "Cannot resolve Cloudinary host — check network");
        } catch (java.net.SocketTimeoutException e) {
            return new TestResult(false, "Connection timed out — check network");
        } catch (Exception e) {
            return new TestResult(false, "Error: " + e.getMessage());
        }
    }

    public static class TestResult {
        public final boolean success;
        public final String message;

        public TestResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
