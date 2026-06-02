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
     * Build a Cloudinary fetch URL with f_auto,q_auto optimization.
     * Returns null if cloud_name is invalid, caller should fall back to direct download.
     */
    public String buildFetchUrl(String originalUrl) {
        if (!hasValidCloudName()) {
            return null;
        }
        return String.format(Locale.US, "%s/%s/image/fetch/f_auto,q_auto/%s",
                FETCH_BASE, cloudName, originalUrl);
    }
}
