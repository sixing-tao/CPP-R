package com.example.cppr;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.jetbrains.annotations.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import com.example.cppr.net.BackendJsonInterceptor;

public class PrivacyService extends Service {

    private static final String TAG = "PrivacyService";
    private static final String BACKEND_URL = "http://8.216.41.236:5005/analyze";
    private static final String NOTIFICATION_CHANNEL_ID = "PRIVACY_SERVICE_CHANNEL_V3";
    private static final String NOTIFICATION_CHANNEL_NAME = "Privacy Monitor";

    public static final String ACTION_UPDATE_RISKS = "com.example.cpp.UPDATE_RISKS";
    public static final String EXTRA_RISKS_JSON = "risks_json";

    private Bitmap lastScreenshot = null;
    private long lastScreenshotTime = 0;
    private static final long MIN_SCREENSHOT_INTERVAL = 2000;
    private static final double CHANGE_THRESHOLD = 0.12;

    private boolean isScreenshotPaused = false;
    private BroadcastReceiver screenshotPauseReceiver;

    private Handler handler;
    private Runnable analysisRunnable;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenWidth;
    private int screenHeight;
    private int screenDensity;
    private OkHttpClient okHttpClient;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        handler = new Handler(Looper.getMainLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.cpp.PAUSE_SCREENSHOT");
        filter.addAction("com.example.cpp.RESUME_SCREENSHOT");
        screenshotPauseReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if ("com.example.cpp.PAUSE_SCREENSHOT".equals(action)) {
                    isScreenshotPaused = true;
                    Log.d(TAG, "Screenshot comparison paused");
                } else if ("com.example.cpp.RESUME_SCREENSHOT".equals(action)) {
                    isScreenshotPaused = false;
                    Log.d(TAG, "Screenshot comparison resumed");
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(screenshotPauseReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenshotPauseReceiver, filter);
        }

        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    long t1 = System.nanoTime();
                    Request req = chain.request();
                    Log.d(TAG, "→ POST " + req.url() + " (hasBody=" + (req.body() != null) + ")");
                    return chain.proceed(req);
                })
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS);

        boolean isDebuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (isDebuggable) {
            clientBuilder.addInterceptor(new BackendJsonInterceptor());
        }

        okHttpClient = clientBuilder.build();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification notification = createNotification();
        startForeground(1, notification);

        if (intent != null) {
            int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra("data");
            if (resultCode == Activity.RESULT_OK && data != null) {
                MediaProjectionManager mediaProjectionManager =
                        (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data);
                if (mediaProjection != null) {
                    setupScreenCapture();
                    setupAnalysisRunnable();
                    handler.post(analysisRunnable);
                }
            }
        }
        return START_STICKY;
    }

    private void setupScreenCapture() {
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            windowManager.getDefaultDisplay().getMetrics(metrics);
        }

        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
        screenDensity = metrics.densityDpi;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2);

        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Log.d(TAG, "MediaProjection stopped");
                if (virtualDisplay != null) {
                    virtualDisplay.release();
                    virtualDisplay = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
            }
        }, handler);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenCapture",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, null);
    }

    private void setupAnalysisRunnable() {
        analysisRunnable = new Runnable() {
            @Override
            public void run() {
                checkScreenChangeAndAnalyze();
                handler.postDelayed(this, 2000);
            }
        };
    }

    private void checkScreenChangeAndAnalyze() {
        if (isScreenshotPaused) {
            Log.d(TAG, "Screenshot comparison paused, skipping...");
            return;
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastScreenshotTime < MIN_SCREENSHOT_INTERVAL) {
            Log.d(TAG, "Skipping - too frequent");
            return;
        }

        Bitmap currentScreenshot = captureQuickScreenshot();
        if (currentScreenshot == null) {
            Log.w(TAG, "Failed to capture quick screenshot");
            return;
        }

        try {
            boolean hasChanged;

            if (lastScreenshot == null) {
                hasChanged = true;
            } else {
                double similarity = calculateImageSimilarity(lastScreenshot, currentScreenshot);
                hasChanged = similarity < (1.0 - CHANGE_THRESHOLD);
                Log.d(TAG, String.format("Screen similarity: %.2f%%, changed: %b", similarity * 100, hasChanged));
            }

            if (hasChanged) {
                captureAndAnalyzeScreen();
                lastScreenshotTime = currentTime;
                if (lastScreenshot != null) {
                    lastScreenshot.recycle();
                }
                lastScreenshot = currentScreenshot;
            } else {
                currentScreenshot.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in change detection", e);
            currentScreenshot.recycle();
        }
    }

    private Bitmap captureQuickScreenshot() {
        if (imageReader == null) return null;

        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image != null) {
                Bitmap fullBitmap = convertImageToBitmap(image);
                if (fullBitmap != null) {
                    // Scale to 1/4 size for faster comparison
                    Bitmap smallBitmap = Bitmap.createScaledBitmap(
                            fullBitmap,
                            fullBitmap.getWidth() / 4,
                            fullBitmap.getHeight() / 4,
                            true
                    );
                    fullBitmap.recycle();
                    return smallBitmap;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in quick screenshot", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
        return null;
    }

    private double calculateImageSimilarity(Bitmap bitmap1, Bitmap bitmap2) {
        if (bitmap1 == null || bitmap2 == null) return 0.0;

        if (bitmap1.getWidth() != bitmap2.getWidth() || bitmap1.getHeight() != bitmap2.getHeight()) {
            return 0.0;
        }

        int width = bitmap1.getWidth();
        int height = bitmap1.getHeight();
        int totalPixels = width * height;
        int similarPixels = 0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel1 = bitmap1.getPixel(x, y);
                int pixel2 = bitmap2.getPixel(x, y);

                int colorDiff = Math.abs(Color.red(pixel1) - Color.red(pixel2))
                        + Math.abs(Color.green(pixel1) - Color.green(pixel2))
                        + Math.abs(Color.blue(pixel1) - Color.blue(pixel2));

                if (colorDiff < 50) {
                    similarPixels++;
                }
            }
        }

        return (double) similarPixels / totalPixels;
    }

    private java.util.List<android.graphics.Rect> getCurrentFloatingWindowMask() {
        try {
            Intent queryIntent = new Intent("com.example.cpp.QUERY_FLOATING_WINDOW_INFO");
            queryIntent.setPackage(getPackageName());
            sendBroadcast(queryIntent);

            Thread.sleep(50);

            java.util.List<android.graphics.Rect> maskRects = OverlayMaskState.getRectsCopy();
            return maskRects != null ? maskRects : new java.util.ArrayList<>();
        } catch (Exception e) {
            Log.w(TAG, "Failed to get floating window mask", e);
            return new java.util.ArrayList<>();
        }
    }

    private Bitmap convertImageToBitmap(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            java.nio.Buffer buffer = planes[0].getBuffer().rewind();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * screenWidth;

            Bitmap bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);
            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "Error converting image to bitmap", e);
            return null;
        }
    }

    private void captureAndAnalyzeScreen() {
        if (imageReader == null) {
            Log.e(TAG, "ImageReader is not initialized.");
            return;
        }

        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
            if (image != null) {
                final Image.Plane[] planes = image.getPlanes();
                final java.nio.Buffer buffer = planes[0].getBuffer().rewind();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * screenWidth;

                Bitmap wide = Bitmap.createBitmap(screenWidth + rowPadding / pixelStride,
                        screenHeight, Bitmap.Config.ARGB_8888);
                wide.copyPixelsFromBuffer(buffer);

                Bitmap bitmap = Bitmap.createBitmap(wide, 0, 0, screenWidth, screenHeight);
                wide.recycle();

                try {
                    java.util.List<android.graphics.Rect> maskRects = getCurrentFloatingWindowMask();
                    if (maskRects != null && !maskRects.isEmpty()) {
                        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                        android.graphics.Paint paint = new android.graphics.Paint();
                        paint.setStyle(android.graphics.Paint.Style.FILL);
                        paint.setColor(android.graphics.Color.BLACK);
                        for (android.graphics.Rect r : maskRects) {
                            int l = Math.max(0, Math.min(r.left, screenWidth));
                            int t = Math.max(0, Math.min(r.top, screenHeight));
                            int rr = Math.max(0, Math.min(r.right, screenWidth));
                            int b = Math.max(0, Math.min(r.bottom, screenHeight));
                            if (rr > l && b > t) {
                                canvas.drawRect(l, t, rr, b, paint);
                            }
                        }
                    }
                } catch (Throwable e) {
                    Log.e(TAG, "Error applying mask", e);
                }

                ByteArrayOutputStream stream = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
                byte[] byteArray = stream.toByteArray();
                bitmap.recycle();

                Log.d(TAG, "Screenshot captured, sending to backend...");
                analyzeScreenWithBackend(byteArray);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing screen", e);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    private void analyzeScreenWithBackend(byte[] imageBytes) {
        try {
            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("screenshot", "screenshot.png",
                            RequestBody.create(imageBytes, MediaType.parse("image/png")))
                    .build();

            Request request = new Request.Builder()
                    .url(BACKEND_URL)
                    .post(requestBody)
                    .build();

            okHttpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Failed to connect to backend: ", e);
                    handler.post(() -> Toast.makeText(PrivacyService.this, "Cannot connect to analysis server", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    String responseBody = null;
                    try {
                        responseBody = (response.body() != null) ? response.body().string() : null;
                        Log.d(TAG, "HTTP ← code=" + response.code()
                                + ", len=" + (responseBody == null ? -1 : responseBody.length()));

                        if (!response.isSuccessful()) {
                            Log.e("Backend", "code=" + response.code() + ", body="
                                    + (responseBody != null ? responseBody.substring(0, Math.min(1024, responseBody.length())) : ""));
                            return;
                        }

                        if (responseBody == null || responseBody.isEmpty()) {
                            Log.e(TAG, "Backend returned an empty body");
                            return;
                        }

                        JSONObject json = new JSONObject(responseBody);

                        boolean success = json.optBoolean("success", true);
                        int totalRisks = json.optInt("total_risks", -1);
                        Log.d(TAG, "Parsed response → success=" + success + ", total_risks=" + totalRisks);

                        if (!success) {
                            String msg = json.optJSONObject("error") != null
                                    ? json.optJSONObject("error").optString("message", json.optString("error"))
                                    : json.optString("error", "Unknown error");
                            Log.e(TAG, "Analyze success=false: " + msg);
                            return;
                        }

                        JSONArray risksArr = json.optJSONArray("risks");
                        List<RiskData> riskDataList = new ArrayList<>();

                        if (risksArr != null) {
                            for (int i = 0; i < risksArr.length(); i++) {
                                JSONObject r = risksArr.getJSONObject(i);

                                String typeEn = r.optString("typeEn", "N/A");
                                String typeZh = r.optString("typeZh", typeEn);
                                String severity = r.optString("severity", "N/A");
                                String iconName = r.optString("iconName", "N/A");
                                String message1 = r.optString("message1", "");
                                String message2 = r.optString("message2", "");
                                String PPOriginal = r.optString("PPOriginal", "");

                                List<BoundingBox> boundingBoxes = new ArrayList<>();
                                JSONArray coordinates = r.optJSONArray("coordinates");
                                if (coordinates != null) {
                                    for (int j = 0; j < coordinates.length(); j++) {
                                        try {
                                            JSONObject coord = coordinates.getJSONObject(j);
                                            JSONArray topLeft = coord.getJSONArray("top_left");
                                            JSONArray bottomRight = coord.getJSONArray("bottom_right");

                                            if (topLeft.length() >= 2 && bottomRight.length() >= 2) {
                                                BoundingBox.RiskLevel riskLevel = RiskData.fromEnglishName(severity);
                                                boundingBoxes.add(new BoundingBox(
                                                        topLeft.getInt(0), topLeft.getInt(1),
                                                        bottomRight.getInt(0), bottomRight.getInt(1),
                                                        riskLevel));
                                            }
                                        } catch (Exception e) {
                                            Log.w(TAG, "Failed to parse coordinate " + j + " for risk " + i, e);
                                        }
                                    }
                                }

                                riskDataList.add(new RiskData(typeEn, typeZh, severity, iconName,
                                        message1, message2, PPOriginal, boundingBoxes));
                            }
                        } else {
                            Log.w(TAG, "No risks array in response.");
                        }

                        Intent intent = new Intent(ACTION_UPDATE_RISKS);
                        intent.putExtra(EXTRA_RISKS_JSON, risksArr != null ? risksArr.toString() : "[]");
                        intent.putParcelableArrayListExtra("risk_data_list", (ArrayList<RiskData>) riskDataList);
                        intent.setPackage(getPackageName());
                        sendBroadcast(intent);
                        Log.d(TAG, "Broadcasted " + riskDataList.size() + " risk items");

                    } catch (Exception e) {
                        Log.e(TAG, "Failed to parse backend response", e);
                    } finally {
                        if (response != null) response.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to build network request", e);
            handler.post(() -> Toast.makeText(PrivacyService.this, "Failed to build request", Toast.LENGTH_SHORT).show());
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (lastScreenshot != null) {
            lastScreenshot.recycle();
            lastScreenshot = null;
        }
        if (handler != null && analysisRunnable != null) {
            handler.removeCallbacks(analysisRunnable);
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
        }
        if (mediaProjection != null) {
            mediaProjection.stop();
        }
        if (screenshotPauseReceiver != null) {
            try {
                unregisterReceiver(screenshotPauseReceiver);
                screenshotPauseReceiver = null;
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering receiver", e);
            }
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW);
            channel.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Privacy Monitor")
                .setContentText("Analyzing screen to protect your privacy")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .build();
    }
}
