package com.example.cppr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.animation.ObjectAnimator;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class FloatingWindowService extends Service {
    private int statusBarHeight = 0;

    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private static final int RISK_NOTIFICATION_ID_STEP1 = 10086;
    private static final int RISK_NOTIFICATION_ID_STEP2 = 10087;

    private WindowManager windowManager;
    private View floatingView;
    private View collapsedView;
    private boolean isExpanded = true;

    private LayoutParams expandedParams;
    private LayoutParams collapsedParams;

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;

    private int screenWidth, screenHeight;

    private RiskBoundsOverlay riskBoundsOverlay;
    private BroadcastReceiver risksReceiver;
    private int lastRisksCount = 0;
    private List<RiskData> dynamicRiskItems = new ArrayList<>();

    private static final String ACTION_PAUSE_SCREENSHOT = "com.example.cpp.PAUSE_SCREENSHOT";
    private static final String ACTION_RESUME_SCREENSHOT = "com.example.cpp.RESUME_SCREENSHOT";

    private RiskData currentNotificationTarget = null;

    private View ppOriginalDialogView;
    private boolean isPPOriginalDialogShowing = false;

    // Prevents immediate shake animation right after collapsing
    private boolean justCollapsed = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "SHOW_PP_ORIGINAL_DIALOG".equals(intent.getAction())) {
            String ppOriginal = intent.getStringExtra("pp_original");
            if (ppOriginal != null) {
                showPPOriginalDialog(ppOriginal);
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Log.d("FloatingWindowService", "onCreate: starting foreground...");
        createNotificationChannelIfNeeded();
        Notification n = buildOngoingNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(1001, n);
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            windowManager.getDefaultDisplay().getMetrics(metrics);
        }
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            statusBarHeight = getResources().getDimensionPixelSize(resourceId);
        } else {
            statusBarHeight = (int) (25 * getResources().getDisplayMetrics().density);
        }

        Log.d("FloatingWindowService", "onCreate: registerRisksReceiver");
        registerRisksReceiver();

        try {
            createRiskBoundsOverlay();
        } catch (Throwable t) {
            Log.e("FloatingWindowService", "Failed to create risk bounds overlay", t);
        }

        try {
            createExpandedView();
            createCollapsedView();
            showExpandedView();
        } catch (Throwable t) {
            Log.e("FloatingWindowService", "Failed to create/show floating views", t);
        }
    }

    private void createRiskBoundsOverlay() {
        riskBoundsOverlay = new RiskBoundsOverlay(this);

        int overlayFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        LayoutParams overlayParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        LayoutParams.TYPE_APPLICATION_OVERLAY : LayoutParams.TYPE_PHONE,
                overlayFlags,
                PixelFormat.TRANSLUCENT
        );
        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = 0;
        overlayParams.y = 0;

        try {
            windowManager.addView(riskBoundsOverlay, overlayParams);
        } catch (Throwable t) {
            Log.e("FloatingWindowService", "addView(riskBoundsOverlay) failed", t);
        }
        riskBoundsOverlay.setShowBounds(false);
    }

    private void registerRisksReceiver() {
        if (risksReceiver != null) return;

        risksReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();

                if ("com.example.cpp.QUERY_FLOATING_WINDOW_INFO".equals(action)) {
                    if (isExpanded && floatingView != null && floatingView.getParent() != null) {
                        tryUpdateMaskRectForExpanded();
                    }
                    return;
                }

                if ("SHOW_PP_ORIGINAL_DIALOG".equals(action)) {
                    String ppOriginal = intent.getStringExtra("pp_original");
                    if (ppOriginal != null) {
                        showPPOriginalDialog(ppOriginal);
                    }
                    return;
                }

                ArrayList<RiskData> riskDataList = intent.getParcelableArrayListExtra("risk_data_list");

                if (riskDataList != null && !riskDataList.isEmpty()) {
                    try {
                        createDynamicWarningItemsFromRiskData(riskDataList);
                        lastRisksCount = riskDataList.size();

                        if (riskBoundsOverlay != null) {
                            riskBoundsOverlay.setShowBounds(false);
                            riskBoundsOverlay.clearBoundingBoxes();
                        }

                        if (!isExpanded && riskDataList.size() > 0 && !justCollapsed) {
                            triggerCollapsedShake(2);
                        }

                        if (justCollapsed) {
                            justCollapsed = false;
                        }
                    } catch (Exception e) {
                        Log.e("FloatingWindowService", "Failed to process RiskData objects", e);
                        fallbackToJsonProcessing(intent);
                    }
                } else {
                    fallbackToJsonProcessing(intent);
                }
            }
        };

        IntentFilter filter = new IntentFilter(PrivacyService.ACTION_UPDATE_RISKS);
        filter.addAction("com.example.cpp.QUERY_FLOATING_WINDOW_INFO");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(risksReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(risksReceiver, filter);
        }
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelId = "FLOATING_WINDOW_CHANNEL_V2";
            NotificationChannel channel = new NotificationChannel(channelId, "Floating Window", NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildOngoingNotification() {
        String channelId = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? "FLOATING_WINDOW_CHANNEL_V2" : "";
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Privacy Monitor Running")
                .setContentText("Waiting for risk data...")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    private void createExpandedView() {
        Log.d("FloatingWindowService", "createExpandedView");
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_widget_layout, null);

        expandedParams = new LayoutParams(
                dpToPx(100),
                LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        LayoutParams.TYPE_APPLICATION_OVERLAY : LayoutParams.TYPE_PHONE,
                LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        expandedParams.gravity = Gravity.TOP | Gravity.END;
        expandedParams.x = 0;
        expandedParams.y = screenHeight / 2 - 100;

        setupTouchListener(floatingView);

        Switch toggleButton = floatingView.findViewById(R.id.toggle_button);
        toggleButton.setChecked(true);
        toggleButton.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                showExpandedView();
            } else {
                showCollapsedView();
            }
        });
    }

    private View createWarningItemView(RiskData item) {
        if (item == null) return null;
        View warningItemView = LayoutInflater.from(this).inflate(R.layout.warning_item_layout, null);
        configureWarningItem(warningItemView, item);
        return warningItemView;
    }

    private void configureWarningItem(View warningView, RiskData item) {
        warningView.setBackgroundResource(item.getBackgroundResId());

        View statusIndicator = warningView.findViewById(R.id.status_indicator);
        if (statusIndicator != null) {
            statusIndicator.setBackgroundResource(item.getStatusIndicatorDrawable());
        }

        ImageView functionIcon = warningView.findViewById(R.id.function_icon);
        if (functionIcon != null) {
            functionIcon.setImageResource(item.getFunctionIconResId());
            functionIcon.setColorFilter(item.getFunctionIconTint());
        }

        ImageView statusIcon = warningView.findViewById(R.id.status_icon);
        if (statusIcon != null) {
            statusIcon.setColorFilter(item.getStatusIconTint());
        }
    }

    private void showChainNotifications(String title, String message1, String message2, RiskData targetItem) {
        currentNotificationTarget = targetItem;

        if (targetItem != null) {
            showSpecificRiskBounds(targetItem);
        }

        Intent pauseIntent = new Intent(ACTION_PAUSE_SCREENSHOT);
        pauseIntent.setPackage(getPackageName());
        sendBroadcast(pauseIntent);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel("RISK_MESSAGE_CHANNEL_V2", "Risk Alerts", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Privacy risk notifications");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }

        NotificationCompat.Builder firstBuilder = new NotificationCompat.Builder(this, "RISK_MESSAGE_CHANNEL_V2")
                .setContentTitle(title)
                .setContentText(message1)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message1))
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setOngoing(false)
                .setOnlyAlertOnce(false);
        notificationManager.notify(RISK_NOTIFICATION_ID_STEP1, firstBuilder.build());

        notificationHandler.removeCallbacksAndMessages(null);

        notificationHandler.postDelayed(() -> {
            try {
                notificationManager.cancel(RISK_NOTIFICATION_ID_STEP1);
            } catch (Throwable ignore) {}

            String message2WithHint = message2 + "（Click to Learn More）";

            PendingIntent pendingIntent = null;
            if (currentNotificationTarget != null) {
                Intent intent = new Intent(this, FloatingWindowService.class);
                intent.setAction("SHOW_PP_ORIGINAL_DIALOG");
                intent.putExtra("pp_original", currentNotificationTarget.getPPOriginal());
                pendingIntent = PendingIntent.getService(
                        this,
                        (int) System.currentTimeMillis(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
            }

            NotificationCompat.Builder secondBuilder = new NotificationCompat.Builder(this, "RISK_MESSAGE_CHANNEL_V2")
                    .setContentTitle(title)
                    .setContentText(message2WithHint)
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(message2WithHint))
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .setOnlyAlertOnce(false);

            if (pendingIntent != null) {
                secondBuilder.setContentIntent(pendingIntent);
            }
            notificationManager.notify(RISK_NOTIFICATION_ID_STEP2, secondBuilder.build());

            notificationHandler.postDelayed(() -> {
                try {
                    notificationManager.cancel(RISK_NOTIFICATION_ID_STEP2);
                } catch (Throwable ignore) {}
                if (targetItem != null) {
                    hideSpecificRiskBounds(targetItem);
                }
                Intent resumeIntent = new Intent(ACTION_RESUME_SCREENSHOT);
                resumeIntent.setPackage(getPackageName());
                sendBroadcast(resumeIntent);
            }, 5000);
        }, 4000);
    }

    private void showSpecificRiskBounds(RiskData item) {
        if (item == null || riskBoundsOverlay == null) return;

        List<BoundingBox> coordinates = item.getCoordinates();
        if (coordinates == null || coordinates.isEmpty()) {
            Log.e("FloatingWindowService", "RiskData coordinates are empty");
            return;
        }

        riskBoundsOverlay.clearBoundingBoxes();
        for (int i = 0; i < coordinates.size(); i++) {
            BoundingBox box = coordinates.get(i);
            if (box != null) {
                BoundingBox correctedBox = new BoundingBox(
                        box.getLeft(),
                        box.getTop() - statusBarHeight,
                        box.getRight(),
                        box.getBottom() - statusBarHeight,
                        box.getRiskLevel()
                );
                riskBoundsOverlay.addBoundingBox(item.getTypeZh() + "_" + i, correctedBox);
            }
        }
        riskBoundsOverlay.setShowBounds(true);
        riskBoundsOverlay.invalidate();
    }

    private void hideSpecificRiskBounds(RiskData item) {
        if (item == null || riskBoundsOverlay == null) return;
        riskBoundsOverlay.removeBoundingBox(item.getTypeZh());
        riskBoundsOverlay.setShowBounds(false);
    }

    private void createCollapsedView() {
        collapsedView = LayoutInflater.from(this).inflate(R.layout.floating_widget_collapsed, null);

        collapsedParams = new LayoutParams(
                dpToPx(32),
                LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        LayoutParams.TYPE_APPLICATION_OVERLAY : LayoutParams.TYPE_PHONE,
                LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        collapsedParams.gravity = Gravity.TOP | Gravity.END;

        setupTouchListener(collapsedView);

        collapsedView.setOnClickListener(v -> {
            isExpanded = true;
            showExpandedView();
        });
    }

    private void setupTouchListener(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        if (isExpanded) {
                            initialX = expandedParams.x;
                            initialY = expandedParams.y;
                        } else {
                            initialX = collapsedParams.x;
                            initialY = collapsedParams.y;
                        }
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float deltaX = event.getRawX() - initialTouchX;
                        float deltaY = event.getRawY() - initialTouchY;

                        if (!isDragging && (Math.abs(deltaX) > 10 || Math.abs(deltaY) > 10)) {
                            isDragging = true;
                        }

                        if (isDragging) {
                            int newX = initialX + (int) deltaX;
                            int newY = initialY + (int) deltaY;

                            if (isExpanded) {
                                newX = 10;
                                newY = Math.max(0, Math.min(newY, screenHeight - 250));
                            } else {
                                newX = 10;
                                newY = Math.max(0, Math.min(newY, screenHeight - 40));
                            }
                            updatePosition(newX, newY);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            v.performClick();
                        }
                        isDragging = false;
                        return true;
                }
                return false;
            }
        });
    }

    private void updatePosition(int x, int y) {
        if (isExpanded) {
            expandedParams.x = x;
            expandedParams.y = y;
            if (floatingView != null && floatingView.getParent() != null) {
                windowManager.updateViewLayout(floatingView, expandedParams);
            }
            tryUpdateMaskRectForExpanded();
        } else {
            collapsedParams.x = x;
            collapsedParams.y = y;
            if (collapsedView != null && collapsedView.getParent() != null) {
                windowManager.updateViewLayout(collapsedView, collapsedParams);
            }
            OverlayMaskState.clear();
        }
    }

    private void showExpandedView() {
        isExpanded = true;

        if (collapsedView != null && collapsedView.getParent() != null) {
            expandedParams.x = 10;
            expandedParams.y = collapsedParams.y;
            windowManager.removeView(collapsedView);
        }

        if (floatingView != null && floatingView.getParent() == null) {
            try {
                windowManager.addView(floatingView, expandedParams);
            } catch (Throwable t) {
                Log.e("FloatingWindowService", "addView(floatingView) failed", t);
            }
        }
        tryUpdateMaskRectForExpanded();

        if (floatingView != null) {
            Switch toggleButton = floatingView.findViewById(R.id.toggle_button);
            if (toggleButton != null) {
                toggleButton.setChecked(true);
            }
        }
    }

    private void showCollapsedView() {
        isExpanded = false;
        justCollapsed = true;

        if (floatingView != null && floatingView.getParent() != null) {
            windowManager.removeView(floatingView);
        }

        if (collapsedView != null && collapsedView.getParent() == null) {
            collapsedParams.x = 10;
            collapsedParams.y = expandedParams.y;
            try {
                windowManager.addView(collapsedView, collapsedParams);
            } catch (Throwable t) {
                Log.e("FloatingWindowService", "addView(collapsedView) failed", t);
            }
        }
        OverlayMaskState.clear();

        new Handler(Looper.getMainLooper()).postDelayed(() -> justCollapsed = false, 2000);
    }

    private void tryUpdateMaskRectForExpanded() {
        if (floatingView == null || floatingView.getParent() == null) {
            OverlayMaskState.clear();
            return;
        }

        floatingView.post(() -> {
            try {
                int[] loc = new int[2];
                floatingView.getLocationOnScreen(loc);
                int w = floatingView.getWidth();
                int h = floatingView.getHeight();

                if (w <= 0 || h <= 0) return;

                android.graphics.Rect r = new android.graphics.Rect(
                        loc[0] - 10, loc[1], loc[0] + w - 10, loc[1] + h
                );
                java.util.List<android.graphics.Rect> list = new java.util.ArrayList<>();
                list.add(r);
                OverlayMaskState.setRects(list);
            } catch (Exception e) {
                Log.e("FloatingWindowService", "Failed to update mask rect", e);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (risksReceiver != null) {
                unregisterReceiver(risksReceiver);
                risksReceiver = null;
            }
        } catch (Exception ignore) {}

        if (floatingView != null && floatingView.getParent() != null) {
            windowManager.removeView(floatingView);
        }
        if (collapsedView != null && collapsedView.getParent() != null) {
            windowManager.removeView(collapsedView);
        }
        if (riskBoundsOverlay != null && riskBoundsOverlay.getParent() != null) {
            windowManager.removeView(riskBoundsOverlay);
        }
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    // Shakes the collapsed widget left/right to alert user of new risks
    private void triggerCollapsedShake(int times) {
        if (collapsedView == null || collapsedView.getParent() == null) return;
        if (times <= 0) times = 1;

        int shift = dpToPx(10);
        Handler mainHandler = new Handler(Looper.getMainLooper());
        final int repeat = times;

        final Runnable[] runnerHolder = new Runnable[1];
        runnerHolder[0] = new Runnable() {
            int count = 0;
            @Override
            public void run() {
                if (count >= repeat) return;

                ObjectAnimator goLeft = ObjectAnimator.ofFloat(collapsedView, "translationX", 0f, -shift);
                goLeft.setDuration(80);
                ObjectAnimator back = ObjectAnimator.ofFloat(collapsedView, "translationX", -shift, 0f);
                back.setDuration(80);
                ObjectAnimator scaleX = ObjectAnimator.ofFloat(collapsedView, "scaleX", 1.0f, 1.2f, 1.0f);
                scaleX.setDuration(160);
                ObjectAnimator scaleY = ObjectAnimator.ofFloat(collapsedView, "scaleY", 1.0f, 1.2f, 1.0f);
                scaleY.setDuration(160);
                ObjectAnimator alpha = ObjectAnimator.ofFloat(collapsedView, "alpha", 1.0f, 0.7f, 1.0f);
                alpha.setDuration(160);

                back.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(android.animation.Animator animation) {
                        count++;
                        if (count < repeat) {
                            mainHandler.postDelayed(runnerHolder[0], 100);
                        }
                    }
                });

                android.animation.AnimatorSet shakeSet = new android.animation.AnimatorSet();
                shakeSet.playTogether(goLeft, back, scaleX, scaleY, alpha);
                shakeSet.start();
            }
        };

        mainHandler.post(runnerHolder[0]);
        addRedGlowEffect();
    }

    private void addRedGlowEffect() {
        if (collapsedView == null) return;

        GradientDrawable glowDrawable = new GradientDrawable();
        glowDrawable.setShape(GradientDrawable.RECTANGLE);
        glowDrawable.setCornerRadius(dpToPx(16));
        glowDrawable.setStroke(dpToPx(3), getResources().getColor(android.R.color.holo_red_light));
        glowDrawable.setColor(0x00FFFFFF);
        collapsedView.setElevation(dpToPx(8));
        collapsedView.setBackground(glowDrawable);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (collapsedView != null) {
                collapsedView.setBackgroundResource(R.drawable.floating_widget_collapsed_background);
                collapsedView.setElevation(0);
            }
        }, 3000);
    }

    private void showContextMenu(View anchorView, String title, String option1, String option2) {
        android.view.LayoutInflater inflater = android.view.LayoutInflater.from(this);
        android.view.View content = inflater.inflate(R.layout.risk_context_menu, null);

        android.widget.TextView actionTop = content.findViewById(R.id.menu_action_top);
        android.widget.TextView actionBottom = content.findViewById(R.id.menu_action_bottom);
        actionTop.setText(option1);
        actionBottom.setText(option2);

        final android.widget.PopupWindow window = new android.widget.PopupWindow(
                content,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        window.setBackgroundDrawable(getResources().getDrawable(R.drawable.context_menu_bg));
        window.setElevation(dpToPx(6));

        actionTop.setOnClickListener(v -> {
            hideWarningItem(anchorView);
            window.dismiss();
        });
        actionBottom.setOnClickListener(v -> window.dismiss());

        window.showAsDropDown(anchorView, -dpToPx(8), -anchorView.getHeight());
    }

    private void hideWarningItem(View warningView) {
        if (warningView != null) {
            warningView.setVisibility(View.GONE);
        }
    }

    private void createDynamicWarningItemsFromRiskData(List<RiskData> riskDataList) {
        try {
            dynamicRiskItems.clear();

            for (RiskData riskData : riskDataList) {
                int iconResId = RiskData.getIconResourceIdFromIconName(riskData.getIconName());
                BoundingBox.RiskLevel riskLevel = RiskData.fromEnglishName(riskData.getSeverity());
                int backgroundResId = RiskData.getRiskLevelBackgroundResourceId(riskLevel);
                int functionIconColorResId = RiskData.getRiskLevelFunctionIconColorResourceId(riskLevel);
                int statusIconColorResId = RiskData.getRiskLevelStatusIconColorResourceId(riskLevel);

                riskData.setUIResources(
                        getRiskLevelStatusIndicatorDrawable(riskLevel),
                        iconResId,
                        getResources().getColor(functionIconColorResId),
                        backgroundResId,
                        getResources().getColor(statusIconColorResId)
                );
                riskData.setGlobalRiskLevel(riskLevel);

                BoundingBox firstCoordinate = riskData.getFirstCoordinate();
                if (firstCoordinate != null) {
                    riskData.setFirstCoordinateBounds(
                            firstCoordinate.getLeft(), firstCoordinate.getTop(),
                            firstCoordinate.getRight(), firstCoordinate.getBottom(), riskLevel);
                }

                dynamicRiskItems.add(riskData);
            }

            Log.d("FloatingWindowService", "Created " + dynamicRiskItems.size() + " dynamic RiskData items");
            updateDynamicWarningItemsDisplay();
        } catch (Exception e) {
            Log.e("FloatingWindowService", "Failed to create dynamic WarningItems from RiskData", e);
        }
    }

    private void fallbackToJsonProcessing(Intent intent) {
        String json = intent.getStringExtra(PrivacyService.EXTRA_RISKS_JSON);
        Log.d("FloatingWindowService", "Fallback to JSON processing");

        if (json != null && !json.isEmpty()) {
            try {
                JSONArray risksArray = new JSONArray(json);
                lastRisksCount = risksArray.length();
                createDynamicWarningItems(risksArray);

                if (riskBoundsOverlay != null) {
                    riskBoundsOverlay.setShowBounds(false);
                    riskBoundsOverlay.clearBoundingBoxes();
                }

                if (!isExpanded && lastRisksCount > 0) {
                    triggerCollapsedShake(3);
                }
            } catch (Exception e) {
                Log.e("FloatingWindowService", "Failed to parse risks JSON (fallback)", e);
                clearAllRiskData();
            }
        } else {
            clearAllRiskData();
        }
    }

    private void clearAllRiskData() {
        if (riskBoundsOverlay != null) {
            riskBoundsOverlay.setShowBounds(false);
            riskBoundsOverlay.clearBoundingBoxes();
            riskBoundsOverlay.invalidate();
        }
        lastRisksCount = 0;
        clearDynamicWarningItems();
    }

    private void createDynamicWarningItems(JSONArray risksArray) {
        try {
            dynamicRiskItems.clear();

            if (riskBoundsOverlay != null) {
                riskBoundsOverlay.clearBoundingBoxes();
            }

            for (int i = 0; i < risksArray.length(); i++) {
                JSONObject risk = risksArray.getJSONObject(i);

                String typeEn = risk.optString("typeEn", "Unknown");
                String typeZh = risk.optString("typeZh", "Unknown Risk");
                String severity = risk.optString("severity", "medium");
                String iconName = risk.optString("iconName", "");
                String message1 = risk.optString("message1", "");
                String message2 = risk.optString("message2", "");
                String PPOriginal = risk.optString("PPOriginal", "");

                int iconResId = RiskData.getIconResourceIdFromIconName(iconName);
                BoundingBox.RiskLevel riskLevel = RiskData.fromEnglishName(severity);
                int backgroundResId = RiskData.getRiskLevelBackgroundResourceId(riskLevel);
                int functionIconColorResId = RiskData.getRiskLevelFunctionIconColorResourceId(riskLevel);
                int statusIconColorResId = RiskData.getRiskLevelStatusIconColorResourceId(riskLevel);

                RiskData riskItem = new RiskData(
                        typeEn, typeZh, severity, iconName, message1, message2, PPOriginal, new ArrayList<>()
                );

                riskItem.setUIResources(
                        getRiskLevelStatusIndicatorDrawable(riskLevel),
                        iconResId,
                        getResources().getColor(functionIconColorResId),
                        backgroundResId,
                        getResources().getColor(statusIconColorResId)
                );
                riskItem.setGlobalRiskLevel(riskLevel);

                JSONArray coordinates = risk.optJSONArray("coordinates");
                if (coordinates != null && coordinates.length() > 0) {
                    JSONObject firstCoord = coordinates.getJSONObject(0);
                    try {
                        int left = firstCoord.getJSONArray("top_left").getInt(0);
                        int top = firstCoord.getJSONArray("top_left").getInt(1);
                        int right = firstCoord.getJSONArray("bottom_right").getInt(0);
                        int bottom = firstCoord.getJSONArray("bottom_right").getInt(1);
                        riskItem.setFirstCoordinateBounds(left, top, right, bottom, riskLevel);
                    } catch (Exception e) {
                        Log.w("FloatingWindowService", "Failed to parse coordinates for risk " + i, e);
                    }
                }

                dynamicRiskItems.add(riskItem);
            }

            Log.d("FloatingWindowService", "Created " + dynamicRiskItems.size() + " dynamic RiskData items");
            updateDynamicWarningItemsDisplay();
        } catch (Exception e) {
            Log.e("FloatingWindowService", "Failed to create dynamic WarningItems", e);
        }
    }

    private void clearDynamicWarningItems() {
        dynamicRiskItems.clear();
        if (riskBoundsOverlay != null) {
            riskBoundsOverlay.clearBoundingBoxes();
        }
        updateDynamicWarningItemsDisplay();
    }

    private void updateDynamicWarningItemsDisplay() {
        if (floatingView == null) return;

        LinearLayout container = floatingView.findViewById(R.id.dynamic_warning_items_container);
        if (container == null) {
            Log.e("FloatingWindowService", "Dynamic container not found");
            return;
        }

        container.removeAllViews();

        for (int i = 0; i < dynamicRiskItems.size(); i++) {
            RiskData item = dynamicRiskItems.get(i);
            View warningItemView = createWarningItemView(item);
            if (warningItemView != null) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );
                if (i > 0) {
                    params.topMargin = dpToPx(4);
                }
                warningItemView.setLayoutParams(params);
                container.addView(warningItemView);
                setupWarningItemListeners(warningItemView, item);
            }
        }
    }

    private void setupWarningItemListeners(View warningItemView, RiskData item) {
        if (warningItemView == null || item == null) return;

        warningItemView.setOnClickListener(v ->
                showChainNotifications(item.getTypeEn(), item.getMessage1(), item.getMessage2(), item));

        warningItemView.setOnLongClickListener(v -> {
            showContextMenu(v, item.getTypeZh(), "Dismiss", "Undo");
            return true;
        });
    }

    private int getRiskLevelStatusIndicatorDrawable(BoundingBox.RiskLevel riskLevel) {
        switch (riskLevel) {
            case HIGH:   return R.drawable.risk_dot_high;
            case MEDIUM: return R.drawable.risk_dot_medium;
            case LOW:    return R.drawable.risk_dot_low;
            default:     return R.drawable.risk_dot_medium;
        }
    }

    private void showPPOriginalDialog(String ppOriginal) {
        if (isPPOriginalDialogShowing) return;

        try {
            hideFloatingWindow();

            ppOriginalDialogView = LayoutInflater.from(this).inflate(R.layout.pp_original_dialog, null);
            TextView contentText = ppOriginalDialogView.findViewById(R.id.pp_original_content);
            contentText.setText(ppOriginal);
            Button closeButton = ppOriginalDialogView.findViewById(R.id.close_button);
            closeButton.setOnClickListener(v -> hidePPOriginalDialog());

            LayoutParams dialogParams = new LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                            LayoutParams.TYPE_APPLICATION_OVERLAY : LayoutParams.TYPE_PHONE,
                    LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT
            );
            dialogParams.gravity = Gravity.CENTER;

            windowManager.addView(ppOriginalDialogView, dialogParams);
            isPPOriginalDialogShowing = true;
        } catch (Exception e) {
            Log.e("FloatingWindowService", "Failed to show PPOriginal dialog", e);
            showFloatingWindow();
        }
    }

    private void hidePPOriginalDialog() {
        if (!isPPOriginalDialogShowing || ppOriginalDialogView == null) return;

        try {
            if (ppOriginalDialogView.getParent() != null) {
                windowManager.removeView(ppOriginalDialogView);
            }
            ppOriginalDialogView = null;
            isPPOriginalDialogShowing = false;
            showFloatingWindow();
        } catch (Exception e) {
            Log.e("FloatingWindowService", "Failed to hide PPOriginal dialog", e);
        }
    }

    private void hideFloatingWindow() {
        if (floatingView != null && floatingView.getParent() != null) {
            windowManager.removeView(floatingView);
        }
        if (collapsedView != null && collapsedView.getParent() != null) {
            windowManager.removeView(collapsedView);
        }
    }

    private void showFloatingWindow() {
        if (isExpanded) {
            showExpandedView();
        } else {
            showCollapsedView();
        }
    }
}
