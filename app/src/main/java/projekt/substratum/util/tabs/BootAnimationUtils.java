/*
 * Copyright (c) 2016-2017 Projekt Substratum
 * This file is part of Substratum.
 *
 * Substratum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Substratum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Substratum.  If not, see <http://www.gnu.org/licenses/>.
 */

package projekt.substratum.util.tabs;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.support.design.widget.Lunchbar;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import org.zeroturnaround.zip.FileSource;
import org.zeroturnaround.zip.ZipEntrySource;
import org.zeroturnaround.zip.ZipUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;

import projekt.substratum.R;
import projekt.substratum.common.Systems;
import projekt.substratum.common.commands.FileOperations;
import projekt.substratum.common.tabs.BootAnimationManager;

import static projekt.substratum.common.References.EXTERNAL_STORAGE_CACHE;

public class BootAnimationUtils {

    private static final String TAG = "BootAnimationUtils";
    private static final String DATA_SYSTEM = "/data/system/theme/";
    private static final String SYSTEM_MEDIA = "/system/media/";
    private static final String BACKUP_SCRIPT = "81-subsboot.sh";

    public void execute(final View view,
                        final String arguments,
                        final Context context,
                        final String theme_pid,
                        final Boolean encrypted,
                        final Boolean shutdownAnimation,
                        final Cipher cipher) {
        new BootAnimationHandlerAsync(
                view, context, theme_pid, encrypted, shutdownAnimation, cipher).execute(arguments);
    }

    private static class BootAnimationHandlerAsync extends AsyncTask<String, Integer, String> {

        @SuppressLint("StaticFieldLeak")
        private final Context mContext;
        private ProgressDialog progress;
        private Boolean has_failed;
        @SuppressLint("StaticFieldLeak")
        private final View view;
        private final String theme_pid;
        private final SharedPreferences prefs;
        private final Boolean encrypted;
        private final Cipher cipher;
        private final Boolean shutdownAnimation;

        BootAnimationHandlerAsync(final View view,
                                  final Context context,
                                  final String theme_pid,
                                  final Boolean encrypted,
                                  final Boolean shutdownAnimation,
                                  final Cipher cipher) {
            super();
            this.mContext = context;
            this.view = view;
            this.theme_pid = theme_pid;
            this.prefs = PreferenceManager.getDefaultSharedPreferences(this.mContext);
            this.encrypted = encrypted;
            this.cipher = cipher;
            this.shutdownAnimation = shutdownAnimation;
        }

        @Override
        protected void onPreExecute() {
            this.progress = new ProgressDialog(this.mContext, R.style.AppTheme_DialogAlert);
            this.progress.setMessage(this.mContext.getString(this.shutdownAnimation ?
                    R.string.shutdownanimation_dialog_apply_text :
                    R.string.bootanimation_dialog_apply_text));
            this.progress.setIndeterminate(false);
            this.progress.setCancelable(false);
            this.progress.show();
        }

        @Override
        protected void onPostExecute(final String result) {
            this.progress.dismiss();

            if (!this.has_failed) {
                Lunchbar.make(this.view, this.mContext.getString(this.shutdownAnimation ?
                                R.string.shutdownanimation_dialog_apply_success :
                                R.string.bootanimation_dialog_apply_success),
                        Lunchbar.LENGTH_LONG)
                        .show();
            } else {
                Lunchbar.make(this.view, this.mContext.getString(this.shutdownAnimation ?
                                R.string.shutdownanimation_dialog_apply_failed :
                                R.string.bootanimation_dialog_apply_failed),
                        Lunchbar.LENGTH_LONG)
                        .show();
            }
            if (!Systems.checkThemeInterfacer(this.mContext)) {
                FileOperations.mountROData();
                FileOperations.mountRO();
            }
        }

        @Override
        protected String doInBackground(final String... sUrl) {
            this.has_failed = false;

            // Move the file from assets folder to a new working area
            Log.d(TAG, "Copying over the selected boot animation to working directory...");

            final File cacheDirectory = new File(this.mContext.getCacheDir(), "/BootAnimationCache/");
            if (!cacheDirectory.exists() && cacheDirectory.mkdirs())
                Log.d(TAG, "Bootanimation folder created");

            final File cacheDirectory2 = new File(this.mContext.getCacheDir(),
                    "/BootAnimationCache/AnimationCreator/");
            if (!cacheDirectory2.exists() && cacheDirectory2.mkdirs()) {
                Log.d(TAG, "Bootanimation work folder created");
            } else {
                FileOperations.delete(this.mContext, this.mContext.getCacheDir().getAbsolutePath() +
                        "/BootAnimationCache/AnimationCreator/");
                final boolean created = cacheDirectory2.mkdirs();
                if (created) Log.d(TAG, "Bootanimation folder recreated");
            }

            String bootanimation = sUrl[0];

            final String directory = (this.shutdownAnimation ? "shutdownanimation" : "bootanimation");

            // Now let's take out desc.txt from the theme's assets (bootanimation.zip) and parse it
            if (!this.has_failed) {
                Log.d(TAG, "Analyzing integrity of boot animation descriptor file...");
                if (this.cipher != null) {
                    try {
                        final Context otherContext = this.mContext.createPackageContext(this.theme_pid, 0);
                        final AssetManager themeAssetManager = otherContext.getAssets();
                        FileOperations.copyFileOrDir(
                                themeAssetManager,
                                directory + "/" + bootanimation +
                                        (this.encrypted ? ".zip.enc" : ".zip"),
                                this.mContext.getCacheDir().getAbsolutePath() +
                                        "/BootAnimationCache/AnimationCreator/" +
                                        bootanimation + ".zip",
                                directory + "/" + bootanimation +
                                        (this.encrypted ? ".zip.enc" : ".zip"),
                                this.cipher);
                    } catch (final PackageManager.NameNotFoundException e) {
                        e.printStackTrace();
                    }
                } else {
                    try {
                        final Context otherContext = this.mContext.createPackageContext(this.theme_pid, 0);
                        final AssetManager am = otherContext.getAssets();
                        try (InputStream inputStream = am.open(
                                directory + "/" + bootanimation + ".zip");
                             OutputStream outputStream = new FileOutputStream(
                                     this.mContext.getCacheDir().getAbsolutePath() +
                                             "/BootAnimationCache/AnimationCreator/" +
                                             bootanimation + ".zip")) {
                            this.CopyStream(inputStream, outputStream);
                        }
                    } catch (final Exception e) {
                        this.has_failed = true;
                        Log.e(TAG,
                                "There is no animation.zip found within the assets " +
                                        "of this theme!");

                    }
                }

                // Rename the file
                final File workingDirectory = new File(
                        this.mContext.getCacheDir().getAbsolutePath() +
                                "/BootAnimationCache/AnimationCreator/");
                final File from = new File(workingDirectory, bootanimation + ".zip");
                bootanimation =
                        bootanimation.replaceAll("\\s+", "").replaceAll("[^a-zA-Z0-9]+", "");
                final File to = new File(workingDirectory, bootanimation + ".zip");
                final boolean rename = from.renameTo(to);
                if (rename)
                    Log.d(TAG, "Boot Animation successfully moved to new directory");
            }

            if (!this.has_failed) {
                final boolean exists = ZipUtil.containsEntry(
                        new File(this.mContext.getCacheDir().getAbsolutePath() +
                                "/BootAnimationCache/AnimationCreator/" +
                                bootanimation + ".zip"), "desc.txt");
                if (exists) {
                    ZipUtil.unpackEntry(
                            new File(this.mContext.getCacheDir().getAbsolutePath() +
                                    "/BootAnimationCache/AnimationCreator/" +
                                    bootanimation + ".zip"),
                            "desc.txt",
                            new File(this.mContext.getCacheDir().getAbsolutePath() +
                                    "/BootAnimationCache/AnimationCreator/desc.txt"));
                } else {
                    Log.e(TAG,
                            "Could not find specified boot animation descriptor file (desc.txt)!");
                    this.has_failed = true;
                }
            }

            // Begin parsing of the file (desc.txt) and parse the first line
            if (!this.has_failed) {
                Log.d(TAG, "Calculating hardware display density metrics " +
                        "and resizing the bootanimation...");
                BufferedReader reader = null;
                try (final OutputStream os = new FileOutputStream(
                        this.mContext.getCacheDir().getAbsolutePath() +
                                "/BootAnimationCache/AnimationCreator/scaled-" +
                                bootanimation + ".zip");
                     final ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(os));
                     final ZipInputStream bootAni = new ZipInputStream(new BufferedInputStream(new
                             FileInputStream(
                             this.mContext.getCacheDir().getAbsolutePath() +
                                     "/BootAnimationCache/AnimationCreator/" +
                                     bootanimation + ".zip")))) {
                    ZipEntry ze;

                    zos.setMethod(ZipOutputStream.STORED);
                    final byte[] bytes = new byte[4096];
                    int len;
                    while ((ze = bootAni.getNextEntry()) != null) {
                        final ZipEntry entry = new ZipEntry(ze.getName());
                        entry.setMethod(ZipEntry.STORED);
                        entry.setCrc(ze.getCrc());
                        entry.setSize(ze.getSize());
                        entry.setCompressedSize(ze.getSize());
                        if (!"desc.txt".equals(ze.getName())) {
                            // just copy this entry straight over into the output zip
                            zos.putNextEntry(entry);
                            while ((len = bootAni.read(bytes)) > 0) {
                                zos.write(bytes, 0, len);
                            }
                        } else {
                            String line;
                            reader = new BufferedReader(new InputStreamReader
                                    (bootAni));
                            final String[] info = reader.readLine().split(" ");

                            final int scaledWidth;
                            int scaledHeight;
                            final WindowManager wm = (WindowManager) this.mContext.getSystemService
                                    (Context.WINDOW_SERVICE);
                            final DisplayMetrics dm = new DisplayMetrics();
                            if (wm != null) {
                                wm.getDefaultDisplay().getRealMetrics(dm);
                            }
                            // just in case the device is in landscape orientation we will
                            // swap the values since most (if not all) animations are portrait
                            final int prevent_lint_w = dm.widthPixels;
                            final int prevent_lint_h = dm.heightPixels;
                            if (dm.widthPixels > dm.heightPixels) {
                                scaledWidth = prevent_lint_h;
                                scaledHeight = prevent_lint_w;
                            } else {
                                scaledWidth = dm.widthPixels;
                                scaledHeight = dm.heightPixels;
                            }

                            final int width = Integer.parseInt(info[0]);
                            final int height = Integer.parseInt(info[1]);

                            if (width == height) {
                                scaledHeight = scaledWidth;
                            } else {
                                // adjust scaledHeight to retain original aspect ratio
                                final float scale = (float) scaledWidth / (float) width;
                                final int newHeight = (int) ((float) height * scale);
                                if (newHeight < scaledHeight)
                                    scaledHeight = newHeight;
                            }

                            final CRC32 crc32 = new CRC32();
                            int size = 0;
                            final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                            line = String.format(Locale.US,
                                    "%d %d %s\n", scaledWidth, scaledHeight, info[2]);
                            buffer.put(line.getBytes());
                            size += line.getBytes().length;
                            crc32.update(line.getBytes());
                            while ((line = reader.readLine()) != null) {
                                line = String.format("%s\n", line);
                                buffer.put(line.getBytes());
                                size += line.getBytes().length;
                                crc32.update(line.getBytes());
                            }
                            entry.setCrc(crc32.getValue());
                            entry.setSize(size);
                            entry.setCompressedSize(size);

                            zos.putNextEntry(entry);
                            zos.write(buffer.array(), 0, size);
                        }
                    }
                } catch (final Exception e) {
                    e.printStackTrace();
                    Log.e(TAG, "The boot animation descriptor file (desc.txt) " +
                            "could not be parsed properly!");
                    this.has_failed = true;
                } finally {
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (final IOException e) {
                            // Suppress warning
                        }
                    }
                }
            }

            if (!this.has_failed) {
                Log.d(TAG,
                        "Finalizing the boot animation descriptor file and " +
                                "committing changes to the archive...");

                final ZipEntrySource[] addedEntries = {
                        new FileSource("desc.txt", new File(
                                this.mContext.getCacheDir().getAbsolutePath() +
                                        "/BootAnimationCache/AnimationCreator/desc.txt"))
                };
                ZipUtil.addOrReplaceEntries(new File(
                        this.mContext.getCacheDir().getAbsolutePath() +
                                "/BootAnimationCache/AnimationCreator/" +
                                bootanimation + ".zip"), addedEntries);
            }

            if (!this.has_failed) {
                Log.d(TAG, "Moving boot animation to theme directory " +
                        "and setting correct contextual parameters...");
                final boolean is_encrypted = Systems.getDeviceEncryptionStatus(this.mContext) > 1;
                final File themeDirectory;
                if (Systems.checkOMS(this.mContext)) {
                    if ((!is_encrypted || this.shutdownAnimation) &&
                            Systems.checkSubstratumFeature(this.mContext)) {
                        Log.d(TAG, "Data partition on the current device is decrypted, using " +
                                "dedicated theme bootanimation slot...");
                        themeDirectory = new File(DATA_SYSTEM);
                        if (!themeDirectory.exists()) {
                            if (!Systems.checkThemeInterfacer(this.mContext)) {
                                FileOperations.mountRWData();
                            }
                            FileOperations.createNewFolder(this.mContext, DATA_SYSTEM);
                        }
                    } else {
                        Log.d(TAG, "Data partition on the current device is encrypted, using " +
                                "dedicated encrypted bootanimation slot...");
                        themeDirectory = new File(SYSTEM_MEDIA);
                    }
                } else {
                    Log.d("BootAnimationUtils",
                            "Current device is on substratum legacy, " +
                                    "using system bootanimation slot...");
                    themeDirectory = new File(SYSTEM_MEDIA);
                }

                final File scaledBootAnimCheck = new File(this.mContext.getCacheDir()
                        .getAbsolutePath() + "/BootAnimationCache/AnimationCreator/" +
                        "scaled-" + bootanimation + ".zip");
                if (scaledBootAnimCheck.exists()) {
                    Log.d(TAG, "Scaled boot animation created by Substratum verified!");
                } else {
                    this.has_failed = true;
                    Log.e(TAG, "Scaled boot animation created by Substratum NOT verified!");
                }

                // Move created boot animation to working directory
                FileOperations.move(this.mContext,
                        scaledBootAnimCheck.getAbsolutePath(),
                        EXTERNAL_STORAGE_CACHE +
                                (this.shutdownAnimation ?
                                        "shutdownanimation.zip" : "bootanimation.zip"));

                // Inject backup script for encrypted legacy and encrypted OMS devices
                if (!this.has_failed && (is_encrypted || !Systems.checkOMS(this.mContext)) &&
                        !this.shutdownAnimation) {
                    FileOperations.mountRW();
                    final File backupScript = new File("/system/addon.d/" + BACKUP_SCRIPT);

                    if (Systems.checkSubstratumFeature(this.mContext)) {
                        if (!backupScript.exists()) {
                            final AssetManager assetManager = this.mContext.getAssets();
                            final String backupScriptPath =
                                    this.mContext.getFilesDir().getAbsolutePath() + "/" + BACKUP_SCRIPT;
                            OutputStream out = null;
                            InputStream in = null;
                            try {
                                out = new FileOutputStream(backupScriptPath);
                                in = assetManager.open(BACKUP_SCRIPT);
                                final byte[] buffer = new byte[1024];
                                int read;
                                while ((read = in.read(buffer)) != -1) {
                                    out.write(buffer, 0, read);
                                }
                            } catch (final Exception e) {
                                e.printStackTrace();
                            } finally {
                                if (in != null) {
                                    try {
                                        in.close();
                                    } catch (final IOException e) {
                                        // Suppress warning
                                    }
                                }
                                if (out != null) {
                                    try {
                                        out.close();
                                    } catch (final IOException e) {
                                        // Suppress warning
                                    }
                                }
                            }
                            FileOperations.copy(this.mContext, this.mContext.getFilesDir().getAbsolutePath() +
                                    "/" + BACKUP_SCRIPT, backupScript.getAbsolutePath());
                            FileOperations.setPermissions(755, backupScript.getAbsolutePath());
                        }
                    }

                    final File backupDirectory = new File(themeDirectory.getAbsolutePath() +
                            "/bootanimation-backup.zip");
                    if (!backupDirectory.exists()) {
                        FileOperations.move(this.mContext, themeDirectory.getAbsolutePath()
                                + "/bootanimation.zip", backupDirectory.getAbsolutePath());
                    }

                    final File bootAnimationCheck = new File(themeDirectory.getAbsolutePath() +
                            "/bootanimation.zip");

                    if (backupDirectory.exists()) {
                        if (backupScript.exists()) {
                            Log.d(TAG, "Old bootanimation is backed up, ready to go!");
                        }
                    } else if (!bootAnimationCheck.exists() && !backupDirectory.exists()) {
                        Log.d(TAG, "There is no predefined bootanimation on this device, " +
                                "injecting a brand new default bootanimation...");
                    } else {
                        this.has_failed = true;
                        Log.e(TAG, "Failed to backup bootanimation!");
                    }
                }

                if (!this.has_failed) {
                    BootAnimationManager.setBootAnimation(this.mContext,
                            themeDirectory.getAbsolutePath(), this.shutdownAnimation);
                }
            }

            if (!this.has_failed) {
                final SharedPreferences.Editor editor = this.prefs.edit();
                if (this.shutdownAnimation) {
                    editor.putString("shutdownanimation_applied", this.theme_pid);
                } else {
                    editor.putString("bootanimation_applied", this.theme_pid);
                }
                editor.apply();
                Log.d(TAG, "Boot animation installed!");
                FileOperations.delete(this.mContext, this.mContext.getCacheDir().getAbsolutePath() +
                        "/BootAnimationCache/AnimationCreator/");
            } else {
                Log.e(TAG, "Boot animation installation aborted!");
                FileOperations.delete(this.mContext, this.mContext.getCacheDir().getAbsolutePath() +
                        "/BootAnimationCache/AnimationCreator/");
            }
            return null;
        }

        private void CopyStream(final InputStream Input, final OutputStream Output) throws IOException {
            final byte[] buffer = new byte[5120];
            int length = Input.read(buffer);
            while (length > 0) {
                Output.write(buffer, 0, length);
                length = Input.read(buffer);
            }
        }
    }
}