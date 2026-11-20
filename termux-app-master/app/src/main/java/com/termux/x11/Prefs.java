package com.termux.x11;

import java.util.HashMap;
import android.content.Context;
import android.content.SharedPreferences;
import com.termux.x11.utils.TermuxX11ExtraKeys;

import com.termux.R;

/** 由 preferences.xml 生成（等价于原工程 gradle generatePrefs 任务）。 */
public class Prefs extends LoriePreferences.PrefsProto {
    public final ListPreference displayResolutionMode = new ListPreference("displayResolutionMode", "native", R.array.displayResolutionVariants, R.array.displayResolutionVariants);
    public final IntPreference displayScale = new IntPreference("displayScale", 100);
    public final ListPreference displayResolutionExact = new ListPreference("displayResolutionExact", "1280x1024", R.array.displayResolution, R.array.displayResolution);
    public final StringPreference displayResolutionCustom = new StringPreference("displayResolutionCustom", "1280x1024");
    public final ListPreference displayFilteringMode = new ListPreference("displayFilteringMode", "bilinear", R.array.displayFilteringVariants, R.array.displayFilteringVariants);
    public final BooleanPreference adjustResolution = new BooleanPreference("adjustResolution", false);
    public final BooleanPreference displayStretch = new BooleanPreference("displayStretch", false);
    public final BooleanPreference Reseed = new BooleanPreference("Reseed", false);
    public final BooleanPreference PIP = new BooleanPreference("PIP", false);
    public final BooleanPreference fullscreen = new BooleanPreference("fullscreen", false);
    public final ListPreference forceOrientation = new ListPreference("forceOrientation", "auto", R.array.forceOrientationVariants, R.array.forceOrientationVariants);
    public final BooleanPreference hideCutout = new BooleanPreference("hideCutout", false);
    public final ListPreference screenIdleTimeout = new ListPreference("screenIdleTimeout", "system", R.array.screenIdleTimeoutEntries, R.array.screenIdleTimeoutValues);
    public final ListPreference touchMode = new ListPreference("touchMode", "1", R.array.touchscreenInputModesEntries, R.array.touchscreenInputModesValues);
    public final BooleanPreference scaleTouchpad = new BooleanPreference("scaleTouchpad", true);
    public final BooleanPreference showStylusClickOverride = new BooleanPreference("showStylusClickOverride", false);
    public final BooleanPreference stylusIsMouse = new BooleanPreference("stylusIsMouse", false);
    public final BooleanPreference stylusButtonContactModifierMode = new BooleanPreference("stylusButtonContactModifierMode", false);
    public final BooleanPreference showMouseHelper = new BooleanPreference("showMouseHelper", false);
    public final BooleanPreference pointerCapture = new BooleanPreference("pointerCapture", false);
    public final ListPreference transformCapturedPointer = new ListPreference("transformCapturedPointer", "no", R.array.transformCapturedPointerEntries, R.array.transformCapturedPointerValues);
    public final IntPreference capturedPointerSpeedFactor = new IntPreference("capturedPointerSpeedFactor", 100);
    public final BooleanPreference tapToMove = new BooleanPreference("tapToMove", true);
    public final BooleanPreference invertScrolling = new BooleanPreference("invertScrolling", false);
    public final BooleanPreference ignoreGamepadEvents = new BooleanPreference("ignoreGamepadEvents", false);
    public final BooleanPreference zoomFollowsCursor = new BooleanPreference("zoomFollowsCursor", true);
    public final BooleanPreference showAdditionalKbd = new BooleanPreference("showAdditionalKbd", false);
    public final BooleanPreference additionalKbdVisible = new BooleanPreference("additionalKbdVisible", true);
    public final BooleanPreference showIMEWhileExternalConnected = new BooleanPreference("showIMEWhileExternalConnected", true);
    public final BooleanPreference preferScancodes = new BooleanPreference("preferScancodes", false);
    public final BooleanPreference hardwareKbdScancodesWorkaround = new BooleanPreference("hardwareKbdScancodesWorkaround", true);
    public final BooleanPreference dexMetaKeyCapture = new BooleanPreference("dexMetaKeyCapture", false);
    public final BooleanPreference enableAccessibilityServiceAutomatically = new BooleanPreference("enableAccessibilityServiceAutomatically", false);
    public final BooleanPreference pauseKeyInterceptingWithEsc = new BooleanPreference("pauseKeyInterceptingWithEsc", false);
    public final BooleanPreference filterOutWinkey = new BooleanPreference("filterOutWinkey", false);
    public final BooleanPreference enforceCharBasedInput = new BooleanPreference("enforceCharBasedInput", false);
    public final BooleanPreference clipboardEnable = new BooleanPreference("clipboardEnable", true);
    public final BooleanPreference storeSecondaryDisplayPreferencesSeparately = new BooleanPreference("storeSecondaryDisplayPreferencesSeparately", false);
    public final BooleanPreference adjustHeightForEK = new BooleanPreference("adjustHeightForEK", true);
    public final BooleanPreference useTermuxEKBarBehaviour = new BooleanPreference("useTermuxEKBarBehaviour", true);
    public final ListPreference ekbarPosition = new ListPreference("ekbarPosition", "bottom", R.array.ekbarPositionValues, R.array.ekbarPositionValues);
    public final BooleanPreference ekbarPositionIgnoreOrientation = new BooleanPreference("ekbarPositionIgnoreOrientation", false);
    public final IntPreference opacityEKBar = new IntPreference("opacityEKBar", 100);
    public final ListPreference swipeUpAction = new ListPreference("swipeUpAction", "no action", R.array.userActionsValues, R.array.userActionsValues);
    public final ListPreference swipeDownAction = new ListPreference("swipeDownAction", "toggle additional key bar", R.array.userActionsValues, R.array.userActionsValues);
    public final ListPreference volumeUpAction = new ListPreference("volumeUpAction", "no action", R.array.userActionsVolumeUpValues, R.array.userActionsVolumeUpValues);
    public final ListPreference volumeDownAction = new ListPreference("volumeDownAction", "no action", R.array.userActionsVolumeDownValues, R.array.userActionsVolumeDownValues);
    public final ListPreference backButtonAction = new ListPreference("backButtonAction", "toggle soft keyboard", R.array.userActionsValues, R.array.userActionsValues);
    public final ListPreference notificationTapAction = new ListPreference("notificationTapAction", "open preferences", R.array.userActionsValues, R.array.userActionsValues);
    public final ListPreference notificationButton0Action = new ListPreference("notificationButton0Action", "open preferences", R.array.userActionsValues, R.array.userActionsValues);
    public final ListPreference notificationButton1Action = new ListPreference("notificationButton1Action", "exit", R.array.userActionsValues, R.array.userActionsValues);
    public final ListPreference mediaKeysAction = new ListPreference("mediaKeysAction", "no action", R.array.userActionsMediaKeyValues, R.array.userActionsMediaKeyValues);
    public final StringPreference extra_keys_config = new StringPreference("extra_keys_config", TermuxX11ExtraKeys.DEFAULT_IVALUE_EXTRA_KEYS);
    public final HashMap<String, Preference> keys = new HashMap<String, Preference>() {{
        put("displayResolutionMode", displayResolutionMode);
        put("displayScale", displayScale);
        put("displayResolutionExact", displayResolutionExact);
        put("displayResolutionCustom", displayResolutionCustom);
        put("displayFilteringMode", displayFilteringMode);
        put("adjustResolution", adjustResolution);
        put("displayStretch", displayStretch);
        put("Reseed", Reseed);
        put("PIP", PIP);
        put("fullscreen", fullscreen);
        put("forceOrientation", forceOrientation);
        put("hideCutout", hideCutout);
        put("screenIdleTimeout", screenIdleTimeout);
        put("touchMode", touchMode);
        put("scaleTouchpad", scaleTouchpad);
        put("showStylusClickOverride", showStylusClickOverride);
        put("stylusIsMouse", stylusIsMouse);
        put("stylusButtonContactModifierMode", stylusButtonContactModifierMode);
        put("showMouseHelper", showMouseHelper);
        put("pointerCapture", pointerCapture);
        put("transformCapturedPointer", transformCapturedPointer);
        put("capturedPointerSpeedFactor", capturedPointerSpeedFactor);
        put("tapToMove", tapToMove);
        put("invertScrolling", invertScrolling);
        put("ignoreGamepadEvents", ignoreGamepadEvents);
        put("zoomFollowsCursor", zoomFollowsCursor);
        put("showAdditionalKbd", showAdditionalKbd);
        put("additionalKbdVisible", additionalKbdVisible);
        put("showIMEWhileExternalConnected", showIMEWhileExternalConnected);
        put("preferScancodes", preferScancodes);
        put("hardwareKbdScancodesWorkaround", hardwareKbdScancodesWorkaround);
        put("dexMetaKeyCapture", dexMetaKeyCapture);
        put("enableAccessibilityServiceAutomatically", enableAccessibilityServiceAutomatically);
        put("pauseKeyInterceptingWithEsc", pauseKeyInterceptingWithEsc);
        put("filterOutWinkey", filterOutWinkey);
        put("enforceCharBasedInput", enforceCharBasedInput);
        put("clipboardEnable", clipboardEnable);
        put("storeSecondaryDisplayPreferencesSeparately", storeSecondaryDisplayPreferencesSeparately);
        put("adjustHeightForEK", adjustHeightForEK);
        put("useTermuxEKBarBehaviour", useTermuxEKBarBehaviour);
        put("ekbarPosition", ekbarPosition);
        put("ekbarPositionIgnoreOrientation", ekbarPositionIgnoreOrientation);
        put("opacityEKBar", opacityEKBar);
        put("swipeUpAction", swipeUpAction);
        put("swipeDownAction", swipeDownAction);
        put("volumeUpAction", volumeUpAction);
        put("volumeDownAction", volumeDownAction);
        put("backButtonAction", backButtonAction);
        put("notificationTapAction", notificationTapAction);
        put("notificationButton0Action", notificationButton0Action);
        put("notificationButton1Action", notificationButton1Action);
        put("mediaKeysAction", mediaKeysAction);
        put("extra_keys_config", extra_keys_config);
    }};

    public Prefs() {}

    @Override
    public void attach(Context ctx, SharedPreferences preferences) {
        super.attach(ctx, preferences);
        Object legacyKeepScreenOn = preferences.getAll().get("keepScreenOn");
        if (legacyKeepScreenOn instanceof Boolean && !preferences.contains("screenIdleTimeout"))
            preferences.edit().putString("screenIdleTimeout", (Boolean) legacyKeepScreenOn ? "never" : "system").commit();
    }
}
