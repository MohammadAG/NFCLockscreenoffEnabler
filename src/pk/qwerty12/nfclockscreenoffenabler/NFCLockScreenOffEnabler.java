package pk.qwerty12.nfclockscreenoffenabler;

import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.XModuleResources;
import android.media.SoundPool;
import android.nfc.NfcAdapter;
import android.util.Log;
import de.robv.android.xposed.IXposedHookCmdInit;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedHelpers.ClassNotFoundError;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class NFCLockScreenOffEnabler implements IXposedHookZygoteInit, IXposedHookLoadPackage, IXposedHookCmdInit {
	// Thanks to Tungstwenty for the preferences code, which I have taken from his Keyboard42DictInjector and made a bad job of it
	private static final String MY_PACKAGE_NAME = NFCLockScreenOffEnabler.class.getPackage().getName();
	private String MODULE_PATH;

	private SharedPreferences prefs;
	private Context mContext = null;

	private XModuleResources modRes = null;
	private SoundPool mSoundPool = null;
	private Object nfcServiceObject = null;

	// TODO: Get this dynamically from NfcService class
	// Taken from NfcService.java, Copyright (C) 2010 The Android Open Source Project, Licensed under the Apache License, Version 2.0
	// Screen state, used by mScreenState
	//private static final int SCREEN_STATE_OFF = 1;
	private static final int SCREEN_STATE_ON_LOCKED = 2;
	private static final int SCREEN_STATE_ON_UNLOCKED = 3;
	private static final int SOUND_START = 0;
	private static final int SOUND_END = 1;
	private static final int SOUND_ERROR = 2;

	int mTagLostSound;

	private boolean mDebugMode = true;
	private static Set<String> mSoundsToPlayList;
	protected Object mViewMediatorCallback;

	// Prevent multiple registers.
	private boolean mBroadcastReceiverRegistered = false;
	private boolean mIsOemStupid;

	private static Object mKeyguardSecurityCallbackInstance;

	private void log(String TAG, String message) {
		if (!mDebugMode)
			return;

		Log.d(TAG, message);
	}

	// Hook for NfcNativeTag$PresenceCheckWatchdog.run()
	class PresenceCheckWatchdogRunHook extends XC_MethodHook {
		private static final String TAG = "PresenceCheckWatchdogRunHook";

		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			if (!prefs.getBoolean(Common.PREF_TAGLOST, true))
				return;

			XposedHelpers.callMethod(param.thisObject, "setTimeout",
					Integer.parseInt(prefs.getString(Common.PREF_PRESENCE_CHECK_TIMEOUT,
							"2000")));
		}

		@Override
		protected void afterHookedMethod(MethodHookParam param) throws Throwable {
			if (!prefs.getBoolean(Common.PREF_TAGLOST, true))
				return;

			// broadcast tag lost message
			try {  
				Class<?> activityManagerNative = Class.forName("android.app.ActivityManagerNative");  

				if (activityManagerNative != null) {
					Object am = activityManagerNative.getMethod("getDefault").invoke(activityManagerNative);  

					if (am != null) {
						am.getClass().getMethod("resumeAppSwitches").invoke(am);
					}
				}
			} catch (Exception e) {  
				e.printStackTrace();  
			}  

			Context context = (Context) XposedHelpers.getAdditionalInstanceField(XposedHelpers.getSurroundingThis(param.thisObject), "mContext");

			if (context == null) {
				log(TAG,  "step-4 context == null");
				return;
			}

			try {
				byte[] uId = (byte[]) XposedHelpers.callMethod(XposedHelpers.getSurroundingThis(param.thisObject), "getUid");

				Common.sendTagChangedBroadcast(context, uId, false);

				Intent intentToStart = new Intent(Common.ACTION_TAG_LOST);
				intentToStart.putExtra(NfcAdapter.EXTRA_ID, uId);
				intentToStart.putExtra(Common.EXTRA_ID_STRING, Common.byteArrayToHexString(uId));

				Common.sendBroadcast(context, intentToStart);

				intentToStart.setData(null);
				intentToStart.setType(null);
				intentToStart.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);

				PackageManager packageManager = context.getPackageManager();

				if (packageManager != null) {
					List<ResolveInfo> activities = packageManager.queryIntentActivities(intentToStart, 0);
					if (activities.size() > 0) {
						log(TAG, String.format("startActivity - android.nfc.action.TAG_LOST(%x%x%x%x)",
								uId[0], uId[1], uId[2], uId[3]));
						context.startActivity(intentToStart);
					} else {
						log(TAG,  String.format("activities.size() <= 0 (%x%x%x%x)",
								uId[0], uId[1], uId[2], uId[3]));
					}
				}

				playTagLostSound();
			} catch (Exception e) {  
				e.printStackTrace();  
			}  
		}
	}

	//Hook for NfcService.onRemoteEndpointDiscovered(TagEndpoint tag)
	class NfcServiceOnRemoteEndpointDiscoveredHook extends XC_MethodHook {
		@Override
		protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
			try {
				byte[] uuid = (byte[]) XposedHelpers.callMethod(param.args[0], "getUid");
				String uuidString = Common.byteArrayToHexString(uuid);

				Set<String> authorizedNfcTags = prefs.getStringSet(Common.PREF_NFC_KEYS,
						new HashSet<String>());
				Context context = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
				if (mDebugMode)
					XposedBridge.log(uuidString.trim());

				if (context != null) {
					Common.sendTagChangedBroadcast(context, uuid, true);

					if (authorizedNfcTags != null && authorizedNfcTags.contains(uuidString.trim())) {
						if (mDebugMode)
							XposedBridge.log("Got matching NFC tag, unlocking device...");
						context.sendBroadcast(new Intent(Common.INTENT_UNLOCK_DEVICE));
					}

					if (!prefs.getBoolean(Common.PREF_TAGLOST, true))
						return;

					XposedHelpers.setAdditionalInstanceField(param.args[0], "mContext", context);
				}
			} catch (Exception e) {  
				e.printStackTrace();  
			}  
		}
	}

	// Thanks to rovo89 for his suggested improvements: http://forum.xda-developers.com/showpost.php?p=35790508&postcount=185
	@Override
	public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
		prefs = AndroidAppHelper.getSharedPreferencesForPackage(MY_PACKAGE_NAME,
				Common.PREFS, Context.MODE_PRIVATE);
		MODULE_PATH = startupParam.modulePath;
		mDebugMode = prefs.getBoolean(Common.PREF_DEBUG_MODE, true);

		try {
			Class<?> ContextImpl = findClass("android.app.ContextImpl", null);

			XC_MethodHook hook = new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					// ContextImpl extends Context
					Context context = (Context) param.thisObject;
					Intent intent = (Intent) param.args[0];
					if (context != null) {
						if (intent != null && Common.INTENT_UNLOCK_DEVICE.equals(intent.getAction())) {
							if (!context.getPackageName().equals(Common.PACKAGE_NFC)) {
								param.args[0] = new Intent(Common.INTENT_UNLOCK_INTERCEPTED);
							}
						}
					}
				}
			};

			try {
				findAndHookMethod(ContextImpl, "sendBroadcast", Intent.class, hook);
				findAndHookMethod(ContextImpl, "sendBroadcast", Intent.class, String.class, hook);
			} catch (NoSuchMethodError e) {}

			// Doesn't exist on pre-4.2
			int currentapiVersion = android.os.Build.VERSION.SDK_INT;
			if (currentapiVersion >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
				try {
					Class<?> UserHandle = findClass("android.os.UserHandle", null);

					if (UserHandle != null) {
						try {
							findAndHookMethod(ContextImpl, "sendBroadcastAsUser", Intent.class, UserHandle, hook);
							findAndHookMethod(ContextImpl, "sendBroadcastAsUser", Intent.class, UserHandle, String.class, hook);
						} catch (NoSuchMethodError e) {}
					}
				} catch (ClassNotFoundError e) {}
			}
		} catch (ClassNotFoundError e) {}
	}

	@Override
	public void initCmdApp(de.robv.android.xposed.IXposedHookCmdInit.StartupParam startupParam) 
			throws Throwable {
		if (!startupParam.startClassName.equals("com.android.commands.am.Am"))
			return;

		Class<?> Am = findClass("com.android.commands.am.Am", null);
		Method sendBroadcastMethod = null;
		try {
			sendBroadcastMethod = XposedHelpers.findMethodBestMatch(Am, "makeIntent", int.class);
		} catch (NoSuchMethodError e) {
			try {
				sendBroadcastMethod = XposedHelpers.findMethodBestMatch(Am, "makeIntent");
			} catch (NoSuchMethodError e1) {}
		}

		if (sendBroadcastMethod != null) {
			XposedBridge.hookMethod(sendBroadcastMethod, new AmInterceptHook());
		}
	}

	public void playTagLostSound() {
		if (!mSoundsToPlayList.contains("sound_taglost"))
			return;

		synchronized (nfcServiceObject) {
			if (mSoundPool == null) {
				if (mDebugMode) {
					Log.w("NfcService", "Not playing sound when NFC is disabled");
				}
				return;
			}

			mSoundPool.play(mTagLostSound, 1.0f, 1.0f, 0, 0, 1.0f);
		}
	}

	private void reloadSoundsToPlayList() {
		HashSet<String> defaultSounds = new HashSet<String>();
		defaultSounds.add("sound_start");
		defaultSounds.add("sound_error");
		defaultSounds.add("sound_end");
		mSoundsToPlayList = prefs.getStringSet(Common.PREF_SOUNDS_TO_PLAY,
				defaultSounds);
	}

	@Override
	public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
		if (lpparam.packageName.equals(Common.PACKAGE_NFC)) {
			modRes = XModuleResources.createInstance(MODULE_PATH, null);
			reloadSoundsToPlayList();

			Class<?> NfcService = null;

			// Fuck you LG
			mIsOemStupid = false;
			try {
				NfcService = findClass(Common.PACKAGE_NFC + ".LNfcService", lpparam.classLoader);
				mIsOemStupid = true;
			} catch (ClassNotFoundError e) {
				e.printStackTrace();
			}

			if (NfcService == null) {
				try {
					NfcService = findClass(Common.PACKAGE_NFC + ".NfcService", lpparam.classLoader);
				} catch (ClassNotFoundError e) {
					// Shouldn't happen
				}
			}

			// Don't reload settings on every call, that can cause slowdowns.
			// This intent is fired from NFCLockScreenOffEnablerActivity when
			// any of the parameters change.
			XC_MethodHook initNfcServiceHook = new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					nfcServiceObject = param.thisObject;

					mContext = (Context) XposedHelpers.getObjectField(param.thisObject, "mContext");
					mContext.registerReceiver(new BroadcastReceiver() {

						@Override
						public void onReceive(Context context, Intent intent) {
							XposedBridge.log(MY_PACKAGE_NAME + ": " + "Settings updated, reloading...");
							AndroidAppHelper.reloadSharedPreferencesIfNeeded(prefs);

							// This may be faster than using prefs.getBoolean, since we use this a lot.
							mDebugMode = prefs.getBoolean(Common.PREF_DEBUG_MODE, true);
							reloadSoundsToPlayList();
						}
					}, new IntentFilter(Common.SETTINGS_UPDATED_INTENT));
				}
			};

			boolean hookedSuccessfully = true;
			try {
				Constructor<?> NfcServiceConstructor =
						XposedHelpers.findConstructorBestMatch(NfcService, Application.class);

				XposedBridge.hookMethod(NfcServiceConstructor, initNfcServiceHook);
			} catch (NoSuchMethodError e) {
				hookedSuccessfully = false;
			}

			if (!hookedSuccessfully) {
				try {
					findAndHookMethod(NfcService, "onCreate", initNfcServiceHook);
				} catch (NoSuchMethodError e) {
					e.printStackTrace();
				}
			}

			try {
				findAndHookMethod(NfcService, "initSoundPool", new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) throws Throwable {
						mSoundPool = (SoundPool) XposedHelpers.getObjectField(param.thisObject, "mSoundPool");
						synchronized (param.thisObject) {
							if (mSoundPool != null) {
								mTagLostSound =
										mSoundPool.load(modRes.openRawResourceFd(R.raw.tag_lost), 1);
							}
						}
					}
				});
			} catch (NoSuchMethodError e) {
				e.printStackTrace();
			}

			try {
				findAndHookMethod(NfcService, "playSound", int.class, new XC_MethodHook() {
					@Override
					protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
						int event = (Integer) param.args[0];
						if ((event == SOUND_START && !mSoundsToPlayList.contains("sound_start"))
								|| (event == SOUND_END && !mSoundsToPlayList.contains("sound_end"))
								|| (event == SOUND_ERROR && !mSoundsToPlayList.contains("sound_error"))) {
							param.setResult(false);
						}
					}
				});
			} catch (NoSuchMethodError e) {
				e.printStackTrace();
			}

			XC_MethodHook checkScreenStateHook = new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					final String enableNfcWhen = prefs.getString(Common.PREF_ENABLE_NFC_WHEN, "locked_screen_on");
					if (enableNfcWhen.equals("unlocked"))
						return;

					try {
						Boolean NeedScreenOnState = (Boolean)XposedHelpers.getAdditionalInstanceField(param.thisObject, "NeedScreenOnState") ;
						if (NeedScreenOnState == null || NeedScreenOnState == false)
							return;

						param.setResult(SCREEN_STATE_ON_UNLOCKED);
					} catch (Exception e) {  
						e.printStackTrace();  
					}  
				}
			};

			// Nfc module of some kinds of ROMs may call checkScreenState in applyRouting
			// and update mScreenState, so we have to hook checkScreenState and modify
			// the return value
			try {
				findAndHookMethod(NfcService, "checkScreenState", checkScreenStateHook);
			} catch (NoSuchMethodError e) {
				try {
					findAndHookMethod(Common.PACKAGE_NFC + ".NfcService", lpparam.classLoader,
							"checkScreenState", checkScreenStateHook);
				} catch (NoSuchMethodError e1) {
					e1.printStackTrace();
				}
			}

			try {
				if (mIsOemStupid) {
					/* The subject seems to have shown signs of intelligence here.
					 * LG's implementation of NFC supports NFC while screen is off/locked.
					 * This might be because of their weird NFC sending feature, or not.
					 */
					findAndHookMethod(NfcService, "applyRouting", boolean.class, new XC_MethodHook() {
						protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
							final String enableNfcWhen = prefs.getString(Common.PREF_ENABLE_NFC_WHEN, "locked_screen_on");

							if (enableNfcWhen.equals("unlocked")) {
								XposedHelpers.setIntField(param.thisObject, "POLLING_MODE", 0);
							} else if (enableNfcWhen.equals("locked_screen_on")) {
								XposedHelpers.setIntField(param.thisObject, "POLLING_MODE", 2);
							} else if (enableNfcWhen.equals("screen_off")) {
								XposedHelpers.setIntField(param.thisObject, "POLLING_MODE", 1);
							}
						};
					});
				} else {
					findAndHookMethod(NfcService, "applyRouting", boolean.class, new XC_MethodHook() {
						@Override
						protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
							final String enableNfcWhen = prefs.getString(Common.PREF_ENABLE_NFC_WHEN, "locked_screen_on");
							if (enableNfcWhen.equals("unlocked"))
								return;
							try {	
								final int currScreenState = (Integer) XposedHelpers.callMethod(param.thisObject, "checkScreenState");
								// We also don't need to run if the screen is already on, or if the user
								// has chosen to enable NFC on the lockscreen only and the phone is not locked
								if ((currScreenState == SCREEN_STATE_ON_UNLOCKED)
										|| (enableNfcWhen.equals("locked_screen_on")
												&& currScreenState != SCREEN_STATE_ON_LOCKED)) {
									XposedHelpers.setAdditionalInstanceField(param.thisObject, "mOrigScreenState", -1);
									return;
								}

								// we are in applyRouting, set the flag NeedScreenOnState to true
								XposedHelpers.setAdditionalInstanceField(param.thisObject, "NeedScreenOnState", true);

								synchronized (param.thisObject) { // Not sure if this is correct, but NfcService.java insists on having accesses to the mScreenState variable synchronized, so I'm doing the same here
									XposedHelpers.setAdditionalInstanceField(param.thisObject, "mOrigScreenState", XposedHelpers.getIntField(param.thisObject, "mScreenState"));
									XposedHelpers.setIntField(param.thisObject, "mScreenState", SCREEN_STATE_ON_UNLOCKED);
								}
							} catch (Exception e) {  
								e.printStackTrace();  
							}  
						}

						@Override
						protected void afterHookedMethod(MethodHookParam param) throws Throwable {
							try {
								final String enableNfcWhen = prefs.getString(Common.PREF_ENABLE_NFC_WHEN, "locked_screen_on");
								if (enableNfcWhen.equals("unlocked"))
									return;

								// exit from applyRouting, set the flag NeedScreenOnState to false
								XposedHelpers.setAdditionalInstanceField(param.thisObject, "NeedScreenOnState", false);

								final int mOrigScreenState = (Integer) XposedHelpers.getAdditionalInstanceField(param.thisObject, "mOrigScreenState");
								if (mOrigScreenState == -1)
									return;

								synchronized (param.thisObject) {
									// Restore original mScreenState value after applyRouting has run
									XposedHelpers.setIntField(param.thisObject, "mScreenState", mOrigScreenState);
								}
							} catch (Exception e) {  
								e.printStackTrace();  
							}  
						}
					});
				}
			} catch (NoSuchMethodError e) {
				e.printStackTrace();
			}

			try {
				findAndHookMethod(NfcService, "onRemoteEndpointDiscovered",
						Common.PACKAGE_NFC + ".DeviceHost$TagEndpoint",
						new NfcServiceOnRemoteEndpointDiscoveredHook());
			} catch (ClassNotFoundError e) {
				e.printStackTrace();
			}


			try {
				Class<?> PresenceCheckWatchDog = findClass(Common.PACKAGE_NFC + ".dhimpl.NativeNfcTag$PresenceCheckWatchdog", lpparam.classLoader);
				findAndHookMethod(PresenceCheckWatchDog, "run", new PresenceCheckWatchdogRunHook());
			} catch (ClassNotFoundError e) {
				if (mDebugMode)
					XposedBridge.log("Not hooking class .dhimpl.NativeNfcTag$PresenceCheckWatchdog");
			}

			try {
				Class<?> PresenceCheckWatchdog = findClass(Common.PACKAGE_NFC +".nxp.NativeNfcTag$PresenceCheckWatchdog", lpparam.classLoader);
				findAndHookMethod(PresenceCheckWatchdog, "run", new PresenceCheckWatchdogRunHook());
			} catch (ClassNotFoundError e) {
				if (mDebugMode)
					XposedBridge.log("Not hooking class .nxp.NativeNfcTag$PresenceCheckWatchdog");
			}
		}

		if (lpparam.packageName.equals("android")) {
			final int currentapiVersion = android.os.Build.VERSION.SDK_INT;
			try {
				String className;

				if (currentapiVersion < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
					className = "com.android.internal.policy.impl.LockPatternKeyguardView";
				} else if (currentapiVersion < android.os.Build.VERSION_CODES.KITKAT) {
					className = "com.android.internal.policy.impl.keyguard.KeyguardHostView";
				} else {
					className = "com.android.keyguard.KeyguardHostView";
				}

				Class<?> ClassToHook = findClass(className, lpparam.classLoader);

				// public KeyguardHostView(Context context, AttributeSet attrs)
				// public LockPatternKeyguardView(Context context, KeyguardViewCallback callback,
				//        KeyguardUpdateMonitor updateMonitor, LockPatternUtils lockPatternUtils,
				//        KeyguardWindowController controller)

				// Class KeyguardHostView on 4.2+ has mCallback of type KeyguardSecurityCallback,
				// while 4.1- has mKeyguardScreenCallback of type KeyguardScreenCallback.
				// Both of which have a method called reportSuccessfulUnlockAttempt() that
				// should be the first step in unlocking the device.

				XposedBridge.hookAllConstructors(ClassToHook, new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) throws Throwable {
						String fieldName;
						if (currentapiVersion < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
							fieldName = "mKeyguardScreenCallback";
						} else {
							fieldName = "mCallback";
						}

						try {
							mKeyguardSecurityCallbackInstance = 
									getObjectField(param.thisObject, fieldName);
						} catch (NoSuchFieldError e) {}

						Context context = (Context) param.args[0];
						registerNfcUnlockReceivers(context);
					}
				});
			} catch (ClassNotFoundError e) {
			} catch (NoSuchFieldError e) {}


			// The classes and field names were renamed and moved around between 4.1 and 4.2,
			// the bits we're interested in stayed the same though.
			try {
				String className;		
				if (currentapiVersion < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
					className = "com.android.internal.policy.impl.KeyguardViewManager";
				} else if (currentapiVersion < android.os.Build.VERSION_CODES.KITKAT) {
					className = "com.android.internal.policy.impl.keyguard.KeyguardViewManager";
				} else {
					className = "com.android.keyguard.KeyguardViewManager";
				}
				Class<?> KeyguardViewManager = findClass(className, lpparam.classLoader);
				XposedBridge.hookAllConstructors(KeyguardViewManager, new XC_MethodHook() {
					@Override
					protected void afterHookedMethod(MethodHookParam param) throws Throwable {
						try {
							String fieldName;
							if (currentapiVersion < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
								fieldName = "mCallback";
							} else {
								fieldName = "mViewMediatorCallback";
							}

							mViewMediatorCallback = XposedHelpers.getObjectField(param.thisObject,
									fieldName);
						} catch (NoSuchFieldError e) {
							e.printStackTrace();
						}
					}
				});
			} catch (ClassNotFoundError e) {
				XposedBridge.log("Class not found: " + e.getMessage().toString() + " NFC Unlocking won't work");
			}
		}

		/* HTC devices have the lockscreen in a separate package, thanks to vrthe1
		 * for the patch.
		 */
		if (lpparam.packageName.equals("com.htc.lockscreen") && !mBroadcastReceiverRegistered) {
			Class<?> ClassToHook = findClass(
					"com.htc.lockscreen.HtcKeyguardHostViewImpl",
					lpparam.classLoader);
			XposedBridge.hookAllConstructors(ClassToHook, new XC_MethodHook() {
				@Override
				protected void afterHookedMethod(MethodHookParam param) throws Throwable {
					try {
						mKeyguardSecurityCallbackInstance = getObjectField(
								param.thisObject, "mSecurityCallback");
					} catch (NoSuchFieldError e) {}
					Context context = (Context) param.args[0];
					registerNfcUnlockReceivers(context);
				}
			});
		}
	}

	private void registerNfcUnlockReceivers(Context context) {
		if (context == null)
			return;

		// *facepalm* previous versions probably leaked memory right here in this very method.
		if (mBroadcastReceiverRegistered)
			return;

		mBroadcastReceiverRegistered = true;

		BroadcastReceiver receiver = new BroadcastReceiver() {				
			@Override
			public void onReceive(Context context, Intent intent) {
				if (Common.INTENT_UNLOCK_DEVICE.equals(intent.getAction())) {
					try {
						// Fake a correct passcode
						if (mKeyguardSecurityCallbackInstance != null) {
							XposedHelpers.callMethod(mKeyguardSecurityCallbackInstance,
									"reportSuccessfulUnlockAttempt");
						}

						if (mViewMediatorCallback != null) {
							XposedHelpers.callMethod(mViewMediatorCallback,
									"keyguardDone", true);
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
				} else if (Common.INTENT_UNLOCK_INTERCEPTED.equals(intent.getAction())) {
					Notification.Builder mBuilder = new Notification.Builder(context)
					.setSmallIcon(android.R.drawable.ic_dialog_alert)
					.setContentTitle("Unlock attempt intercepted")
					.setContentText("An attempt to use an intent used by NFC unlocking to unlock your device has been prevented")
					.setAutoCancel(true);
					NotificationManager mNotificationManager =
							(NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
					mNotificationManager.notify(0, mBuilder.build());
				}
			}
		};

		context.registerReceiver(receiver, new IntentFilter(Common.INTENT_UNLOCK_DEVICE));
		context.registerReceiver(receiver, new IntentFilter(Common.INTENT_UNLOCK_INTERCEPTED));
	}
}
