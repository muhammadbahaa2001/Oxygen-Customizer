package it.dhd.oxygencustomizer.xposed.hooks.systemui.navbar;

import static android.view.MotionEvent.ACTION_DOWN;
import static de.robv.android.xposed.XposedBridge.hookAllConstructors;
import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedBridge.log;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getFloatField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;
import static it.dhd.oxygencustomizer.xposed.XPrefs.Xprefs;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Toast;

import androidx.core.content.res.ResourcesCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import it.dhd.oxygencustomizer.BuildConfig;
import it.dhd.oxygencustomizer.R;
import it.dhd.oxygencustomizer.utils.Constants;
import it.dhd.oxygencustomizer.xposed.ResourceManager;
import it.dhd.oxygencustomizer.xposed.XPLauncher;
import it.dhd.oxygencustomizer.xposed.XposedMods;
import it.dhd.oxygencustomizer.xposed.hooks.framework.Buttons;
import it.dhd.oxygencustomizer.xposed.utils.DrawableConverter;
import it.dhd.oxygencustomizer.xposed.utils.ShellUtils;
import it.dhd.oxygencustomizer.xposed.utils.SystemUtils;

public class GestureNavbarManager extends XposedMods {

    private static final String listenPackage = Constants.Packages.SYSTEM_UI;

    private Object SideGestureConfigurationEx;

    //region Back gesture
    private List<Float> backGestureHeightFractionLeft = Arrays.asList(0f, 1f); // % of screen height. can be anything between 0 to 1
    private List<Float> backGestureHeightFractionRight = Arrays.asList(0f, 1f); // % of screen height. can be anything between 0 to 1
    private boolean leftEnabled = true;
    private boolean rightEnabled = true;
    private boolean onRotationToo = true;
    private boolean overrideBack = false;
    private int overrideMode = 0;
    private int overrideLeft = 0;
    private int overrideRight = 0;
    private int mDirection;
    private String QSExpandMethodName = "";
    private Object NotificationPanelViewController;


    public GestureNavbarManager(Context context) {
        super(context);
    }

    @Override
    public void updatePrefs(String... Key) {
        if (Xprefs == null) return;

        //region Back gesture
        leftEnabled = Xprefs.getBoolean("gesture_left", true);
        rightEnabled = Xprefs.getBoolean("gesture_right", true);
        backGestureHeightFractionLeft = Xprefs.getSliderValues( "gesture_left_height_double", 100f);
        backGestureHeightFractionRight = Xprefs.getSliderValues( "gesture_right_height_double", 100f) ;
        onRotationToo = Xprefs.getBoolean("gesture_on_rotate", true);
        overrideBack = Xprefs.getBoolean("gesture_override_holdback", false);
        overrideMode = Integer.parseInt(Xprefs.getString("gesture_override_holdback_mode", "0"));
        overrideLeft = Integer.parseInt(Xprefs.getString("gesture_override_holdback_left", "0"));
        overrideRight = Integer.parseInt(Xprefs.getString("gesture_override_holdback_right", "0"));
        //endregion
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(listenPackage)) return;

        Class<?> SideGestureDetector;
        try {
            SideGestureDetector = findClass("com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureDetector", lpparam.classLoader);
        } catch (Throwable t) {
            SideGestureDetector = findClass("com.oplusos.systemui.navigationbar.gesture.sidegesture.SideGestureDetector", lpparam.classLoader); // OOS 13
        }
        Class<?> SideGestureNavView;
        try {
          SideGestureNavView = findClass("com.oplus.systemui.navigationbar.gesture.sidegesture.SideGestureNavView", lpparam.classLoader);
        } catch (Throwable t) {
            SideGestureNavView = findClass("com.oplusos.systemui.navigationbar.gesture.sidegesture.SideGestureNavView", lpparam.classLoader); // OOS 13
        }

            if (Build.VERSION.SDK_INT >= 34) {
                hookAllConstructors(SideGestureDetector, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        SideGestureConfigurationEx = getObjectField(param.thisObject, "mSideGestureConfiguration");
                    }
                });

                hookAllMethods(SideGestureDetector, "onMotionEventImpl", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        MotionEvent ev = (MotionEvent) param.args[0];

                        if (getForegroundApp()[0].equals(getDefaultLauncherPackageName())) return;

                        Point mDisplaySize = (Point) getObjectField(param.thisObject, "mDisplaySize");
                        boolean isLeftSide = ev.getX() < (mDisplaySize.x / 3f);
                        mDirection = isLeftSide ? 0 : 1;
                        if (ev.getActionMasked() == ACTION_DOWN) //down action is enough. once gesture is refused it won't accept further actions
                        {
                            int mBottomGestureHeight = (int) callMethod(SideGestureConfigurationEx, "getBottomGestureAreaHeight");
                            int rotation = (int) getFloatField(param.thisObject, "mRotation");
                            if (notWithinInsets(ev.getX(),
                                    ev.getY(),
                                    mDisplaySize,
                                    mBottomGestureHeight, rotation)) {
                                setObjectField(param.thisObject, "mAllowGesture", false); //act like the gesture was not good enough
                                param.setResult(null); //and stop the current method too
                            }
                        }
                    }
                });
            } else {
                findAndHookMethod(SideGestureDetector, "isWithinInsets",
                        int.class,
                        int.class,
                        new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        int x = (int) param.args[0];
                        int y = (int) param.args[1];


                        if (getForegroundApp()[0].equals(getDefaultLauncherPackageName())) return;

                        Point mDisplaySize = (Point) getObjectField(param.thisObject, "mDisplaySize");
                        boolean isLeftSide = x < (mDisplaySize.x / 3f);
                        mDirection = isLeftSide ? 0 : 1;
                            int mBottomGestureHeight = (int) mContext.getResources().getDimensionPixelSize(mContext.getResources().getIdentifier("bottom_gesture_area_height", "dimen", listenPackage));
                            int rotation = (int) getFloatField(param.thisObject, "mRotation");
                            if (notWithinInsets(x,
                                    y,
                                    mDisplaySize,
                                    mBottomGestureHeight, rotation)) {
                                setObjectField(param.thisObject, "mAllowGesture", false); //act like the gesture was not good enough
                                param.setResult(false); //and stop the current method too
                            }
                    }
                });
            }

        hookAllMethods(SideGestureNavView, "setAppIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (overrideBack) {
                    Drawable icon;
                    if (overrideMode == 0) {
                        if (overrideLeft == 0) return;
                        icon = getActionIcon(overrideLeft);
                    } else {
                        if (mDirection == 0) {
                            if (overrideLeft == 0) return;
                            icon = getActionIcon(overrideLeft);
                        } else {
                            if (overrideRight == 0) return;
                            icon = getActionIcon(overrideRight);
                        }
                    }
                    param.args[0] = DrawableConverter.drawableToBitmap(icon);
                }
            }
        });

        findAndHookMethod(SideGestureDetector, "switchApp", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (overrideBack) {
                    if (overrideMode == 0) {
                        if (overrideLeft == 0) return;
                        runAction(overrideLeft);
                    } else {
                        if (mDirection == 0) {
                            if (overrideLeft == 0) return;
                            runAction(overrideLeft);
                        } else {
                            if (overrideRight == 0) return;
                            runAction(overrideRight);
                        }
                    }
                    param.setResult(null);
                }
            }
        });

        Class<?> NotificationPanelViewControllerClass;
        try {
            NotificationPanelViewControllerClass = findClass("com.android.systemui.shade.NotificationPanelViewController", lpparam.classLoader);
        } catch (Throwable t) {
            NotificationPanelViewControllerClass = findClass("com.android.systemui.statusbar.phone.NotificationPanelViewController", lpparam.classLoader); // A13
        }

        QSExpandMethodName = Arrays.stream(NotificationPanelViewControllerClass.getMethods())
                .anyMatch(m -> m.getName().equals("expandToQs"))
                ? "expandToQs" //A14
                : "expandWithQs"; //A13

        hookAllConstructors(NotificationPanelViewControllerClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                NotificationPanelViewController = param.thisObject;
            }
        });

        hookAllMethods(NotificationPanelViewControllerClass, "createTouchHandler", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                NotificationPanelViewController = param.thisObject;
            }
        });

    }

    private Drawable getActionIcon(int action) {
        int resId = switch (action) {
            case 0 -> R.drawable.ic_switch_app;
            case 1 -> R.drawable.ic_clear_all;
            case 2 -> R.drawable.ic_kill;
            case 3 -> R.drawable.ic_screenshot;
            case 4 -> R.drawable.ic_quick_settings;
            case 5 -> R.drawable.ic_power_menu;
            case 6 -> R.drawable.ic_notifications;
            case 7 -> R.drawable.ic_screen_off;
            default -> 0;
        };
        return ResourcesCompat.getDrawable(ResourceManager.modRes,
                resId,
                mContext.getTheme());
    }

    private void runAction(int action) {
        switch (action) {
            case 2 -> killForegroundApp();
            case 3 -> takeScreenshot();
            case 4 -> showQs();
            case 5 -> showPowerMenu();
            case 7 -> callMethod(SystemUtils.PowerManager(), "goToSleep", SystemClock.uptimeMillis());
        }
    }

    //region Back gesture
    private boolean notWithinInsets(float x, float y, Point mDisplaySize, float mBottomGestureHeight, int rotation) {
        boolean isLeftSide = x < (mDisplaySize.x / 3f);
        if (!onRotationToo &&
                (rotation == 1 ||
                        rotation == 3)) return false;
        if ((isLeftSide && !leftEnabled)
                || (!isLeftSide && !rightEnabled)) {
            return true;
        }


        float topLeft = 1f, bottomLeft = 0f, topRight = 1f, bottomRight = 0f;
        if (backGestureHeightFractionLeft.size() == 2) {
            topLeft = backGestureHeightFractionLeft.get(1) / 100f;
            bottomLeft = backGestureHeightFractionLeft.get(0) / 100f;
        } else {
            topLeft = backGestureHeightFractionLeft.get(0) / 100f;
            bottomLeft = 0;
        }

        if (backGestureHeightFractionRight.size() == 2) {
            topRight = backGestureHeightFractionRight.get(1) / 100f;
            bottomRight = backGestureHeightFractionRight.get(0) / 100f;
        } else {
            topRight = backGestureHeightFractionRight.get(0) / 100f;
            bottomRight = 0;
        }

        return isLeftSide ?
                y <= (mDisplaySize.y
                        - mBottomGestureHeight
                        - mDisplaySize.y * topLeft)
                        || y >= (mDisplaySize.y
                        - mBottomGestureHeight
                        - mDisplaySize.y * bottomLeft) :
                y <= (mDisplaySize.y
                        - mBottomGestureHeight
                        - mDisplaySize.y * topRight)
                        && y >= (mDisplaySize.y
                        - mBottomGestureHeight
                        - mDisplaySize.y * bottomRight);

        /*int mEdgeHeight = isLeftSide ?
                Math.round(mDisplaySize.y * backGestureHeightFractionLeft) :
                Math.round(mDisplaySize.y * backGestureHeightFractionRight);

        return mEdgeHeight != 0
                && y < (mDisplaySize.y
                - mBottomGestureHeight
                - mEdgeHeight);*/
    }
    //endregion

    private String[] getForegroundApp() {
        ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();

        String foregroundApp = null;
        String uid = null;
        for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
            if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                foregroundApp = processInfo.processName;
                uid = String.valueOf(processInfo.uid);
                break;
            }
        }
        return new String[]{foregroundApp, uid};
    }

    private void killForegroundApp() {
        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> {
            try {
                ActivityManager am = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
                List<ActivityManager.RunningAppProcessInfo> processes = am.getRunningAppProcesses();

                String foregroundApp = null;
                String[] appInfo = getForegroundApp();
                foregroundApp = appInfo[0];

                if (foregroundApp != null && !foregroundApp.equals(Constants.Packages.SYSTEM_UI) && !foregroundApp.equals(getDefaultLauncherPackageName())) {
                    //am.killBackgroundProcesses(foregroundApp);
                    if (ShellUtils.checkRootPermission()) {
                        String finalForegroundApp = foregroundApp;
                        XPLauncher.enqueueProxyCommand(proxy -> proxy.runCommand("killall " + finalForegroundApp));
                        XPLauncher.enqueueProxyCommand(proxy -> proxy.runCommand("am force-stop " + finalForegroundApp));
                    }
                    String appLabel = getApplicationLabel(foregroundApp, mContext.getPackageManager());
                    Toast.makeText(mContext, "Killed: " + appLabel, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(mContext, "Nothing to kill.", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable t) {
                Log.e("KillAppNow", "Error in killForegroundApp", t);
            }
        });
    }

    private void takeScreenshot() {

    }

    private void showQs() {
        if (TextUtils.isEmpty(QSExpandMethodName) || NotificationPanelViewController == null) return;

        try {
            callMethod(NotificationPanelViewController, QSExpandMethodName);
        } catch (Throwable t) {
            Log.e("ShowQs", "Error in showQs", t);
        }
    }

    private void showPowerMenu() {
        Intent broadcast = new Intent(Constants.ACTION_POWER_MENU);
        broadcast.setPackage(BuildConfig.APPLICATION_ID);
        mContext.sendBroadcast(broadcast);
    }

    private String getDefaultLauncherPackageName() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = mContext.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null) {
            return resolveInfo.activityInfo.packageName;
        }
        return null;
    }

    private String getApplicationLabel(String packageName, PackageManager pm) {
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            return (String) pm.getApplicationLabel(appInfo);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName; // Fallback to package name if the app label is not found
        }
    }

    @Override
    public boolean listensTo(String packageName) {
        return packageName.equals(listenPackage);
    }
}
