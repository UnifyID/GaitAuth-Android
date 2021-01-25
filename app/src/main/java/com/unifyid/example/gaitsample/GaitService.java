package com.unifyid.example.gaitsample;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.List;
import java.util.Vector;

import id.unify.sdk.core.CompletionHandler;
import id.unify.sdk.core.UnifyID;
import id.unify.sdk.core.UnifyIDConfig;
import id.unify.sdk.core.UnifyIDException;
import id.unify.sdk.gaitauth.AuthenticationListener;
import id.unify.sdk.gaitauth.AuthenticationResult;
import id.unify.sdk.gaitauth.Authenticator;
import id.unify.sdk.gaitauth.FeatureCollectionException;
import id.unify.sdk.gaitauth.FeatureEventListener;
import id.unify.sdk.gaitauth.GaitAuth;
import id.unify.sdk.gaitauth.GaitAuthException;
import id.unify.sdk.gaitauth.GaitFeature;
import id.unify.sdk.gaitauth.GaitModel;
import id.unify.sdk.gaitauth.GaitModelException;
import id.unify.sdk.gaitauth.GaitQuantileConfig;
import id.unify.sdk.gaitauth.GaitScore;
import id.unify.sdk.gaitauth.OnScoreListener;

import static android.app.NotificationManager.*;
import static id.unify.sdk.gaitauth.AuthenticationResult.*;


// Most of the interaction with the GaitAuth classes is performed here through a service. This
// allows the collection of features and training to occur even when the application is in the
// background. Keep in mind that many of the calls to the GaitAuth classes initiate network calls.
// Thus it is necessary to make calls on a thread that is not the UI thread as you would with
// any other network call.

public class GaitService extends Service {


    public interface StatusListener {
        void onAuthenticationStatusChanged(Status status);
        void onScoresUpdated(List<GaitScore> scores);
        void onTrainingStatusUpdated(GaitModel.Status status);
        void onGaitFeatureAdded(GaitFeature feature);
        void onMessage(String[] messages);
    }


    private WeakReference<StatusListener> statusListener;

    // The callback methods must be called from the UI thread. Rather than clutter the code
    // with checks for which thread a callback is being made from, this proxy callback contains
    // the necessary checks. Neither the caller nor the object called need to perform any further
    // checks.
    StatusListener proxyStatusListener = new StatusListener() {

        Handler handler = new Handler(Looper.getMainLooper());

        @Override
        public void onAuthenticationStatusChanged(Status status) {
            if (statusListener == null) {
                return;
            }
            final StatusListener s = statusListener.get();
            if (s != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        s.onAuthenticationStatusChanged(status);
                    }
                });
            }
        }

        @Override
        public void onScoresUpdated(final List<GaitScore> scores) {
            if (statusListener == null) {
                return;
            }
            final StatusListener s = statusListener.get();
            if (s != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        s.onScoresUpdated(scores);
                    }
                });
            }
        }

        public void onTrainingStatusUpdated(final GaitModel.Status status) {
            if (statusListener == null) {
                return;
            }
            final StatusListener s = statusListener.get();
            if (s != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        s.onTrainingStatusUpdated(status);
                    }
                });
            }
        }

        @Override
        public void onGaitFeatureAdded(final GaitFeature feature) {
            if (statusListener == null) {
                return;
            }
            final StatusListener s = statusListener.get();
            if (s != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        s.onGaitFeatureAdded(feature);
                    }
                });
            }
        }

        @Override
        public void onMessage(final String[] messages) {
            if (statusListener == null) {
                return;
            }
            final StatusListener s = statusListener.get();
            if (s != null) {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        s.onMessage(messages);
                    }
                });
            }
        }
    };


    Status lastStatus = Status.INCONCLUSIVE;
    private String userID;
    private GaitModel gaitModel;
    private boolean isInitialized = false;
    boolean isShuttingDown = false;
    boolean isScoringEnabled = false;

    Boolean isAuthenticated = false;

    private static String TAG = "GaitService";
    private static int MIN_FEATURES_FOR_TRAINING = 2000;

    private static final String PREFERENCE_FEATURES = "features";
    private static final String PREFERENCE_KEY_FEATURECOUNT = "featureCount";

    private static int STATUS_UPDATE_INTERVAL = 5 * 60 * 1000;

    private static final String PREFERENCE_GAITMODEL = "gaitModel";
    private static final String PREFERENCE_KEY_MODELID = "modelId";

    public static String INTENT_USER_ID = "UserID";
    public static final String SERVICE_CHANNEL = "ServiceChannel";
    static final float QUANTILE_THRESHOLD  = 0.6f;
    static final long MAX_PASSING_AGE = 30000; // milliseconds. If it has been longer than this amount
    // since the passing score, then the user is not
    // considered authenticated
    static final int MIN_SCORE_COUNT = 4;

    SharedPreferences pref;


    Authenticator gaitAuthenticator;
    Status gaitAuthenticatorResult;

    GaitModel.Status trainingStatus = GaitModel.Status.UNKNOWN;
    NotificationCompat.Builder notificationBuilder;
    PowerManager.WakeLock wakeLock;
    int featureCount = 0;
    // Once this number of features is collected, they will be written to the device storage.
    final int FEATURES_TO_HOLD = 250;

    String trainingStatusReason;

    Thread statusUpdateThread = null;

    Vector<GaitFeature> gaitFeatureList = new Vector<GaitFeature>();
    List<GaitScore> gaitScoreList = new Vector<GaitScore>();


    private String GetSDKKey() {

        String key = getApplicationContext().getResources().getString(R.string.sdkKey);
        return key;
    }

    public void setStatusListener(StatusListener listener) {
        statusListener = new WeakReference<StatusListener>(listener);
    }

    public class LocalBinder extends Binder {
        public GaitService getService() {
            return GaitService.this;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isInitialized) {

            super.onStartCommand(intent, flags, startId);
            // The user name is passed from the activity that starts the service
            userID = intent.getStringExtra(INTENT_USER_ID);

            initGait();
            initNotification();
            isInitialized = true;
        }
        return START_NOT_STICKY;
    }

    public boolean isAuthenticated() {
        if(gaitAuthenticator!= null) {

        }
        if (gaitScoreList.size() >= MIN_SCORE_COUNT) {
            long age = (new Date()).getTime() - gaitFeatureList.get(gaitFeatureList.size() - 1).getEndTimeStamp().getTime();
            float sum = 0.0f;
            for (GaitScore score : gaitScoreList) {
                sum += score.getScore();
            }
            float avg = sum / (float) gaitScoreList.size();
            if (avg > QUANTILE_THRESHOLD  && age <= MAX_PASSING_AGE) {
                return true;
            }
        }
        return false;
    }


    void initAuthenticator() {
        //if an authenticator is being used, then feature colleciton is
        // no longer needed. The Authenticator will take care of collecting
        // heatures on its own.
        GaitAuth instance = GaitAuth.getInstance() ;
        instance.unregisterAllListeners();
        GaitQuantileConfig config = new GaitQuantileConfig(QUANTILE_THRESHOLD);
        config.setMinNumScores(1); //Require at least 5 scores
        config.setMaxNumScores(50); //sets the maximum number of scores to use for authenticaiton
        config.setMaxScoreAge(10000); //The maximum age, in milliseconds, for features
        config.setNumQuantiles(100);//set num of quantiles (divisions) for the feature data
        config.setQuantile(50);

        try {
            gaitAuthenticator = GaitAuth.getInstance().createAuthenticator(config, gaitModel);
        } catch(GaitAuthException exc) {

        }
    }
    void initModel() {
        // If we have not already instantiated a GaitMode, then create one.
        if (gaitModel == null) {
            // See if there is a GaitModel ID that we can load.
            SharedPreferences pref = getSharedPreferences(PREFERENCE_GAITMODEL, MODE_PRIVATE);
            String modelID = pref.getString(PREFERENCE_KEY_MODELID, "");
            GaitAuth gaitAuth = GaitAuth.getInstance();
            try {
                // If there is no modelID, then create a model and save it's ID
                if (modelID == "") {
                    gaitModel = gaitAuth.createModel();
                    SharedPreferences.Editor editor = pref.edit();
                    editor.putString(PREFERENCE_KEY_MODELID, gaitModel.getId());
                    editor.commit();
                } else {
                    // If there is a modelID, then use it to load the model
                    gaitModel = gaitAuth.loadModel(modelID);
                    getTrainingStatus();
                }
            } catch (GaitModelException exc) {
                Log.e(TAG, exc.getMessage());
                exc.printStackTrace();
            }
        }
    }

    Thread updateScoreThread = null;

    void updateTrainingScore(final List<GaitFeature> feature) {
        // if there is a score update in progress immediately return
        if (updateScoreThread != null) {
            return;
        }

        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    GaitService.this.gaitModel.score(feature, new OnScoreListener() {
                        @Override
                        public void onComplete(List<GaitScore> scores) {
                            GaitService.this.setGaitScoreList(scores);
                        }

                        @Override
                        public void onFailure(GaitModelException cause) {
                            GaitService.this.setGaitScoreList(null);
                        }
                    });
                } finally {
                    updateScoreThread = null;
                }
            }
        };
        updateScoreThread = new Thread(r);
        updateScoreThread.start();
    }


    public void setGaitScoreList(List<GaitScore> gaitScores) {
        this.gaitScoreList = gaitScores;
    }

    private void setTrainingStatus(GaitModel.Status status) {
        if (trainingStatus != status) {
            trainingStatus = status;
            isScoringEnabled = trainingStatus == GaitModel.Status.READY;
            proxyStatusListener.onTrainingStatusUpdated(trainingStatus);
        }
    }

    public GaitModel.Status getTrainingStatus() {
        if (statusUpdateThread == null) {
            startStatusUpdate();
        }
        return trainingStatus;
    }


    Thread authenticationStatusUpdateThread = null;
    void startAuthenticationStatusUpdate() {
        if(authenticationStatusUpdateThread == null) {
            Runnable r = () -> {
                gaitAuthenticator.getStatus(new AuthenticationListener() {
                    @Override
                    public void onComplete(AuthenticationResult result) {
                        //We have a result. Note that successfully having a result
                        //does not mean that the person is authenticate. The result
                        //could indicate that the person with the device is thought to
                        //be authentic, or thought not to be.
                        gaitAuthenticatorResult = result.getStatus();
                        if(gaitAuthenticatorResult != lastStatus) {
                            lastStatus = gaitAuthenticatorResult;
                            proxyStatusListener.onAuthenticationStatusChanged(lastStatus);
                        }

                        Status x;
                        switch(result.getStatus())
                        {
                            case AUTHENTICATED:
                                isAuthenticated = true;
                            break;

                            case UNAUTHENTICATED:
                                isAuthenticated = false;
                            default:break;
                        }


                    }

                    @Override
                    public void onFailure(GaitAuthException cause) {

                    }
                });
            };
            authenticationStatusUpdateThread = new Thread(r);
            authenticationStatusUpdateThread.start();
        }
    }

    void startStatusUpdate() {
        if (statusUpdateThread != null) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    GaitModel model = GaitService.this.gaitModel;
                    GaitModel.Status status = GaitModel.Status.UNKNOWN;
                    try {
                        do {
                            status = model.getStatus();

                            setTrainingStatus(status);
                            // Training can take a few hours. If the model is in the training status,
                            // then while the application is running refresh the training status every
                            // so often. For the other statuses there will be no change unless there
                            // is an action taken on the model.
                            if(status == GaitModel.Status.READY) {

                                startAuthenticationStatusUpdate();
                                GaitAuth.getInstance().unregisterAllListeners();
                            }
                            if (status == GaitModel.Status.TRAINING && !isShuttingDown) {
                                Thread.sleep(STATUS_UPDATE_INTERVAL);
                            }
                        } while (status == GaitModel.Status.TRAINING && !isShuttingDown);
                    } catch (InterruptedException exc) {
                        exc.printStackTrace();
                    }
                    GaitService.this.statusUpdateThread = null;
                }
            };
            statusUpdateThread = new Thread(r);
            statusUpdateThread.start();
        }
    }

    void initNotification() {
        Resources resources = getResources();
        NotificationChannel serviceChannel = new NotificationChannel(
                SERVICE_CHANNEL, resources.getString(R.string.notification_header),
                IMPORTANCE_MAX);
        serviceChannel.setLightColor(Color.BLUE);
        serviceChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(serviceChannel);

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendintIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Intent stopTrackingIntent = new Intent(this, MainActivity.class);
        stopTrackingIntent.setAction(MainActivity.STOP_TRACKING);
        PendingIntent stopTrackingPendingEvent = PendingIntent.getBroadcast(this, 0, stopTrackingIntent, 0);

        Intent startTrainingIntent = new Intent(this, MainActivity.class);
        startTrainingIntent.setAction(MainActivity.START_TRAINING);
        PendingIntent startTrainingPendingIntent = PendingIntent.getBroadcast(this, 0, startTrainingIntent, 0);


        notificationBuilder = new NotificationCompat.Builder(this, SERVICE_CHANNEL)
                .setContentTitle(resources.getString(R.string.notification_title))
                .setContentText(resources.getString(R.string.notification_description))
                .setSmallIcon(R.drawable.ic_service_notification)
                .setContentIntent(pendintIntent)
                .setNotificationSilent()
                .addAction(R.drawable.ic_stop_tracking, getString(R.string.notification_action_stop_tracking), stopTrackingPendingEvent)
                .addAction(R.drawable.ic_start_training, getString(R.string.notification_action_start_training), startTrainingPendingIntent);

        Notification notification = notificationBuilder.build();
        startForeground(1, notification);
    }

    void initGait() {
        UnifyID.initialize(getApplicationContext(), GetSDKKey(), userID, new CompletionHandler() {
            @Override
            public void onCompletion(UnifyIDConfig config) {
                GaitAuth.initialize(getApplicationContext(), config);
                startFeatureCollection();
                initModel();
                PowerManager mgr = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = mgr.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyWakeLock");
                wakeLock.acquire();
                postUpdate("GaitAuth Initialized");
                getTrainingStatus();
            }

            @Override
            public void onFailure(UnifyIDException exc) {
                exc.printStackTrace();
                postUpdate(exc.getMessage());
                Log.e(TAG, exc.getMessage());
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        saveFeatures();
        GaitAuth.getInstance().unregisterAllListeners();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {

        return new LocalBinder();
    }


    void addFeatureCount(int c) {
        int total = 0;
        SharedPreferences pref = getSharedPreferences(PREFERENCE_FEATURES, MODE_PRIVATE);
        total = pref.getInt(PREFERENCE_KEY_FEATURECOUNT, 0);
        total += c;
        SharedPreferences.Editor editor = pref.edit();
        editor.putInt(PREFERENCE_KEY_FEATURECOUNT, total);
        editor.commit();
    }

    int getFeatureCount() {
        int total = 0;
        SharedPreferences pref = getSharedPreferences(PREFERENCE_FEATURES, MODE_PRIVATE);
        total = pref.getInt(PREFERENCE_KEY_FEATURECOUNT, 0);
        return total;
    }


    void postUpdate(String[] s) {
        proxyStatusListener.onMessage(s);
    }

    void postUpdate(String s) {
        this.postUpdate(new String[]{s});
    }

    boolean isLoadingTraining = false;

    synchronized void trainModel() {
        // If the model isn't already training, then we want to kick off the process.
        if (getTrainingStatus() == GaitModel.Status.TRAINING || isLoadingTraining)
            return;
        AsyncTask startTrainingTask = new AsyncTask() {
            @Override
            protected Object doInBackground(Object[] objects) {
                isLoadingTraining = true;


                File storageDirectory = getApplicationContext().getExternalFilesDir("training_data");
                if (storageDirectory == null)
                    storageDirectory = getApplicationContext().getFilesDir();
                File trainingFile = new File(storageDirectory.toString());

                FilenameFilter filenameFilter = new FilenameFilter() {
                    @Override
                    public boolean accept(File file, String s) {
                        if (s.endsWith(".features") || s.endsWith(".bin"))
                            return true;
                        return false;
                    }
                };
                String[] featureFiles = trainingFile.list(filenameFilter);

                int featureIndex = 0;
                for (String fileName : featureFiles) {

                    File currentFile = new File(trainingFile.toString() + "/" + fileName);
                    try {
                        if (currentFile.canRead()) {
                            FileInputStream fos = new FileInputStream(currentFile);
                            int size = (int) currentFile.length();
                            byte[] data = new byte[size];
                            fos.read(data, 0, data.length);
                            List<GaitFeature> features = GaitAuth.deserializeFeatures(data);
                            String stat = String.format("Added feature set  %d of %d with %d features", featureIndex++, featureFiles.length, features.size());
                            postUpdate(stat);
                            gaitModel.add(features);

                        }
                    } catch (FeatureCollectionException exc) {
                        exc.printStackTrace();
                    } catch (FileNotFoundException exc) {
                        exc.printStackTrace();
                    } catch (IOException exc) {
                        exc.printStackTrace();
                    } catch (GaitModelException exc) {
                        exc.printStackTrace();
                    }
                }
                try {
                    gaitModel.train();
                    GaitService.this.startStatusUpdate();
                } catch (GaitModelException exc) {
                    exc.printStackTrace();
                } finally {
                    isLoadingTraining = false;
                }


                return null;
            }
        };
        startTrainingTask.execute();
    }

    void saveFeatures() {
        if (gaitFeatureList.size() == 0) {
            return;
        }
        Vector<GaitFeature> featuresToSave = new Vector<GaitFeature>();
        synchronized (gaitFeatureList) {
            featuresToSave.addAll(gaitFeatureList);
            gaitFeatureList.clear();
        }
        try {
            byte[] featureData = GaitAuth.serializeFeatures(featuresToSave);
            File storageFile = getStorageFile(getNextFileSegment());
            FileOutputStream fos = new FileOutputStream(storageFile);
            fos.write(featureData, 0, featureData.length);
            fos.close();
            addFeatureCount(featuresToSave.size());
            notificationBuilder.setContentText(String.format("Saved feature set %d containing %d elements at %s", getFeatureCount(), featuresToSave.size(), new Date()));
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.notify(1, notificationBuilder.build());

        } catch (FeatureCollectionException | FileNotFoundException exc) {
            exc.printStackTrace();
        } catch (IOException exc) {
            exc.printStackTrace();
        }
    }

    int getNextFileSegment() {
        SharedPreferences preferences = getSharedPreferences("GaitAuth", MODE_PRIVATE);
        int segment = preferences.getInt("fileSegmentNumber", 0);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("fileSegmentNumber", segment + 1);
        editor.commit();
        return segment;
    }

    File getStorageFile(int segment) {
        File storageDirectory = getApplicationContext().getExternalFilesDir("training_data");
        if (storageDirectory == null)
            storageDirectory = getApplicationContext().getFilesDir();
        File trainingFile = new File(storageDirectory.toString() + "/" + String.format("gait_data_%s_%06d.features", userID, segment));
        return trainingFile;
    }

    void startFeatureCollection() {
        try {
            GaitAuth.getInstance().registerListener(new FeatureEventListener() {
                @Override
                public void onNewFeature(GaitFeature feature) {
                    if (trainingStatus == GaitModel.Status.READY) {
                        // We are working with a trained model. This feature can be used for authentication
                        GaitQuantileConfig config = new GaitQuantileConfig(QUANTILE_THRESHOLD);
                        try {
                            gaitAuthenticator = GaitAuth.getInstance().createAuthenticator(config, gaitModel);
                        }catch(GaitAuthException exc)
                        {

                        }
                    }
                    synchronized (gaitFeatureList) {
                        gaitFeatureList.add(feature);
                    }

                    if (gaitFeatureList.size() > MIN_SCORE_COUNT) {
                        Vector<GaitFeature> testFeatures = new Vector<GaitFeature>();
                        testFeatures.addAll(gaitFeatureList.size() - MIN_SCORE_COUNT, gaitFeatureList);
                        updateTrainingScore(testFeatures);
                    }


                    if (gaitFeatureList.size() == 1) {
                        postUpdate("First Feature collected");
                    } else if (gaitFeatureList.size() % FEATURES_TO_HOLD == 0) {
                        postUpdate(String.format("Collected feature %d ", gaitFeatureList.size()));
                    }

                    if (gaitFeatureList.size() >= FEATURES_TO_HOLD) {
                        saveFeatures();
                    }
                }
            });
        } catch (GaitAuthException exc) {
            exc.printStackTrace();
        }
    }
}
