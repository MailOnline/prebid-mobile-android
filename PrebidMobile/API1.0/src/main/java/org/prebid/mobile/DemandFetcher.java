/*
 *    Copyright 2018-2019 Prebid.org, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package org.prebid.mobile;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;

import java.util.HashMap;
import java.util.UUID;

class DemandFetcher {

    private final HandlerThread fetcherThread;

    enum STATE {
        STOPPED,
        RUNNING,
        DESTROYED
    }

    private STATE state;
    private int periodMillis;
    private Object adObject;
    private OnCompleteListener listener;
    private Handler fetcherHandler;
    private RequestRunnable requestRunnable;
    private long lastFetchTime = -1;
    private long timePausedAt = -1;
    private RequestParams requestParams;

    DemandFetcher(@NonNull Object adObj) {
        this.state = STATE.STOPPED;
        this.periodMillis = 0;
        this.adObject = adObj;
        fetcherThread = new HandlerThread("FetcherThread");
        fetcherThread.start();
        this.fetcherHandler = new Handler(fetcherThread.getLooper());
        this.requestRunnable = new RequestRunnable();
    }

    void setListener(OnCompleteListener listener) {
        this.listener = listener;
    }

    void setRequestParams(RequestParams requestParams) {
        this.requestParams = requestParams;
    }


    void setPeriodMillis(int periodMillis) {
        boolean periodChanged = this.periodMillis != periodMillis;
        this.periodMillis = periodMillis;
        if ((periodChanged) && !state.equals(STATE.STOPPED)) {
            stop();
            start();
        }
    }

    private void stop() {
        this.requestRunnable.cancelRequest();
        this.fetcherHandler.removeCallbacks(requestRunnable);
        // cancel existing requests
        timePausedAt = System.currentTimeMillis();
        state = STATE.STOPPED;
    }

    void start() {
        switch (state) {
            case STOPPED:
                if (this.periodMillis <= 0) {
                    // start a single request
                    fetcherHandler.post(requestRunnable);
                } else {
                    // Start recurring ad requests
                    final int msPeriod = periodMillis; // refresh periodMillis
                    final long stall; // delay millis for the initial request
                    if (timePausedAt != -1 && lastFetchTime != -1) {
                        //Clamp the stall between 0 and the periodMillis. Ads should never be requested on
                        //a delay longer than the periodMillis
                        stall = Math.min(msPeriod, Math.max(0, msPeriod - (timePausedAt - lastFetchTime)));
                    } else {
                        stall = 0;
                    }
                    fetcherHandler.postDelayed(requestRunnable, stall * 1000);
                }
                state = STATE.RUNNING;
                break;
            case RUNNING:
                if (this.periodMillis <= 0) {
                    // start a single request
                    fetcherHandler.post(requestRunnable);
                }
                break;
            case DESTROYED:
                break;
        }
    }

    void destroy() {
        if (state != STATE.DESTROYED) {
            this.adObject = null;
            this.listener = null;
            this.requestRunnable.cancelRequest();
            this.fetcherHandler.removeCallbacks(requestRunnable);
            this.requestRunnable = null;
            cleanThread(fetcherThread);
            state = STATE.DESTROYED;
        }
    }

    private void notifyListener(final ResultCode resultCode) {
        if (listener != null) {
            final OnCompleteListener listenerFinal = listener;
            Handler uiThread = new Handler(Looper.getMainLooper());
            uiThread.post(new Runnable() {
                @Override
                public void run() {
                    listenerFinal.onComplete(resultCode);

                }
            });
        }
        // for single request, if done, finish current fetcher,
        // let ad unit create a new fetcher for next request
        if (periodMillis <= 0) {
            destroy();
        }
    }

    class RequestRunnable implements Runnable {
        private final HandlerThread demandThread;
        private DemandAdapter demandAdapter;
        private boolean finished = false;
        private String auctionId;
        private Handler demandHandler;

        RequestRunnable() {
            // Using a separate thread for making demand request so that waiting on currently thread doesn't block actual fetching
            demandThread = new HandlerThread("DemandThread");
            demandThread.start();
            this.demandHandler = new Handler(demandThread.getLooper());
            this.demandAdapter = new PrebidServerAdapter();
            auctionId = UUID.randomUUID().toString();
        }

        void cancelRequest() {
            this.demandAdapter.stopRequest(auctionId);
        }

        @Override
        public void run() {
            // reset state
            auctionId = UUID.randomUUID().toString();
            finished = false;
            lastFetchTime = System.currentTimeMillis();
            // check input values
            demandHandler.post(new Runnable() {
                final String auctionIdFinal = auctionId;

                @Override
                public void run() {
                    demandAdapter.requestDemand(requestParams, new DemandAdapter.DemandAdapterListener() {
                        @Override
                        public void onDemandReady(final HashMap<String, String> demand, String auctionId) {
                            if (!finished && RequestRunnable.this.auctionId.equals(auctionId)) {
                                Util.apply(demand, DemandFetcher.this.adObject);
                                LogUtil.i("Successfully set the following keywords: " + demand.toString());
                                notifyListener(ResultCode.SUCCESS);
                                cleanThread(demandThread);
                                finished = true;
                            }
                        }

                        @Override
                        public void onDemandFailed(ResultCode resultCode, String auctionId) {
                            if (!finished && RequestRunnable.this.auctionId.equals(auctionId)) {
                                Util.apply(null, DemandFetcher.this.adObject);
                                LogUtil.i("Removed all used keywords from the ad object");
                                notifyListener(resultCode);
                                cleanThread(demandThread);
                                finished = true;
                            }
                        }
                    }, auctionIdFinal);
                }
            });
            if (periodMillis > 0) {
                fetcherHandler.postDelayed(this, periodMillis);
            }
            while (!finished && !testMode) {
                long currentTime = System.currentTimeMillis();
                if (currentTime - lastFetchTime >= PrebidMobile.timeoutMillis) {
                    cleanThread(demandThread);
                    finished = true;
                    notifyListener(ResultCode.TIMEOUT);
                }
                if (Thread.interrupted()) {
                    return;
                }
            }
        }


    }

    private static void cleanThread(HandlerThread thread) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            thread.quitSafely();
        } else {
            thread.quit();
        }
    }


    //region exposed for testing
    @VisibleForTesting
    Handler getHandler() {
        return this.fetcherHandler;
    }

    @VisibleForTesting
    Handler getDemandHandler() {
        RequestRunnable runnable = this.requestRunnable;
        return runnable.demandHandler;
    }

    private boolean testMode = false;

    @VisibleForTesting
    void enableTestMode() {
        this.testMode = true;
    }
    //endregion
}



