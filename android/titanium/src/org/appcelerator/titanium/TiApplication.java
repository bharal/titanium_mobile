/**
 * Appcelerator Titanium Mobile
 * Copyright (c) 2009-2010 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License
 * Please see the LICENSE included with this distribution for details.
 */
package org.appcelerator.titanium;

import java.io.File;
import java.lang.Thread.UncaughtExceptionHandler;
import java.lang.ref.SoftReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import org.appcelerator.titanium.analytics.TiAnalyticsEvent;
import org.appcelerator.titanium.analytics.TiAnalyticsEventFactory;
import org.appcelerator.titanium.analytics.TiAnalyticsModel;
import org.appcelerator.titanium.analytics.TiAnalyticsService;
import org.appcelerator.titanium.util.Log;
import org.appcelerator.titanium.util.TiConfig;
import org.appcelerator.titanium.util.TiPlatformHelper;
import org.appcelerator.titanium.view.ITiWindowHandler;

import android.app.Application;
import android.content.Intent;

// Naming TiHost to more closely match other implementations
public class TiApplication extends Application
{
	public static final String DEPLOY_TYPE_DEVELOPMENT = "development";
	public static final String DEPLOY_TYPE_TEST = "test";
	public static final String DEPLOY_TYPE_PRODUCTION = "production";
	
	private static final String PROPERTY_DEPLOY_TYPE = "ti.deploytype";
	private static final String LCAT = "TiApplication";
	private static final boolean DBG = TiConfig.LOGD;
	private static final long STATS_WAIT = 300000;

	private String baseUrl;
	private String startUrl;
	private HashMap<Class<?>, HashMap<String, Method>> methodMap;
	private HashMap<String, SoftReference<TiProxy>> proxyMap;
	private TiRootActivity rootActivity;
	private TiProperties appProperties;
	private ITiWindowHandler windowHandler;
	protected ITiAppInfo appInfo;

	private boolean needsStartEvent;
	private boolean needsEnrollEvent;
	protected TiAnalyticsModel analyticsModel;
	protected Intent analyticsIntent;
	private static long lastAnalyticsTriggered = 0;

	public TiApplication() {
		Log.checkpoint("checkpoint, app created.");

		needsEnrollEvent = false; // test is after DB is available
		needsStartEvent = true;
	}

	@Override
	public void onCreate()
	{
		super.onCreate();

		final UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {

			public void uncaughtException(Thread t, Throwable e) {
				Log.e("TiUncaughtHandler", "Sending event: exception on thread: " + t.getName() + " msg:" + e.toString(), e);
				postAnalyticsEvent(TiAnalyticsEventFactory.createErrorEvent(t, e));
				defaultHandler.uncaughtException(t, e);
				//Process.killProcess(Process.myPid());
			}
		});

		//TODO read from tiapp.xml
		TiConfig.LOGD = true;

		baseUrl = "file:///android_asset/Resources/";

		File fullPath = new File(baseUrl, getStartFilename("app.js"));
		baseUrl = fullPath.getParent();

		methodMap = new HashMap<Class<?>, HashMap<String,Method>>(25);
		proxyMap = new HashMap<String, SoftReference<TiProxy>>(5);

		TiPlatformHelper.initialize(this);

		appProperties = new TiProperties(getApplicationContext(), "titanium", false);
	}

	public void setRootActivity(TiRootActivity rootActivity) {
		//TODO consider weakRef
		this.rootActivity = rootActivity;
		this.windowHandler = rootActivity;

		if (collectAnalytics()) {
			analyticsIntent = new Intent(this, TiAnalyticsService.class);
			analyticsModel = new TiAnalyticsModel(this);
			needsEnrollEvent = analyticsModel.needsEnrollEvent();

			if (needsEnrollEvent()) {
				String deployType = appProperties.getString("ti.deploytype", "unknown");
				postAnalyticsEvent(TiAnalyticsEventFactory.createAppEnrollEvent(this,deployType));
			}

			if (needsStartEvent()) {
				String deployType = appProperties.getString("ti.deploytype", "unknown");

				postAnalyticsEvent(TiAnalyticsEventFactory.createAppStartEvent(this, deployType));
			}

		} else {
			needsEnrollEvent = false;
			needsStartEvent = false;
			Log.i(LCAT, "Analytics have been disabled");
		}
	}

	public TiRootActivity getRootActivity() {
		return rootActivity;
	}

	public ITiWindowHandler getWindowHandler() {
		return windowHandler;
	}

	public void setWindowHandler(ITiWindowHandler windowHandler) {
		if (windowHandler == null) {
			this.windowHandler = rootActivity;
		} else {
			this.windowHandler = windowHandler; //TODO weakRef?
		}
	}

	public String getBaseUrl() {
		return baseUrl;
	}

	public String getStartUrl() {
		return startUrl;
	}

	private String getStartFilename(String defaultStartFile) {
		return defaultStartFile;
	}

	public synchronized Method methodFor(Class<?> source, String name)
	{
		HashMap<String, Method> classMethods = methodMap.get(source);
		if (classMethods == null) {
			Method[] methods = source.getMethods();
			classMethods = new HashMap<String, Method>(methods.length);
			methodMap.put(source, classMethods);

			// we need to sort methods by their implementation order
			// i.e. subClass > superClass precedence
			final HashMap<Class<?>, Integer> hierarchy = new HashMap<Class<?>, Integer>();
			int i = 0;
			hierarchy.put(source, 0);
			for (Class<?> superClass = source.getSuperclass(); superClass != null;
				superClass = superClass.getSuperclass())
			{
				hierarchy.put(superClass, ++i);
			}

			Comparator<Method> comparator = new Comparator<Method>()
			{
				public int compare(Method o1, Method o2) {
					int h1 = hierarchy.get(o1.getDeclaringClass());
					int h2 = hierarchy.get(o2.getDeclaringClass());
					return h1-h2;
				}
			};

			List<Method> methodList = Arrays.asList(methods);
			Collections.sort(methodList, comparator);
			Collections.reverse(methodList);

			for(Method method : methodList) {
				// TODO filter?
				//Log.e(LCAT, "Obj: " + source.getSimpleName() + " Method: " + method.getName());
				classMethods.put(method.getName(), method);
			}
		}

		return classMethods.get(name);
	}

	private ArrayList<TiProxy> appEventProxies = new ArrayList<TiProxy>();
	public void addAppEventProxy(TiProxy appEventProxy)
	{
		appEventProxies.add(appEventProxy);
	}

	public void removeAppEventProxy(TiProxy appEventProxy)
	{
		appEventProxies.remove(appEventProxy);
	}

	public void fireAppEvent(String eventName, TiDict data)
	{
		for (TiProxy appEventProxy : appEventProxies)
		{
			appEventProxy.getTiContext().dispatchEvent(eventName, data);
		}
	}

	public TiProperties getAppProperties()
	{
		return appProperties;
	}

	public ITiAppInfo getAppInfo() {
		return appInfo;
	}

	public void registerProxy(TiProxy proxy) {
		String proxyId = proxy.proxyId;
		if (!proxyMap.containsKey(proxyId)) {
			proxyMap.put(proxyId, new SoftReference<TiProxy>(proxy));
		}
	}

	public TiProxy unregisterProxy(String proxyId) {
		TiProxy proxy = null;

		SoftReference<TiProxy> ref = proxyMap.remove(proxyId);
		if (ref != null) {
			proxy = ref.get();
		}

		return proxy;
	}

	@Override
	public void onLowMemory()
	{
		super.onLowMemory();
	}

	@Override
	public void onTerminate() {
		super.onTerminate();
	}

	public synchronized boolean needsStartEvent() {
		return needsStartEvent;
	}

	public synchronized boolean needsEnrollEvent() {
		return needsEnrollEvent;
	}

	private boolean collectAnalytics() {
		return getAppInfo().isAnalyticsEnabled();
	}

	public synchronized void postAnalyticsEvent(TiAnalyticsEvent event)
	{
		if (!collectAnalytics()) {
			if (DBG) {
				Log.i(LCAT, "Analytics are disabled, ignoring postAnalyticsEvent");
			}
			return;
		}

		if (DBG) {
			StringBuilder sb = new StringBuilder();
			sb.append("Analytics Event: type=").append(event.getEventType())
				.append("\n event=").append(event.getEventEvent())
				.append("\n timestamp=").append(event.getEventTimestamp())
				.append("\n mid=").append(event.getEventMid())
				.append("\n sid=").append(event.getEventSid())
				.append("\n aguid=").append(event.getEventAppGuid())
				.append("\n isJSON=").append(event.mustExpandPayload())
				.append("\n payload=").append(event.getEventPayload())
				;
			Log.d(LCAT, sb.toString());
		}

		if (event.getEventType() == TiAnalyticsEventFactory.EVENT_APP_ENROLL) {
			if (needsEnrollEvent) {
				analyticsModel.addEvent(event);
				needsEnrollEvent = false;
				sendAnalytics();
				analyticsModel.markEnrolled();
			}
		} else if (event.getEventType() == TiAnalyticsEventFactory.EVENT_APP_START) {
			if (needsStartEvent) {
				analyticsModel.addEvent(event);
				needsStartEvent = false;
				sendAnalytics();
				lastAnalyticsTriggered = System.currentTimeMillis();
			}
			return;
		} else if (event.getEventType() == TiAnalyticsEventFactory.EVENT_APP_END) {
			needsStartEvent = true;
			analyticsModel.addEvent(event);
			sendAnalytics();
		} else {
			analyticsModel.addEvent(event);
			long now = System.currentTimeMillis();
			if (now - lastAnalyticsTriggered >= STATS_WAIT) {
				sendAnalytics();
				lastAnalyticsTriggered = now;
			}
		}
	}

	public void sendAnalytics() {
		if (analyticsIntent != null) {
			if (startService(analyticsIntent) == null) {
				Log.w(LCAT, "Analytics service not found.");
			}
		}
	}

	public String getDeployType()
	{
		return getAppProperties().getString(PROPERTY_DEPLOY_TYPE, DEPLOY_TYPE_DEVELOPMENT);
	}
}
