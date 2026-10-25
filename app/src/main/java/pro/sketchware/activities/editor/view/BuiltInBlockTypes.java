package pro.sketchware.activities.editor.view;

import java.util.HashMap;
import java.util.Map;

/**
 * Block type of every block the Logic editor's palettes offer, by opcode, as listed by
 * {@code logicEditor.a(type, opcode)} in ExtraPaletteBlock, BlocksHandler and LogicEditorActivity.
 * Used by {@link JavaToBlocks} to know which blocks exist and whether they are statements or reporters.
 * Blocks added to the palettes later are still converted if they are Custom Blocks; built-in ones need
 * an entry here.
 */
final class BuiltInBlockTypes {

    static final Map<String, String> TYPES = new HashMap<>();

    static {
        // statements
        put(" ",
                "AsyncTaskExecute", "AsyncTaskPublishProgress", "Dialog SetIcon", "EditTextLines",
                "EditTextSelectAll", "EditTextSetMaxLines", "EditTextSetSelection", "EditTextShowError",
                "EditTextSingleLine", "EditTextdiableSuggestion", "GsonStringToListNumber",
                "GsonStringToListString", "LightStatusBar", "ListViewAddFooter", "ListViewAddHeader",
                "ViewPagerNotifyOnDtatChange", "YTPVLifecycle", "addCustomVariable", "addInitializer",
                "addListInt", "addListMap", "addListStr", "addMapToList", "addPermission",
                "addSourceDirectly", "addTab", "addView", "addViews", "autoComSetData", "bannerAdViewLoadAd",
                "bluetoothConnectActivateBluetooth", "bluetoothConnectGetPairedDevices",
                "bluetoothConnectReadyConnection", "bluetoothConnectReadyConnectionToUuid",
                "bluetoothConnectSendData", "bluetoothConnectStartConnection",
                "bluetoothConnectStartConnectionToUuid", "bluetoothConnectStopConnection",
                "bottomMenuAddItem", "calendarAdd", "calendarGetNow", "calendarSet", "calendarSetTime",
                "calendarViewSetDate", "calendarViewSetMinDate", "calnedarViewSetMaxDate",
                "camerastarttakepicture", "caseNumAnd", "caseStrAnd", "changeStatebarColour", "clearList",
                "closeDrawer", "codeviewApply", "codeviewSetCode", "codeviewSetLanguage", "codeviewSetTheme",
                "copyToClipboard", "cropBitmapFileFromCenter", "customImport", "customImport2",
                "customToast", "customToastWithIcon", "datePickerDialogShow", "decreaseInt", "deleteList",
                "deleteMapFromListmap", "dialogSetMessage", "dialogSetTitle", "dialogShow", "doToast",
                "fabIcon", "fabSize", "fabVisibility", "fileRemoveData", "fileSetData",
                "filepickerstartpickfiles", "fileutilcopy", "fileutilcopydir", "fileutildelete",
                "fileutillistdir", "fileutilmakedir", "fileutilmove", "fileutilwrite", "firebaseAdd",
                "firebaseDelete", "firebasePush", "firebaseStartListen", "firebaseStopListen",
                "firebaseauthCreateUser", "firebaseauthResetPassword", "firebaseauthSignInAnonymously",
                "firebaseauthSignInUser", "firebaseauthSignOutUser", "firebasestorageDelete",
                "firebasestorageDownloadFile", "firebasestorageUploadFile", "getAssetFile", "getMapInList",
                "gridSetColumnWidth", "gridSetCustomViewData", "gridSetHorizontalSpacing",
                "gridSetNumColumns", "gridSetStretchMode", "gridSetVerticalSpacing", "gyroscopeStartListen",
                "gyroscopeStopListen", "hashmapPutBoolean", "hashmapPutListmap", "hashmapPutListstr",
                "hashmapPutMap", "hashmapPutNumber", "hashmapPutNumber2", "hideKeyboard", "imageCrop",
                "increaseInt", "insertListInt", "insertListMap", "insertListStr", "insertMapToList",
                "intentPutExtra", "intentRemoveExtra", "intentSetAction", "intentSetData", "intentSetFlags",
                "intentSetScreen", "intentSetType", "interstitialAdLoad", "interstitialAdShow", "launchApp",
                "listAddAll", "listRefresh", "listSetCustomViewData", "listSetData",
                "listSetStackFromBottom", "listSetTranscriptMode", "listSmoothScrollTo",
                "listViewRemoveFooter", "listViewRemoveHeader", "listViewSetSelection",
                "locationManagerRemoveUpdates", "locationManagerRequestLocationUpdates",
                "lottieSetAnimationFromAsset", "lottieSetAnimationFromJson", "lottieSetAnimationFromUrl",
                "lottieSetRepeatCount", "lottieSetSpeed", "mapClear", "mapCreateNew", "mapGetAllKeys",
                "mapPut", "mapRemoveKey", "mapViewAddMarker", "mapViewMoveCamera", "mapViewSetMapType",
                "mapViewSetMarkerColor", "mapViewSetMarkerIcon", "mapViewSetMarkerInfo",
                "mapViewSetMarkerPosition", "mapViewSetMarkerVisible", "mapViewZoomIn", "mapViewZoomOut",
                "mapViewZoomTo", "mediaplayerCreate", "mediaplayerPause", "mediaplayerRelease",
                "mediaplayerReset", "mediaplayerSeek", "mediaplayerSetLooping", "mediaplayerStart",
                "menuAddItem", "menuAddMenuItem", "menuInflater", "multiAutoComSetData",
                "notifyDataSetChanged", "objectanimatorCancel", "objectanimatorSetDuration",
                "objectanimatorSetFromTo", "objectanimatorSetInterpolator", "objectanimatorSetProperty",
                "objectanimatorSetRepeatCount", "objectanimatorSetRepeatMode", "objectanimatorSetTarget",
                "objectanimatorSetValue", "objectanimatorStart", "openDrawer", "otpViewSetFieldCount",
                "otpViewSetOTPText", "pagerSetCurrentItem", "pagerSetCustomViewData",
                "pagerSetFragmentAdapter", "pagerSetOffscreenPageLimit", "patternLockClear",
                "patternSetCorrectStateColor", "patternSetDotCount", "patternSetNormalStateColor",
                "patternSetViewMode", "patternSetWrongStateColor", "performClick",
                "progressBarSetIndeterminate", "progressdialogCreate", "progressdialogDismiss",
                "progressdialogSetCancelable", "progressdialogSetCanceledOutside", "progressdialogSetMax",
                "progressdialogSetMessage", "progressdialogSetProgress", "progressdialogSetStyle",
                "progressdialogSetTitle", "progressdialogShow", "recyclerScrollToPositionWithOffset",
                "recyclerSetCustomViewData", "recyclerSetHasFixedSize", "recyclerSetLayoutManager",
                "recyclerSetLayoutManagerHorizontal", "recyclerSmoothScrollToPosition", "refreshingList",
                "removePermission", "removeView", "removeViews", "renameFile", "requestFocus",
                "requestnetworkSetHeaders", "requestnetworkSetParams", "requestnetworkStartRequestNetwork",
                "resizeBitmapFileRetainRatio", "resizeBitmapFileToCircle", "resizeBitmapFileToSquare",
                "resizeBitmapFileWithRoundedBorder", "reverseList", "rewardedVideoAdLoad",
                "rewardedVideoAdShow", "rotateBitmapFile", "scaleBitmapFile", "seekBarSetMax",
                "seekBarSetProgress", "sendBroadcast", "setAlpha", "setAtPosListnum", "setAtPosListstr",
                "setBadgeBackground", "setBadgeNumber", "setBadgeString", "setBadgeTextColor",
                "setBadgeTextSize", "setBgColor", "setBgDrawable", "setBgResource",
                "setBitmapFileBrightness", "setBitmapFileColorFilter", "setBitmapFileContrast",
                "setBubbleArrowHeight", "setBubbleArrowPosition", "setBubbleArrowWidth", "setBubbleColor",
                "setBubbleCornerRadius", "setBubbleStrokeColor", "setBubbleStrokeWidth",
                "setCardBackgroundColor", "setCardElevation", "setCardRadius", "setChecked",
                "setColorFilter", "setColorFilterView", "setCornerRadiusView", "setCustomLetter",
                "setElevation", "setEnable", "setGradientBackground", "setGravity", "setHint",
                "setHintTextColor", "setImage", "setImageCustomRes", "setImageFilePath",
                "setImageIdentifier", "setImageUrl", "setInlineLabel", "setListMap", "setMapAtPosListmap",
                "setNumStars", "setPreventCornerOverlap", "setRadiusAndStrokeView", "setRating",
                "setRecyclerViewLayoutParams", "setRefreshing", "setRotate", "setScaleX", "setScaleY",
                "setSelectedTabIndicatorColor", "setSelectedTabIndicatorHeight", "setStepSize",
                "setStrokeView", "setTabRippleColor", "setTabTextColors", "setText", "setTextColor",
                "setTextSize", "setThreshold", "setTitle", "setTokenizer", "setTranslationX",
                "setTranslationY", "setTypeface", "setUseCompatPadding", "setVarBoolean", "setVarInt",
                "setVarString", "setVisible", "setupWithViewPager", "showKeyboard", "shuffleList",
                "signInButtonSetColorScheme", "signInButtonSetSize", "skewBitmapFile", "sortList",
                "sortListmap", "sortListnum", "soundpoolCreate", "soundpoolStreamStop",
                "speechToTextShutdown", "speechToTextStartListening", "speechToTextStopListening",
                "spnRefresh", "spnSetCustomViewData", "spnSetData", "spnSetSelection", "startActivity",
                "startActivityWithChooser", "startService", "stopService", "strToListMap", "strToMap",
                "stringSplitToList", "submenuAddItem", "swapInList", "textToSpeechSetPitch",
                "textToSpeechSetSpeechRate", "textToSpeechShutdown", "textToSpeechSpeak", "textToSpeechStop",
                "tilSetBoxBgColor", "tilSetBoxBgMode", "tilSetBoxCornerRadii", "tilSetBoxStrokeColor",
                "tilSetCounterEnabled", "tilSetCounterMaxLength", "tilSetError", "tilSetErrorEnabled",
                "timePickerDialogShow", "timepickerSetCurrentHour", "timepickerSetCurrentMinute",
                "timepickerSetHour", "timepickerSetIs24Hour", "timepickerSetMinute", "timerCancel",
                "vibratorAction", "videoviewPause", "videoviewSetVideoUri", "videoviewStart",
                "videoviewStop", "webViewClearCache", "webViewClearHistory", "webViewGoBack",
                "webViewGoForward", "webViewLoadUrl", "webViewSetCacheMode", "webViewStopLoading",
                "webViewZoomIn", "webViewZoomOut");
        // maps
        put("a",
                "getMapAtPosListmap", "hashmapGetMap");
        // booleans
        put("b",
                "&&", "<", "=", ">", "asdBoolean", "bluetoothConnectIsBluetoothActivated",
                "bluetoothConnectIsBluetoothEnabled", "checkViewVisibility", "checkboxIsChecked",
                "containListInt", "containListMap", "containListStr", "false", "fileContainsData",
                "fileutilEndsWith", "fileutilStartsWith", "fileutilisdir", "fileutilisexist",
                "fileutilisfile", "firebaseauthIsLoggedIn", "getChecked", "getEnable", "hashmapGetBoolean",
                "instanceOfOperator", "intentHasExtra", "interstitialAdIsLoaded", "isConnected",
                "isDrawerOpen", "isEmpty", "mapContainKey", "mapContainValue", "mapIsEmpty",
                "mediaplayerIsLooping", "mediaplayerIsPlaying", "not", "objectanimatorIsRunning",
                "stringContains", "stringEquals", "stringMatches", "textToSpeechIsSpeaking", "true",
                "videoviewCanPause", "videoviewCanSeekBackward", "videoviewCanSeekForward",
                "videoviewIsPlaying", "webViewCanGoBack", "webViewCanGoForward", "||");
        // control blocks with one slot
        put("c",
                "CommandBlockJava", "RepeatKnownNumDescending", "YTPVSetListener", "caseNum", "caseStr",
                "checkboxOnChecked", "defaultSwitch", "dialogCancelButton", "dialogNeutralButton",
                "dialogOkButton", "firebaseGetChildren", "forever", "if", "menuAddSubmenu",
                "onSwipeRefreshLayout", "otpViewSetOTPListener", "repeat", "repeatKnownNum", "showSnackbar",
                "switchNum", "switchStr", "timerAfter", "timerEvery", "viewOnClick", "viewOnLongClick",
                "viewOnTouch", "whileLoop");
        // numbers
        put("d",
                "%", "*", "+", "-", "/", "EdittextGetselectionEnd", "EdittextGetselectionStart", "asdNumber",
                "calendarDiff", "calendarGetTime", "fileutillength", "getAlpha", "getAtListInt",
                "getBadgeCount", "getHeight", "getJpegRotate", "getLastVisiblePosition", "getLocationX",
                "getLocationY", "getRating", "getRotate", "getScaleX", "getScaleY", "getTranslationX",
                "getTranslationY", "getWidth", "hashmapGetNumber", "indexListInt", "indexListStr",
                "lengthList", "listscrollparam", "mapSize", "mathAbs", "mathAcos", "mathAsin", "mathAtan",
                "mathCeil", "mathCos", "mathE", "mathExp", "mathFloor", "mathGetDip", "mathGetDisplayHeight",
                "mathGetDisplayWidth", "mathLog", "mathLog10", "mathMax", "mathMin", "mathPi", "mathPow",
                "mathRound", "mathSin", "mathSqrt", "mathTan", "mathToDegree", "mathToRadian",
                "mediaplayerGetCurrent", "mediaplayerGetDuration", "pagerGetCurrentItem",
                "pagerGetOffscreenPageLimit", "pagerscrollparam", "random", "recyclerscrollparam",
                "seekBarGetMax", "seekBarGetProgress", "soundpoolLoad", "soundpoolStreamPlay",
                "spnGetSelection", "strParseInteger", "stringIndex", "stringLastIndex", "stringLength",
                "ternaryNumber", "tilGetCounterMaxLength", "toHashCode", "toNumber",
                "videoviewGetCurrentPosition", "videoviewGetDuration", "webviewGetProgress");
        // control blocks with two slots
        put("e",
                "ifElse", "tryCatch");
        // final statements
        put("f",
                "break", "continue", "finishActivity", "finishAffinity", "returnBoolean", "returnFragment",
                "returnListMap", "returnListStr", "returnMap", "returnNumber", "returnString", "returnTitle",
                "returnView");
        // strings
        put("s",
                "GsonListTojsonString", "asdString", "bluetoothConnectGetRandomUuid", "calendarFormat",
                "copyAssetFile", "fileGetData", "fileutilGetLastSegmentPath", "fileutilread",
                "firebaseGetPushKey", "firebaseauthGetCurrentUser", "firebaseauthGetUid", "getAtListMap",
                "getAtListStr", "getExternalStorageDir", "getPackageDataDir", "getPublicDir", "getResString",
                "getText", "html", "intentGetString", "listMapToStr", "mapGet", "mapToStr",
                "otpViewGetOTPText", "patternToMD5", "patternToSha1", "patternToString", "reverse",
                "stringJoin", "stringReplace", "stringReplaceAll", "stringReplaceFirst", "stringSub",
                "stringSubSingle", "ternaryString", "toLowerCase", "toString", "toStringFormat",
                "toStringWithDecimal", "toUpperCase", "trim", "webViewGetUrl");
    }

    private static void put(String type, String... opcodes) {
        for (String opcode : opcodes) {
            TYPES.put(opcode, type);
        }
    }

    private BuiltInBlockTypes() {
    }
}
