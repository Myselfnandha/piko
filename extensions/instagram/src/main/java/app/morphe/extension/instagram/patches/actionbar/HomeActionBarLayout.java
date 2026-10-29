/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.instagram.patches.actionbar;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch;
import app.morphe.extension.instagram.patches.navigation.NavigationBarPatch.NotificationsVisibility;
import app.morphe.extension.instagram.utils.Pref;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

final class HomeActionBarLayout {
    private static final String TRAILING_ACTION_CONTAINER_ID =
            "action_bar_buttons_container_right";
    private static final Map<ViewGroup, Monitor> MONITORS = new WeakHashMap<>();

    private HomeActionBarLayout() {
    }

    static void observe(
            ViewGroup actionBar,
            Supplier<NotificationsVisibility> stateProvider
    ) {
        if (actionBar == null || stateProvider == null) return;
        actionBar.post(() -> {
            try {
                Monitor monitor;
                synchronized (MONITORS) {
                    monitor = MONITORS.get(actionBar);
                    if (monitor == null) {
                        monitor = new Monitor(actionBar);
                        MONITORS.put(actionBar, monitor);
                    }
                    monitor.stateProvider = stateProvider;
                }
                monitor.start();
            } catch (Exception exception) {
                Logger.printException(() -> "Failed observeTrailingActionContainer: ", exception);
            }
        });
    }

    private static final class Monitor
            implements ViewTreeObserver.OnGlobalLayoutListener,
            View.OnAttachStateChangeListener {
        private final WeakReference<ViewGroup> actionBar;
        private Supplier<NotificationsVisibility> stateProvider;
        private WeakReference<ViewGroup> collapsedContainer;
        private final List<WeakReference<View>> hiddenInboxViews = new ArrayList<>();
        private int originalVisibility = -1;
        private ViewTreeObserver observer;
        private boolean attachListenerRegistered;
        private boolean evaluating;

        private Monitor(ViewGroup actionBar) {
            this.actionBar = new WeakReference<>(actionBar);
        }

        private void start() {
            ViewGroup bar = actionBar.get();
            if (bar == null) return;
            if (!attachListenerRegistered) {
                bar.addOnAttachStateChangeListener(this);
                attachListenerRegistered = true;
            }
            if (bar.isAttachedToWindow()) attach(bar);
        }

        private void attach(ViewGroup bar) {
            if (observer == null || !observer.isAlive()) {
                observer = bar.getViewTreeObserver();
                if (observer.isAlive()) observer.addOnGlobalLayoutListener(this);
            }
            evaluate();
        }

        private void detach() {
            if (observer != null && observer.isAlive()) {
                observer.removeOnGlobalLayoutListener(this);
            }
            observer = null;
            restore();
            restoreInboxViews();
        }

        @Override
        public void onGlobalLayout() {
            evaluate();
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (view instanceof ViewGroup) attach((ViewGroup) view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            detach();
        }

        private void evaluate() {
            if (evaluating) return;
            evaluating = true;
            try {
                ViewGroup bar = actionBar.get();
                if (bar == null || stateProvider == null) {
                    restore();
                    return;
                }
                int id = ResourceUtils.getIdentifier(
                        ResourceType.ID,
                        TRAILING_ACTION_CONTAINER_ID
                );
                View view = id == 0 ? null : bar.findViewById(id);
                if (!(view instanceof ViewGroup)) {
                    if (id != 0 && bar.getId() == id) {
                        view = bar;
                    } else if (bar.getParent() instanceof View && id != 0) {
                        view = ((View) bar.getParent()).findViewById(id);
                    }
                }
                if (!(view instanceof ViewGroup)) {
                    restore();
                    return;
                }

                ViewGroup container = (ViewGroup) view;
                ViewGroup collapsed = collapsed();
                if (collapsed != null && collapsed != container) restore();

                boolean hideInbox = NavigationBarPatch.isMessagesTabInBottomBar();
                updateInboxButton(bar, container, hideInbox);

                NotificationsVisibility state = stateProvider.get();
                boolean hideNotifications = state == NotificationsVisibility.VISIBLE || Pref.getHideHomeNotificationsButton();
                if (!hideNotifications || hasVisibleDirectChild(container, hideInbox)) {
                    restore();
                } else if (collapsed() != container
                        && container.getVisibility() != View.GONE) {
                    collapsedContainer = new WeakReference<>(container);
                    originalVisibility = container.getVisibility();
                    container.setVisibility(View.GONE);
                }
            } catch (Exception exception) {
                restore();
                Logger.printException(
                        () -> "Failed updateTrailingActionContainer: ",
                        exception
                );
            } finally {
                evaluating = false;
            }
        }

        private void updateInboxButton(ViewGroup bar, ViewGroup container, boolean hideInbox) {
            if (hideInbox) {
                int inboxId = ResourceUtils.getIdentifier(ResourceType.ID, "action_bar_inbox_button");
                if (inboxId != 0) {
                    View inbox = container.findViewById(inboxId);
                    if (inbox == null) inbox = bar.findViewById(inboxId);
                    hideView(inbox);
                }
                for (int i = 0; i < container.getChildCount(); i++) {
                    View child = container.getChildAt(i);
                    if (child != null && isDirectMessageView(child)) {
                        hideView(child);
                    }
                }
            } else {
                restoreInboxViews();
            }
        }

        private void hideView(View view) {
            if (view != null && view.getVisibility() != View.GONE) {
                view.setVisibility(View.GONE);
                hiddenInboxViews.add(new WeakReference<>(view));
            }
        }

        private void restoreInboxViews() {
            for (WeakReference<View> ref : hiddenInboxViews) {
                View v = ref.get();
                if (v != null && v.getVisibility() == View.GONE) {
                    v.setVisibility(View.VISIBLE);
                }
            }
            hiddenInboxViews.clear();
        }

        private static boolean isDirectMessageView(View view) {
            if (view == null) return false;
            int inboxId = ResourceUtils.getIdentifier(ResourceType.ID, "action_bar_inbox_button");
            if (inboxId != 0 && (view.getId() == inboxId || view.findViewById(inboxId) != null)) {
                return true;
            }
            try {
                if (view.getId() != View.NO_ID && view.getResources() != null) {
                    String entryName = view.getResources().getResourceEntryName(view.getId());
                    if (entryName != null && (entryName.contains("inbox") || entryName.contains("direct"))) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
            }
            CharSequence desc = view.getContentDescription();
            if (desc != null) {
                String descStr = desc.toString().toLowerCase();
                if (descStr.contains("direct") || descStr.contains("message") || descStr.contains("chats") || descStr.contains("inbox")) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasVisibleDirectChild(ViewGroup container, boolean ignoreInbox) {
            for (int index = 0; index < container.getChildCount(); index++) {
                View child = container.getChildAt(index);
                if (child != null && child.getVisibility() == View.VISIBLE) {
                    if (ignoreInbox && isDirectMessageView(child)) continue;
                    return true;
                }
            }
            return false;
        }

        private ViewGroup collapsed() {
            return collapsedContainer == null ? null : collapsedContainer.get();
        }

        private void restore() {
            ViewGroup container = collapsed();
            int visibility = originalVisibility;
            collapsedContainer = null;
            originalVisibility = -1;
            if (container != null && visibility >= 0
                    && container.getVisibility() != visibility) {
                container.setVisibility(visibility);
            }
            if (!NavigationBarPatch.isMessagesTabInBottomBar()) {
                restoreInboxViews();
            }
        }
    }
}
