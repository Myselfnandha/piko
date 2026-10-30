/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.story;

import static app.morphe.extension.instagram.utils.IgStr.str;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.crimera.PikoUtils;
import app.morphe.extension.instagram.constants.UI;
import app.morphe.extension.instagram.entity.MediaData;
import app.morphe.extension.instagram.entity.UserData;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

public final class StoryMentionsView {
    private static final String HEADER_CONTAINER_ID = "reel_viewer_text_container";
    private static final String TITLE_ID = "reel_viewer_title";
    private static final String PILL_TAG = "piko_story_mention_pill";
    private static final int MAX_DEPTH = 8;
    private static final long[] RETRY_DELAYS = {0, 150, 350, 700};

    private static final Map<View, Object> sLatestHeaderItem = new WeakHashMap<>();
    private static final Map<String, Bitmap> sAvatarCache = new WeakHashMap<>();

    private static volatile int sHeaderId = -1;
    private static volatile int sTitleId = -1;

    public static final class Pill extends TextView {
        public List<UserData> mentions = Collections.emptyList();

        public Pill(Context context) {
            super(context);
        }
    }

    public static void onStoryHeaderBound(Object storyItem) {
        updateStoryMentions(null, storyItem);
    }

    public static void updateStoryMentions(View root, Object storyItem) {
        try {
            if (storyItem == null) return;
            if (!Pref.viewStoryMentions()) return;

            Activity activity = Utils.getActivity();
            View targetRoot = root;
            if (targetRoot == null && activity != null) {
                targetRoot = activity.getWindow().getDecorView();
            }
            if (targetRoot == null) return;

            final View finalRoot = targetRoot;
            for (long delay : RETRY_DELAYS) {
                if (delay == 0) {
                    finalRoot.post(() -> applyMentions(finalRoot, storyItem));
                } else {
                    finalRoot.postDelayed(() -> applyMentions(finalRoot, storyItem), delay);
                }
            }
        } catch (Throwable t) {
            Logger.printException(() -> "updateStoryMentions failed", t);
        }
    }

    private static void applyMentions(View root, Object storyItem) {
        try {
            if (root == null || storyItem == null) return;

            List<LinearLayout> headers = findVisibleHeaders(root);
            if (headers.isEmpty()) return;

            HashSet<UserData> mentionSet = extractMentions(storyItem);
            List<UserData> mentionsList = mentionSet != null && !mentionSet.isEmpty()
                    ? new ArrayList<>(mentionSet)
                    : Collections.emptyList();

            for (LinearLayout header : headers) {
                sLatestHeaderItem.put(header, storyItem);
                Pill pill = findPillIn(header);

                if (mentionsList.isEmpty()) {
                    if (pill != null) {
                        pill.setVisibility(View.GONE);
                    }
                    continue;
                }

                Context context = header.getContext();
                if (pill == null) {
                    LinearLayout row = findTitleRow(header);
                    LinearLayout parentLayout = row != null ? row : header;
                    boolean isRow = parentLayout.getOrientation() == LinearLayout.HORIZONTAL;

                    pill = createPill(context, isRow);
                    parentLayout.addView(pill);
                }

                pill.mentions = mentionsList;
                int count = mentionsList.size();

                SpannableStringBuilder ssb = new SpannableStringBuilder();
                ssb.append("@");
                ssb.setSpan(new ForegroundColorSpan(Color.WHITE), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int numStart = ssb.length();
                ssb.append(String.valueOf(count));
                ssb.setSpan(new ForegroundColorSpan(Color.parseColor("#FF3B30")), numStart, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ssb.setSpan(new StyleSpan(Typeface.BOLD), 0, ssb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                pill.setText(ssb);
                pill.setContentDescription("@" + count + ", see who this story mentions");
                pill.setVisibility(View.VISIBLE);
            }
        } catch (Throwable t) {
            Logger.printException(() -> "applyMentions failed", t);
        }
    }

    private static Pill createPill(Context context, boolean isRow) {
        Pill pill = new Pill(context);
        pill.setTag(PILL_TAG);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setGravity(Gravity.CENTER_VERTICAL);

        int padH = dp(context, 7);
        int padV = dp(context, 2);
        pill.setPadding(padH, padV, padH, padV);

        GradientDrawable backing = new GradientDrawable();
        backing.setColor(0x73000000);
        backing.setCornerRadius(dp(context, 10));
        pill.setBackground(backing);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (isRow) {
            lp.setMarginStart(dp(context, 6));
            lp.gravity = Gravity.CENTER_VERTICAL;
        } else {
            lp.topMargin = dp(context, 4);
            lp.gravity = Gravity.START;
        }
        pill.setLayoutParams(lp);

        pill.setClickable(true);
        pill.setFocusable(true);

        pill.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ViewParent parent = v.getParent();
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    downX = event.getX();
                    downY = event.getY();
                    moved = false;
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    return true;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (Math.hypot(event.getX() - downX, event.getY() - downY) > 20) {
                        moved = true;
                    }
                    return true;
                } else if (action == MotionEvent.ACTION_UP) {
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                    if (!moved) {
                        v.performClick();
                    }
                    return true;
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                    return true;
                }
                return true;
            }
        });

        pill.setOnClickListener(v -> {
            try {
                if (v instanceof Pill) {
                    showMentionsDialog(v.getContext(), ((Pill) v).mentions);
                }
            } catch (Throwable t) {
                Logger.printException(() -> "Pill click failed", t);
            }
        });

        return pill;
    }

    public static void showMentionsDialog(Context context, List<UserData> people) {
        if (context == null || people == null || people.isEmpty()) return;

        Activity activity = Utils.getActivity();
        if (activity == null && context instanceof Activity) {
            activity = (Activity) context;
        }
        final Context dialogCtx = activity != null ? activity : context;

        AlertDialog.Builder builder = new AlertDialog.Builder(dialogCtx)
                .setTitle(str("piko_vsm_title"))
                .setNegativeButton(str("piko_close"), (d, which) -> d.dismiss());

        Context themed = builder.getContext();
        AlertDialog dialog = builder.create();

        LinearLayout container = new LinearLayout(themed);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, dp(themed, 8), 0, dp(themed, 8));

        for (UserData user : people) {
            final String username = safeUsername(user);
            container.addView(createMentionRow(themed, user, () -> {
                dialog.dismiss();
                PikoUtils.openUrl("instagram://user?username=" + username, true);
            }));
        }

        ScrollView scroll = new ScrollView(themed);
        scroll.addView(container);
        dialog.setView(scroll);

        dialog.setOnDismissListener(d -> resumeStoryPlayback(dialogCtx));
        dialog.setOnCancelListener(d -> resumeStoryPlayback(dialogCtx));

        dialog.show();
    }

    public static void resumeStoryPlayback(Context context) {
        try {
            Activity act = Utils.getActivity();
            if (act == null && context instanceof Activity) {
                act = (Activity) context;
            }
            if (act == null) return;

            final Activity finalAct = act;
            View decor = finalAct.getWindow() != null ? finalAct.getWindow().getDecorView() : null;
            if (decor == null) return;

            decor.postDelayed(() -> {
                try {
                    long now = SystemClock.uptimeMillis();
                    MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
                    decor.dispatchTouchEvent(cancel);
                    cancel.recycle();

                    MotionEvent up = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, 0f, 0f, 0);
                    decor.dispatchTouchEvent(up);
                    up.recycle();
                } catch (Throwable t) {
                    Logger.printException(() -> "resumeStoryPlayback dispatch failed", t);
                }
            }, 100);
        } catch (Throwable t) {
            Logger.printException(() -> "resumeStoryPlayback failed", t);
        }
    }

    private static String safeUsername(UserData user) {
        try {
            return user != null ? user.getUsername() : "unknown";
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String safeFullName(UserData user) {
        try {
            return user != null ? user.getFullName() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static View createMentionRow(Context themed, UserData person, Runnable onClick) {
        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(themed, 56));
        row.setPadding(dp(themed, 16), dp(themed, 8), dp(themed, 16), dp(themed, 8));

        GradientDrawable surface = new GradientDrawable();
        surface.setColor(0x1A888888);
        surface.setCornerRadius(dp(themed, 10));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), surface, null));

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(dp(themed, 12), dp(themed, 3), dp(themed, 12), dp(themed, 3));
        row.setLayoutParams(rowLp);
        row.setClickable(true);
        row.setFocusable(true);

        ImageView avatar = new ImageView(themed);
        int avatarSize = dp(themed, 40);
        LinearLayout.LayoutParams avatarLp = new LinearLayout.LayoutParams(avatarSize, avatarSize);
        avatarLp.setMarginEnd(dp(themed, 14));
        avatar.setLayoutParams(avatarLp);
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);

        GradientDrawable avatarBg = new GradientDrawable();
        avatarBg.setShape(GradientDrawable.OVAL);
        avatarBg.setColor(0x33888888);
        avatar.setImageDrawable(avatarBg);
        avatar.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatar.setClipToOutline(true);
        row.addView(avatar);

        loadAvatar(avatar, person);

        LinearLayout textLayout = new LinearLayout(themed);
        textLayout.setOrientation(LinearLayout.VERTICAL);
        textLayout.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        String fullName = safeFullName(person);
        if (fullName != null && !fullName.trim().isEmpty()) {
            TextView nameView = new TextView(themed);
            nameView.setText(fullName);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            nameView.setTextColor(Color.WHITE);
            nameView.setTypeface(Typeface.DEFAULT_BOLD);
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            textLayout.addView(nameView);
        }

        TextView usernameView = new TextView(themed);
        usernameView.setText("@" + safeUsername(person));
        usernameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        usernameView.setTextColor(0xFFB0B0B0);
        usernameView.setSingleLine(true);
        usernameView.setEllipsize(TextUtils.TruncateAt.END);
        textLayout.addView(usernameView);

        row.addView(textLayout);
        row.setOnClickListener(v -> onClick.run());

        return row;
    }

    private static void loadAvatar(ImageView view, UserData user) {
        Utils.runOnBackgroundThread(() -> {
            try {
                String picUrl = null;
                try {
                    picUrl = user.getProfilePictureUrl();
                } catch (Throwable ignored) {}

                if (picUrl == null || picUrl.isEmpty()) return;

                Bitmap cached = sAvatarCache.get(picUrl);
                if (cached != null) {
                    Utils.runOnMainThread(() -> view.setImageBitmap(cached));
                    return;
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(picUrl).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                conn.connect();
                try (InputStream in = conn.getInputStream()) {
                    Bitmap bmp = BitmapFactory.decodeStream(in);
                    if (bmp != null) {
                        sAvatarCache.put(picUrl, bmp);
                        Utils.runOnMainThread(() -> view.setImageBitmap(bmp));
                    }
                }
            } catch (Throwable ignored) {}
        });
    }

    private static List<LinearLayout> findVisibleHeaders(View root) {
        List<LinearLayout> headers = new ArrayList<>();
        int id = getHeaderId();
        if (id == 0 || root == null) return headers;

        collectVisibleHeaders(root, id, headers);
        if (headers.isEmpty()) {
            Activity act = Utils.getActivity();
            if (act != null) {
                View decor = act.getWindow().getDecorView();
                if (decor != null && decor != root) {
                    collectVisibleHeaders(decor, id, headers);
                }
            }
        }
        if (headers.isEmpty()) {
            LinearLayout fallback = findAnyHeader(root);
            if (fallback != null) {
                headers.add(fallback);
            }
        }
        return headers;
    }

    private static void collectVisibleHeaders(View view, int id, List<LinearLayout> out) {
        if (view == null) return;
        if (view.getId() == id && view instanceof LinearLayout) {
            if (view.isShown() || view.getVisibility() == View.VISIBLE) {
                out.add((LinearLayout) view);
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectVisibleHeaders(vg.getChildAt(i), id, out);
            }
        }
    }

    private static LinearLayout findAnyHeader(View root) {
        int id = getHeaderId();
        if (id == 0 || root == null) return null;

        View at = root;
        for (int depth = 0; depth < MAX_DEPTH && at != null; depth++) {
            View candidate = at.findViewById(id);
            if (candidate instanceof LinearLayout) {
                return (LinearLayout) candidate;
            }
            ViewParent parent = at.getParent();
            at = parent instanceof View ? (View) parent : null;
        }

        Activity act = Utils.getActivity();
        if (act != null) {
            View decor = act.getWindow().getDecorView();
            View candidate = decor.findViewById(id);
            if (candidate instanceof LinearLayout) {
                return (LinearLayout) candidate;
            }
        }
        return null;
    }

    private static LinearLayout findTitleRow(ViewGroup header) {
        int id = getTitleId();
        if (id != 0) {
            View title = header.findViewById(id);
            if (title != null && title.getParent() instanceof LinearLayout) {
                LinearLayout parent = (LinearLayout) title.getParent();
                if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
                    return parent;
                }
            }
        }
        if (header instanceof LinearLayout && ((LinearLayout) header).getOrientation() == LinearLayout.HORIZONTAL) {
            return (LinearLayout) header;
        }
        return null;
    }

    private static Pill findPillIn(ViewGroup header) {
        LinearLayout row = findTitleRow(header);
        if (row != null) {
            Pill inRow = findPillChild(row);
            if (inRow != null) return inRow;
        }
        return findPillChild(header);
    }

    private static Pill findPillChild(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof Pill || PILL_TAG.equals(child.getTag())) {
                return (Pill) child;
            }
        }
        return null;
    }

    private static HashSet<UserData> extractMentions(Object item) {
        try {
            Object media = extractMedia(item);
            if (media == null) return null;
            return new MediaData(media).getMentionSet();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object extractMedia(Object item) {
        if (item == null) return null;
        if (item.getClass().getName().contains("Media")) return item;

        Class<?> clazz = item.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType().getName().contains("Media") && !Modifier.isStatic(f.getModifiers())) {
                        f.setAccessible(true);
                        Object val = f.get(item);
                        if (val != null) return val;
                    }
                }
            } catch (Throwable ignored) {}
            clazz = clazz.getSuperclass();
        }

        try {
            Method m = item.getClass().getMethod("getMedia");
            return m.invoke(item);
        } catch (Throwable ignored) {}

        return item;
    }

    private static int getHeaderId() {
        if (sHeaderId == -1) {
            sHeaderId = ResourceUtils.getIdentifier(ResourceType.ID, HEADER_CONTAINER_ID);
        }
        return sHeaderId;
    }

    private static int getTitleId() {
        if (sTitleId == -1) {
            sTitleId = ResourceUtils.getIdentifier(ResourceType.ID, TITLE_ID);
        }
        return sTitleId;
    }

    private static int dp(Context context, int dpValue) {
        return Math.round(dpValue * context.getResources().getDisplayMetrics().density);
    }
}
