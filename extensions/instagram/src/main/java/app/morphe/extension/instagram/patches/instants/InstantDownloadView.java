/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.instants;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.Utils;

public final class InstantDownloadView {
    private static final String BUTTON_TAG = "alan_instant_download_button";

    private static final String[] EXISTING_DOWNLOAD_IDS = {
            "direct_visual_message_viewer_download_button",
            "quick_snap_download_button"
    };

    private static final String[] ANCHOR_IDS = {
            "direct_visual_message_viewer_action_button",
            "direct_visual_message_viewer_action_button_container",
            "quick_snap_multi_select_more_button",
            "quick_snap_delete_button",
            "toolbar_menu_button",
            "more_button",
            "more_button_click_area",
            "viewer_menu_button",
            "options_button",
            "action_button",
            "menu_button"
    };

    private static WeakReference<ViewGroup> sAttachedParent = new WeakReference<>(null);

    public static void attach() {
        Utils.runOnMainThread(() -> {
            try {
                Activity activity = Utils.getActivity();
                if (activity == null || activity.isFinishing()) return;

                View decor = activity.getWindow().getDecorView();
                if (findAndInject(decor)) return;

                ViewTreeObserver vto = decor.getViewTreeObserver();
                if (vto.isAlive()) {
                    ViewTreeObserver.OnGlobalLayoutListener listener = new ViewTreeObserver.OnGlobalLayoutListener() {
                        int attempts = 0;
                        @Override
                        public void onGlobalLayout() {
                            attempts++;
                            try {
                                if (findAndInject(decor) || attempts > 15) {
                                    if (vto.isAlive()) {
                                        vto.removeOnGlobalLayoutListener(this);
                                    }
                                }
                            } catch (Throwable t) {
                                if (vto.isAlive()) {
                                    vto.removeOnGlobalLayoutListener(this);
                                }
                            }
                        }
                    };
                    vto.addOnGlobalLayoutListener(listener);
                }
            } catch (Throwable t) {
                Logger.printException(() -> "InstantDownloadView attach failed", t);
            }
        });
    }

    private static boolean findAndInject(View root) {
        if (root == null) return false;

        // Strategy 1: Check if Instagram already has a hidden download button in layout
        for (String dlIdName : EXISTING_DOWNLOAD_IDS) {
            int dlId = ResourceUtils.getIdentifier(ResourceType.ID, dlIdName);
            if (dlId != 0) {
                View existing = root.findViewById(dlId);
                if (existing != null) {
                    existing.setVisibility(View.VISIBLE);
                    existing.setClickable(true);
                    existing.setFocusable(true);
                    if (existing instanceof ImageView) {
                        UI.setThemedIcon((ImageView) existing, UI.DRAWABLE_DOWNLOAD_ICON);
                        ((ImageView) existing).setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);
                    }
                    existing.setOnClickListener(v -> InstantsDownloadHook.downloadActiveInstant(v.getContext()));
                    existing.setOnLongClickListener(v -> {
                        InstantsDownloadHook.showInstantOptions(v.getContext());
                        return true;
                    });
                    existing.setOnTouchListener((v, event) -> {
                        ViewParent p = v.getParent();
                        if (p != null) p.requestDisallowInterceptTouchEvent(true);
                        return false;
                    });
                    return true;
                }
            }
        }

        // Strategy 2: Find anchor near 3-dots and inject our download button
        View anchor = findAnchor(root);
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) return false;

        ViewGroup parent = (ViewGroup) anchor.getParent();
        injectButton(parent, anchor);
        return true;
    }

    private static View findAnchor(View root) {
        for (String idName : ANCHOR_IDS) {
            int id = ResourceUtils.getIdentifier(ResourceType.ID, idName);
            if (id != 0) {
                View candidate = root.findViewById(id);
                if (candidate != null && candidate.isShown()) {
                    return candidate;
                }
            }
        }

        List<View> matches = new ArrayList<>();
        findViewsByDesc(root, matches);
        if (!matches.isEmpty()) {
            return matches.get(0);
        }
        return null;
    }

    private static void findViewsByDesc(View view, List<View> out) {
        if (view == null || !view.isShown()) return;
        CharSequence desc = view.getContentDescription();
        if (desc != null) {
            String s = desc.toString().toLowerCase();
            if (s.contains("more") || s.contains("option") || s.contains("menu")) {
                out.add(view);
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                findViewsByDesc(vg.getChildAt(i), out);
            }
        }
    }

    private static void injectButton(ViewGroup parent, View anchor) {
        try {
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (BUTTON_TAG.equals(child.getTag())) {
                    child.setVisibility(View.VISIBLE);
                    return;
                }
            }

            Context context = parent.getContext();
            ImageView button = new ImageView(context);
            button.setTag(BUTTON_TAG);
            button.setScaleType(ImageView.ScaleType.CENTER);
            button.setClickable(true);
            button.setFocusable(true);

            int padLeft = anchor.getPaddingLeft();
            int padTop = anchor.getPaddingTop();
            int padRight = anchor.getPaddingRight();
            int padBottom = anchor.getPaddingBottom();
            if (padLeft == 0 && padRight == 0) {
                padLeft = padTop = padRight = padBottom = 16;
            }
            button.setPadding(padLeft, padTop, padRight, padBottom);

            UI.setThemedIcon(button, UI.DRAWABLE_DOWNLOAD_ICON);
            button.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_ATOP);
            button.setContentDescription(str("piko_download_current_media"));

            int anchorIndex = parent.indexOfChild(anchor);
            ViewGroup.LayoutParams lp = copyLayoutParams(anchor.getLayoutParams());
            parent.addView(button, Math.max(0, anchorIndex), lp);

            button.setOnTouchListener((v, event) -> {
                ViewParent p = v.getParent();
                if (p != null) p.requestDisallowInterceptTouchEvent(true);
                return false;
            });

            button.setOnClickListener(v -> InstantsDownloadHook.downloadActiveInstant(v.getContext()));
            button.setOnLongClickListener(v -> {
                InstantsDownloadHook.showInstantOptions(v.getContext());
                return true;
            });
            sAttachedParent = new WeakReference<>(parent);
        } catch (Throwable t) {
            Logger.printException(() -> "injectButton failed", t);
        }
    }

    private static ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams source) {
        if (source instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) source);
        }
        if (source != null) {
            return new ViewGroup.LayoutParams(source);
        }
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
