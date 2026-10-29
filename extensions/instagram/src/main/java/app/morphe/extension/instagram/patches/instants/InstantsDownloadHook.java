/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import app.morphe.extension.crimera.sharedPreference.SharedPref;
import app.morphe.extension.instagram.db.PikoInstantsDb;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.patches.download.DownloadUtils;
import app.morphe.extension.instagram.settings.Settings;
import app.morphe.extension.instagram.settings.preference.widgets.InstagramPreferenceStyle;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Records each view-once Instant as the app builds it, attaches direct download near 3 dots,
 * and allows re-viewing from {@link InstantsVaultActivity}.
 */
@SuppressWarnings("unused")
public class InstantsDownloadHook {

    /** The item constructor runs again whenever the app rebuilds an item. */
    private static volatile String sLastCapturedId;

    public static volatile String sCurrentInstantId;
    public static volatile String sCurrentInstantUrl;
    public static volatile boolean sCurrentInstantIsVideo;
    public static volatile String sCurrentInstantUsername;

    /** Called from the per-instant item's constructor with that instant's Media. */
    public static void noteInstantMedia(Object media) {
        try {
            if (media == null) return;
            if (!SharedPref.getBooleanPref(Settings.INSTANTS_DOWNLOAD)) return;

            captureMedia(media);
        } catch (Throwable t) {
            Logger.printException(() -> "instants capture: noteInstantMedia failed", t);
        }
    }

    public static void downloadActiveInstant(Context context) {
        try {
            if (context == null) context = Utils.getContext();
            String url = sCurrentInstantUrl;
            if (url == null || !url.startsWith("http")) {
                Utils.showToastShort(str("piko_instants_link_unavailable"));
                return;
            }
            String fileName = "alan_instant_" + (sCurrentInstantId != null ? sCurrentInstantId : System.currentTimeMillis()) 
                    + (sCurrentInstantIsVideo ? ".mp4" : ".jpg");
            DownloadUtils.downloadMediaUrl(context, url, "Instants", fileName);
            Utils.showToastShort(str("piko_downloading_media") + " Instant");
        } catch (Throwable t) {
            Logger.printException(() -> "downloadActiveInstant failed", t);
        }
    }

    public static void showInstantOptions(Context context) {
        try {
            if (context == null) context = Utils.getActivity();
            if (context == null) context = Utils.getContext();
            String url = sCurrentInstantUrl;
            if (url == null || !url.startsWith("http")) {
                Utils.showToastShort(str("piko_instants_link_unavailable"));
                return;
            }
            final Context dialogCtx = context;
            CharSequence[] options = new CharSequence[] {
                    str("piko_download_current_media"),
                    sCurrentInstantIsVideo ? str("piko_open_video_externally") : str("piko_open_image_externally"),
                    str("piko_copy_media_link"),
                    str("piko_view_saved_instants")
            };
            new AlertDialog.Builder(InstagramPreferenceStyle.dialogContext(context))
                    .setTitle(str("piko_download_options"))
                    .setItems(options, (d, which) -> {
                        try {
                            if (which == 0) {
                                downloadActiveInstant(dialogCtx);
                            } else if (which == 1) {
                                dialogCtx.startActivity(Intent.createChooser(
                                        new Intent(Intent.ACTION_VIEW, Uri.parse(url)), null));
                            } else if (which == 2) {
                                Utils.setClipboard(url);
                                Utils.showToastShort(str("piko_copied_media_link"));
                            } else if (which == 3) {
                                Intent intent = new Intent(dialogCtx, InstantsVaultActivity.class);
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                dialogCtx.startActivity(intent);
                            }
                        } catch (Throwable t) {
                            Logger.printException(() -> "showInstantOptions action failed", t);
                        }
                    })
                    .show();
        } catch (Throwable t) {
            Logger.printException(() -> "showInstantOptions dialog failed", t);
        }
    }

    private static void captureMedia(Object media) {
        try {
            MediaData md = new MediaData(media);

            String id;
            try {
                id = md.getMediaPkId();
            } catch (Throwable t) {
                return; // without a stable id it can't be deduped or referenced later
            }

            boolean isVideo = safeBool(() -> md.isVideo());
            String imageUrl = safeStr(() -> md.getImageLink());
            String videoUrl = isVideo ? safeStr(() -> md.getVideoLink()) : null;
            String username = safeStr(() -> md.getUserData().getUsername());
            String userId = safeStr(() -> md.getUserData().getUserId());

            sCurrentInstantId = id;
            sCurrentInstantIsVideo = isVideo;
            sCurrentInstantUsername = username;
            sCurrentInstantUrl = isVideo && videoUrl != null && !videoUrl.isEmpty() ? videoUrl : imageUrl;

            // Attach download button near the 3 dots in the viewer
            InstantDownloadView.attach();

            if (id == null || id.isEmpty() || id.equals(sLastCapturedId)) return;
            sLastCapturedId = id;

            Context ctx = Utils.getContext();
            if (ctx == null) return;
            PikoInstantsDb db = PikoInstantsDb.getInstance(ctx);
            if (db.has(id)) return;

            db.insertOrIgnore(id, username, userId, imageUrl, videoUrl, isVideo);
        } catch (Throwable t) {
            Logger.printException(() -> "instants capture: captureMedia failed", t);
        }
    }

    // ---- best-effort helpers ----

    private interface StrCall { String get() throws Exception; }
    private interface BoolCall { boolean get() throws Exception; }

    private static String safeStr(StrCall c) {
        try { return c.get(); } catch (Throwable t) { return null; }
    }

    private static boolean safeBool(BoolCall c) {
        try { return c.get(); } catch (Throwable t) { return false; }
    }
}
