package com.red5pro.reactnative.stream;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.red5pro.reactnative.view.PublishService;
import com.red5pro.reactnative.view.SubscribeService;
import com.red5pro.streaming.R5Connection;
import com.red5pro.streaming.R5Stream;
import com.red5pro.streaming.config.R5Configuration;
import com.red5pro.streaming.event.R5ConnectionEvent;
import com.red5pro.streaming.media.R5AudioController;

public class R5StreamSubscriber implements R5StreamInstance,
		SubscribeService.SubscribeServicable {

	private ReactApplicationContext mContext;
	private DeviceEventManagerModule.RCTDeviceEventEmitter mEventEmitter;

	private R5Configuration mConfiguration;
	private R5Connection mConnection;
	private R5Stream mStream;

	private boolean mIsStreaming;
	private boolean mIsBackgroundBound;
	private boolean mEnableBackgroundStreaming;
	private SubscribeService mBackgroundSubscribeService;
	private Intent mSubscribeIntent;

	private ServiceConnection mSubscribeServiceConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName name, IBinder service) {
			Log.d("R5StreamSubscriber", "connection:onServiceConnected()");
			mBackgroundSubscribeService = ((SubscribeService.SubscribeServiceBinder)service).getService();
			mBackgroundSubscribeService.setServicableDelegate(R5StreamSubscriber.this);
		}
		@Override
		public void onServiceDisconnected(ComponentName name) {
			Log.d("R5StreamSubscriber", "connection:onServiceDisconnected()");
			mBackgroundSubscribeService = null;
		}
	};

	public enum Events {

		CONFIGURED("onConfigured"),
		METADATA("onMetaDataEvent"),
		SUBSCRIBER_STATUS("onSubscriberStreamStatus"),
		UNSUBSCRIBE_NOTIFICATION("onUnsubscribeNotification");

		private final String mName;

		Events(final String name) {
			mName = name;
		}

		@Override
		public String toString() {
			return mName;
		}

	}

	public R5StreamSubscriber (ReactApplicationContext context) {
		this.mContext = context;
		this.mContext.addLifecycleEventListener(this);
		this.mEventEmitter = mContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class);
	}

	private void cleanup() {

		Log.d("R5StreamSubscriber", ":cleanup (" + mConfiguration.getStreamName() + ")!");
		if (mStream != null) {
			mStream.client = null;
			mStream.setListener(null);
			mStream = null;
		}

		if (mConnection != null) {
			mConnection.removeListener();
			mConnection = null;
		}
		mIsStreaming = false;

	}

	private void detectToStartService (Intent intent, ServiceConnection connection) {
		Log.d("R5StreamSubscriber", "detectStartService()");
		boolean found = false;
		Activity activity = mContext.getCurrentActivity();
		ActivityManager actManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
		try {
			for (ActivityManager.RunningServiceInfo serviceInfo : actManager.getRunningServices(Integer.MAX_VALUE)) {
				if (serviceInfo.service.getClassName().equals(PublishService.class.getName())) {
					found = true;
				}
			}
		} catch (NullPointerException e){}

		if(!found){
			Log.d("R5StreamSubscriber", "detectStartService:start()");
			mContext.getCurrentActivity().startService(intent);
		}

		Log.d("R5StreamSubscriber", "detectStartService:bind()");
		activity.bindService(intent, connection, Context.BIND_IMPORTANT);
		mIsBackgroundBound = true;
	}

	private void establishConnection(R5Configuration configuration,
									 int audioMode,
									 int logLevel,
									 int scaleMode) {

		R5AudioController.mode = audioMode == 1
				? R5AudioController.PlaybackMode.STANDARD
				: R5AudioController.PlaybackMode.AEC;

		mConfiguration = configuration;
		mConnection = new R5Connection(configuration);
		mStream = new R5Stream(mConnection);

		mStream.setListener(this);
		mStream.client = this;

		mStream.setLogLevel(logLevel);
		mStream.setScaleMode(scaleMode);

	}

	private void doSubscribe (String streamName) {

		Log.d("R5StreamSubscriber", "doSubscribe()");
		mStream.play(streamName);

	}

	public void subscribeBound () {

		Log.d("R5StreamSubscriber", "doSubscribeBound()");
		doSubscribe(mConfiguration.getStreamName());

	}

	public R5StreamSubscriber subscribe (R5Configuration configuration,
										 boolean enableBackground) {
		return subscribe(configuration, enableBackground, 1, 3, 0);
	}

	public R5StreamSubscriber subscribe (R5Configuration configuration,
										 boolean enableBackground,
										 int audioMode,
										 int logLevel,
										 int scaleMode) {

		establishConnection(configuration, audioMode, logLevel, scaleMode);
		WritableMap evt = new WritableNativeMap();
		mEventEmitter.emit(R5StreamSubscriber.Events.CONFIGURED.toString(), evt);

		if (enableBackground) {
			Log.d("R5StreamSubscriber", "Setting up bound subscriber for background streaming.");
			// Set up service and offload setup.
			mEnableBackgroundStreaming = true;
			mSubscribeIntent = new Intent(mContext.getCurrentActivity(), SubscribeService.class);
			detectToStartService(mSubscribeIntent, mSubscribeServiceConnection);
			return this;
		}

		doSubscribe(configuration.getStreamName());
		return this;

	}

	public void unsubscribe () {

	}

	public void setPlaybackVolume (float value) {
		Log.d("R5StreamSubscriber", "setPlaybackVolume(" + value + ")");
		if (mIsStreaming) {
			if (mStream != null && mStream.audioController != null) {
				mStream.audioController.setPlaybackGain(value);
			}
		}
	}

	private void setSubscriberDisplayOn (Boolean setOn) {

		Log.d("R5StreamSubscriber", "setSubscriberDisplayOn(" + setOn + ")");
		if (!setOn) {
			if (mStream != null) {
				Log.d("R5StreamSubscriber", "Stream:deactivate_display()");
				mStream.deactivate_display();
			}
		} else if (mStream != null) {
			Log.d("R5StreamSubscriber", "Stream:activate_display()");
			mStream.activate_display();

		}

		if (mBackgroundSubscribeService != null) {
			mBackgroundSubscribeService.setDisplayOn(setOn);
		}

	}

	private void sendToBackground () {

		Log.d("R5StreamSubscriber", "sendToBackground()");
		if (!mEnableBackgroundStreaming) {
			Log.d("R5StreamSubscriber", "sendToBackground:shutdown");
			this.unsubscribe();
			return;
		}

		if (mIsStreaming && mEnableBackgroundStreaming) {
			Log.d("R5StreamSubscriber", "sendToBackground:subscriberPause");
			this.setSubscriberDisplayOn(false);
		}

	}

	private void bringToForeground () {

		Log.d("R5StreamSubscriber", "bringToForeground()");
		if (mIsStreaming && mEnableBackgroundStreaming) {
			Log.d("R5StreamSubscriber", "sendToBackground:publiserResume");
			this.setSubscriberDisplayOn(true);
		}

	}

	public void onMetaData(String metadata) {

		Log.d("R5StreamSubscriber", "onMetaData() : " + metadata);
		WritableMap map = new WritableNativeMap();
		map.putString("metadata", metadata);
		mEventEmitter.emit(R5StreamSubscriber.Events.METADATA.toString(), map);

	}

	@Override
	public void onConnectionEvent(R5ConnectionEvent event) {

		Log.d("R5StreamSubscriber", ":onConnectionEvent " + event.name());
		WritableMap map = new WritableNativeMap();
		WritableMap statusMap = new WritableNativeMap();
		statusMap.putInt("code", event.value());
		statusMap.putString("message", event.message);
		statusMap.putString("name", event.name());
		statusMap.putString("streamName", mConfiguration.getStreamName());
		map.putMap("status", statusMap);

		mEventEmitter.emit(R5StreamSubscriber.Events.SUBSCRIBER_STATUS.toString(), map);

		if (event == R5ConnectionEvent.START_STREAMING) {
			mIsStreaming = true;
		}
		else if (event == R5ConnectionEvent.DISCONNECTED && mIsStreaming) {
			WritableMap evt = new WritableNativeMap();
			mEventEmitter.emit(R5StreamSubscriber.Events.UNSUBSCRIBE_NOTIFICATION.toString(), evt);
			Log.d("R5StreamSubscriber", "DISCONNECT");
			cleanup();
			mIsStreaming = false;
		}

	}

	@Override
	public void onHostResume() {
		Log.d("R5StreamSubscriber", "onHostResume()");
		bringToForeground();
	}

	@Override
	public void onHostPause() {
		Log.d("R5StreamSubscriber", "onHostPause()");
		sendToBackground();
	}

	@Override
	public void onHostDestroy() {
		Log.d("R5StreamSubscriber", "onHostDestroy()");
		Activity activity = mContext.getCurrentActivity();
		if (mSubscribeIntent != null && mIsBackgroundBound) {
			this.setSubscriberDisplayOn(false);
			activity.unbindService(mSubscribeServiceConnection);
			activity.stopService(mSubscribeIntent);
			mIsBackgroundBound = false;
		}
	}

}
