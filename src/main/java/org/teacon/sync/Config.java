/*
 * Copyright (C) 2021 3TUSK
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

// SPDX-Identifier: LGPL-2.1-or-later

package org.teacon.sync;

import java.net.URL;

public final class Config {

    /**
     * URL that points to the mod list to download. Supported protocols include
     * http, https, files (i.e. local file) and others.
     *
     * @see URL
     * @see URL#getProtocol()
     */
    public URL modList;
    /**
     * Path to the directory where all downloaded mods are held, relative to the
     * Minecraft home directory.
     */
    public String modDir = "synced_mods";
    /**
     * Path to the public keys ring file, relative to the Minecraft home directory.
     */
    public String keyRingPath = "pub_key.asc";
    /**
     * Amount of time to wait before giving up a connection, measured in milliseconds.
     */
    public int timeout = 15000;
}
