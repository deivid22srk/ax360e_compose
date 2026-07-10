/*
 * Copyright 2021 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package aenu.ax360e;

import android.app.Activity;
import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.appopen.AppOpenAd;
import com.google.android.gms.ads.appopen.AppOpenAd.AppOpenAdLoadCallback;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.UserMessagingPlatform;

import java.util.Date;

/** Application class that initializes, loads and show ads when activities change states. */
// [START application_class]
public class AppOpenAdManager {

    private static final String LOG_TAG = "AppOpenAdManager";
    private final String AD_UNIT_ID="ca-app-pub-5412294626339113/8042966570";

    private final ConsentInformation consentInformation;
    private AppOpenAd appOpenAd = null;
    private boolean isLoadingAd = false;
    private boolean isShowingAd = false;

    /** Keep track of the time an app open ad is loaded to ensure you don't show an expired ad. */
    private long loadTime = 0;

    public interface OnLoadAdCompleteListener {
        void onLoadAdComplete();
    }
    /**
     * Interface definition for a callback to be invoked when an app open ad is complete (i.e.
     * dismissed or fails to show).
     */
    public interface OnShowAdCompleteListener {
        void onShowAdComplete();
    }

    private static AppOpenAdManager instance;
    public static AppOpenAdManager getInstance(Context ctx) {
        if (instance == null) {
            instance = new AppOpenAdManager(ctx);
        }
        return instance;
    }

    /** Constructor. */
    private AppOpenAdManager(Context ctx) {
        /*ApplicationInfo info = ctx.getApplicationInfo();
        AD_UNIT_ID = info.metaData.getString("com.google.android.gms.ads.APPLICATION_ID");
        Log.i(LOG_TAG, "AD_UNIT_ID: " + AD_UNIT_ID);*/
        consentInformation = UserMessagingPlatform.getConsentInformation(ctx);
    }

    // [END manager_class]

    public void loadAd(Activity activity) {
        loadAd(activity, new OnLoadAdCompleteListener() {
            @Override
            public void onLoadAdComplete() {
            }
        });
    }
    /**
     * Load an ad.
     *
     * @param context the context of the activity that loads the ad
     */
    public void loadAd(Activity activity, OnLoadAdCompleteListener listener) {
        Log.i(LOG_TAG, "loadAd: isShowingAd: " + isShowingAd);
        // Do not load ad if there is an unused ad or one is already loading.
        if (isLoadingAd || isAdAvailable()) {
            Log.w(LOG_TAG, "Can not load ad. An ad is already loading or there is one cached already.");
            return;
        }
        isLoadingAd = true;
        // [START load_ad]
        AppOpenAd.load(
                activity,
                AD_UNIT_ID,
                new AdRequest.Builder().build(),
                new AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(AppOpenAd ad) {
                        // Called when an app open ad has loaded.
                        Log.w(LOG_TAG, "App open ad loaded.");
                        //Toast.makeText(activity, "Ad was loaded.", Toast.LENGTH_SHORT).show();

                        appOpenAd = ad;
                        isLoadingAd = false;
                        loadTime = (new Date()).getTime();
                        listener.onLoadAdComplete();
                    }

                    @Override
                    public void onAdFailedToLoad(LoadAdError loadAdError) {
                        // Called when an app open ad has failed to load.
                        Log.w(LOG_TAG, "App open ad failed to load with error: " + loadAdError.getMessage());
                        //Toast.makeText(activity, "Ad failed to load.", Toast.LENGTH_SHORT).show();

                        isLoadingAd = false;
                        listener.onLoadAdComplete();
                    }
                });
        // [END load_ad]
    }

    // [START ad_expiration]
    /** Check if ad was loaded more than n hours ago. */
    private boolean wasLoadTimeLessThanNHoursAgo(long numHours) {
        long dateDifference = (new Date()).getTime() - loadTime;
        long numMilliSecondsPerHour = 3600000;
        return (dateDifference < (numMilliSecondsPerHour * numHours));
    }

    /** Check if ad exists and can be shown. */
    private boolean isAdAvailable() {
        // For time interval details, see: https://support.google.com/admob/answer/9341964
        return appOpenAd != null && wasLoadTimeLessThanNHoursAgo(4);
    }

    /**
     * Show the ad if one isn't already showing.
     *
     * @param activity the activity that shows the app open ad
     */
    public void showAdIfAvailable(@NonNull final Activity activity) {
        showAdIfAvailable(
                activity,
                new OnShowAdCompleteListener() {
                    @Override
                    public void onShowAdComplete() {
                        // Empty because the user will go back to the activity that shows the ad.
                    }
                });
    }

    /**
     * Show the ad if one isn't already showing.
     *
     * @param activity the activity that shows the app open ad
     * @param onShowAdCompleteListener the listener to be notified when an app open ad is complete
     */
    public void showAdIfAvailable(
            @NonNull final Activity activity,
            @NonNull OnShowAdCompleteListener onShowAdCompleteListener) {
        // If the app open ad is already showing, do not show the ad again.
        if (isShowingAd) {
            Log.w(LOG_TAG, "The app open ad is already showing.");
            return;
        }

        // If the app open ad is not available yet, invoke the callback then load the ad.
        if (!isAdAvailable()) {
            Log.w(LOG_TAG, "The app open ad is not ready yet.");
            onShowAdCompleteListener.onShowAdComplete();
            if (consentInformation.canRequestAds()) {
                loadAd(activity);
            }
            return;
        }

        Log.w(LOG_TAG, "Will show ad.");

        appOpenAd.setFullScreenContentCallback(
                new FullScreenContentCallback() {
                    /** Called when full screen content is dismissed. */
                    @Override
                    public void onAdDismissedFullScreenContent() {
                        // Set the reference to null so isAdAvailable() returns false.
                        appOpenAd = null;
                        isShowingAd = false;

                        Log.w(LOG_TAG, "onAdDismissedFullScreenContent.");
                        //Toast.makeText(activity, "onAdDismissedFullScreenContent", Toast.LENGTH_SHORT).show();

                        onShowAdCompleteListener.onShowAdComplete();
                        if (consentInformation.canRequestAds()) {
                            loadAd(activity);
                        }
                    }

                    /** Called when fullscreen content failed to show. */
                    @Override
                    public void onAdFailedToShowFullScreenContent(AdError adError) {
                        appOpenAd = null;
                        isShowingAd = false;

                        Log.w(LOG_TAG, "onAdFailedToShowFullScreenContent: " + adError.getMessage());
                        //Toast.makeText(activity, "onAdFailedToShowFullScreenContent", Toast.LENGTH_SHORT)
                        //        .show();

                        onShowAdCompleteListener.onShowAdComplete();
                        if (consentInformation.canRequestAds()) {
                            loadAd(activity);
                        }
                    }

                    /** Called when fullscreen content is shown. */
                    @Override
                    public void onAdShowedFullScreenContent() {
                        Log.w(LOG_TAG, "onAdShowedFullScreenContent.");
                        //Toast.makeText(activity, "onAdShowedFullScreenContent", Toast.LENGTH_SHORT).show();
                    }
                });

        isShowingAd = true;
        appOpenAd.show(activity);
    }
}