/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.userprofile;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.Context;
import android.app.Dialog;
import android.content.DialogInterface;
import android.view.View;
import android.view.ViewGroup;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.entity.ProfileInfo;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.crimera.ObjectBrowser;
import app.morphe.extension.instagram.settings.ActivityHook;
import app.morphe.extension.instagram.settings.preference.fragments.FragmentHook;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.instagram.entity.InstagramDialogBox;
import app.morphe.extension.instagram.entity.InstagramButton;
import app.morphe.extension.instagram.entity.InstagramButtonStyleEnum;
import app.morphe.extension.shared.ui.Dim;

import com.instagram.igds.components.button.IgdsButton;

public class ProfileMoreOption {
    private static boolean DEBUG;
    private static final Set<View> hookedThreeDotsViews = Collections.newSetFromMap(new WeakHashMap<>());

    static {
        DEBUG = Pref.pikoDebug();
    }

    public static void moreOptionsDailogueBox(Context context, UserData userData) {
        try {
            InstagramDialogBox dialog = new InstagramDialogBox(context);

            ArrayList<String> options = new ArrayList<>();
            options.add(str("piko_view_profile_picture"));
            options.add(str("piko_copy_username"));
            options.add(str("piko_copy_full_name"));
            options.add(str("piko_copy_user_id"));
            options.add(str("piko_copy_bio"));
            options.add(str("piko_copy_links_from_bio"));
            if (DEBUG) options.add(str("piko_debug"));

            CharSequence[] items = options.toArray(new CharSequence[0]);

            dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    try {
                        // Doing like this because options are dynamic.
                        String selectedOption = options.get(which);
                        boolean toCopy = false;
                        String text = null;

                        if (selectedOption.equals(str("piko_copy_username"))) {
                            text = userData.getUsername();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_full_name"))) {
                            text = userData.getFullName();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_user_id"))) {
                            text = userData.getUserId();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_bio"))) {
                            text = userData.getBio();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_links_from_bio"))) {
                            // Links live in the bio as plain text; the profile object
                            // carries no separate list of them.
                            String links = linksFrom(userData.getBio());
                            if (links == null) {
                                Utils.showToastShort(str("piko_no_links_in_bio"));
                            } else {
                                text = links;
                                toCopy = true;
                            }

                        } else if (selectedOption.equals(str("piko_view_profile_picture"))) {
                            ProfilePictureViewer.show(context, userData);

                        } else if (selectedOption.equals(str("piko_debug"))) {
                            // Using userData as it will be easier to debug known functions.
                            ObjectBrowser.browseObject(context, userData);

                        }

                        if (toCopy && text != null && text.length() > 0) {
                            Utils.setClipboard(text);
                            Utils.showToastShort(str("piko_copied"));
                        }
                    } catch (Exception e) {
                        Logger.printException(() -> "Error at moreOptionsDailogueBox onclick", e);
                        Utils.showToastShort(e.getMessage());
                    }
                }
            });
            dialog.setTitle(str("piko_more_profile_options"));
            dialog.setNegativeButton(str("piko_close"), (d, which) -> d.dismiss());
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);

            Dialog dlg = dialog.getDialog();
            dlg.show();
        } catch (Exception e) {
            Logger.printException(() -> "Error at moreOptionsDailogueBox", e);
            Utils.showToastShort(e.getMessage());
        }
    }

    /**
     * Every link written in a bio, one per line. Instagram stores the bio as plain
     * text, so a URL there is only ever a URL by how it reads — matched loosely on
     * purpose, since most bios write "example.com" without a scheme.
     */
    private static String linksFrom(String bio) {
        if (bio == null || bio.isEmpty()) return null;
        Matcher matcher = BIO_LINK.matcher(bio);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String link = matcher.group();
            if (out.indexOf(link) >= 0) continue; // the same link twice reads as noise
            if (out.length() > 0) out.append('\n');
            out.append(link);
        }
        return out.length() == 0 ? null : out.toString();
    }

    private static final Pattern BIO_LINK = Pattern.compile(
            "(?:https?://)?(?:[\\w-]+\\.)+[a-z]{2,}(?:/[^\\s]*)?",
            Pattern.CASE_INSENSITIVE);

    public static void showThreeDotsProfileMenu(
            Context context,
            UserData userData,
            View.OnClickListener originalListener,
            View view
    ) {
        try {
            InstagramDialogBox dialog = new InstagramDialogBox(context);

            ArrayList<String> options = new ArrayList<>();
            options.add(str("piko_view_profile_picture"));
            options.add(str("piko_copy_username"));
            options.add(str("piko_copy_full_name"));
            options.add(str("piko_copy_user_id"));
            options.add(str("piko_copy_bio"));
            options.add(str("piko_copy_links_from_bio"));
            if (originalListener != null) {
                options.add(str("piko_stock_options"));
            }
            if (DEBUG) options.add(str("piko_debug"));

            CharSequence[] items = options.toArray(new CharSequence[0]);

            dialog.addDialogMenuItems(items, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int which) {
                    try {
                        String selectedOption = options.get(which);
                        if (selectedOption.equals(str("piko_stock_options"))) {
                            if (originalListener != null) {
                                originalListener.onClick(view);
                            }
                            return;
                        }

                        boolean toCopy = false;
                        String text = null;

                        if (selectedOption.equals(str("piko_copy_username"))) {
                            text = userData.getUsername();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_full_name"))) {
                            text = userData.getFullName();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_user_id"))) {
                            text = userData.getUserId();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_bio"))) {
                            text = userData.getBio();
                            toCopy = true;

                        } else if (selectedOption.equals(str("piko_copy_links_from_bio"))) {
                            String links = linksFrom(userData.getBio());
                            if (links == null) {
                                Utils.showToastShort(str("piko_no_links_in_bio"));
                            } else {
                                text = links;
                                toCopy = true;
                            }

                        } else if (selectedOption.equals(str("piko_view_profile_picture"))) {
                            ProfilePictureViewer.show(context, userData);

                        } else if (selectedOption.equals(str("piko_debug"))) {
                            ObjectBrowser.browseObject(context, userData);

                        }

                        if (toCopy && text != null && text.length() > 0) {
                            Utils.setClipboard(text);
                            Utils.showToastShort(str("piko_copied"));
                        }
                    } catch (Exception e) {
                        Logger.printException(() -> "Error at showThreeDotsProfileMenu onclick", e);
                        Utils.showToastShort(e.getMessage());
                    }
                }
            });
            dialog.setTitle(str("piko_more_profile_options"));
            dialog.setNegativeButton(str("piko_close"), (d, which) -> d.dismiss());
            dialog.setCancelable(true);
            dialog.setCanceledOnTouchOutside(true);

            Dialog dlg = dialog.getDialog();
            dlg.show();
        } catch (Exception e) {
            Logger.printException(() -> "Error at showThreeDotsProfileMenu", e);
            Utils.showToastShort(e.getMessage());
        }
    }

    public static void hookProfileThreeDots(Activity activity, ViewGroup viewGroup, UserData userData, boolean isSelfProfile) {
        if (activity == null || viewGroup == null || userData == null) return;

        viewGroup.post(() -> {
            try {
                int childCount = viewGroup.getChildCount();
                if (childCount == 0) return;

                View threeDotsView = null;
                for (int i = childCount - 1; i >= 0; i--) {
                    View child = viewGroup.getChildAt(i);
                    if (child == null || "piko_settings_gear".equals(child.getTag()) || "piko_ghost_mode_toggle".equals(child.getTag())) {
                        continue;
                    }
                    CharSequence desc = child.getContentDescription();
                    if (desc != null) {
                        String descStr = desc.toString().toLowerCase();
                        if (descStr.contains("option") || descStr.contains("more") || descStr.contains("menu")) {
                            threeDotsView = child;
                            break;
                        }
                    }
                }

                if (threeDotsView == null) {
                    for (int i = childCount - 1; i >= 0; i--) {
                        View child = viewGroup.getChildAt(i);
                        if (child != null && !"piko_settings_gear".equals(child.getTag()) && !"piko_ghost_mode_toggle".equals(child.getTag())) {
                            threeDotsView = child;
                            break;
                        }
                    }
                }

                if (threeDotsView == null || hookedThreeDotsViews.contains(threeDotsView)) {
                    return;
                }

                hookedThreeDotsViews.add(threeDotsView);
                final View target = threeDotsView;
                final View.OnClickListener originalListener = getOnClickListener(target);

                if (isSelfProfile) {
                    // Own profile: keep stock Instagram settings on tap, open Piko settings on long press
                    target.setOnLongClickListener(v -> {
                        try {
                            FragmentHook.startSettings();
                        } catch (Exception e) {
                            Logger.printException(() -> "Error launching Piko settings from profile header: ", e);
                        }
                        return true;
                    });
                } else {
                    // Other user profile: tap opens more profile options dialog, long press opens direct options
                    target.setOnClickListener(v -> showThreeDotsProfileMenu(activity, userData, originalListener, v));
                    target.setOnLongClickListener(v -> {
                        moreOptionsDailogueBox(activity, userData);
                        return true;
                    });
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Error in hookProfileThreeDots: ", ex);
            }
        });
    }

    private static View.OnClickListener getOnClickListener(View view) {
        try {
            Field listenerInfoField = View.class.getDeclaredField("mListenerInfo");
            listenerInfoField.setAccessible(true);
            Object listenerInfo = listenerInfoField.get(view);
            if (listenerInfo != null) {
                Field clickListenerField = listenerInfo.getClass().getDeclaredField("mOnClickListener");
                clickListenerField.setAccessible(true);
                return (View.OnClickListener) clickListenerField.get(listenerInfo);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static void addProfileMoreOptionsButton(ViewGroup viewGroup, ProfileInfo profileInfo) {
        // Stock bio buttons: disabled injecting buttons below the profile bio.
    }
}
