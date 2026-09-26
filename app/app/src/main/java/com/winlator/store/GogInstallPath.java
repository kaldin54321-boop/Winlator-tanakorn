package com.winlator.store;

import android.content.Context;

import java.io.File;

/** Static helper that resolves install paths for GOG games. */
public final class GogInstallPath {

    private GogInstallPath() {}

    /**
     * Returns the install directory for a game.
     * Path: {rootfs}/gog_games/{dirName} (Wine-visible as Z:\gog_games\...).
     */
    public static File getInstallDir(Context ctx, String dirName) {
        return new File(StorePaths.storeDir(ctx, StorePaths.GOG_DIR), dirName);
    }

    /**
     * Converts an absolute Android path to a Wine Z: path.
     * This project maps Z: to the RootFS root, so anything under it keeps its
     * relative form.
     *
     * e.g. .../rootfs/gog_games/Game/game.exe → Z:\gog_games\Game\game.exe
     */
    public static String toWinePath(Context ctx, String absExePath) {
        return StorePaths.toWinePath(ctx, absExePath);
    }
}
