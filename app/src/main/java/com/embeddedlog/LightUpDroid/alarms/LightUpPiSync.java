/*
 * Copyright (C) 2015 carlosperate http://carlosperate.github.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embeddedlog.LightUpDroid.alarms;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.widget.Toast;

import com.embeddedlog.LightUpDroid.AlarmClockFragment;
import com.embeddedlog.LightUpDroid.Log;
import com.embeddedlog.LightUpDroid.R;
import com.embeddedlog.LightUpDroid.SettingsActivity;
import com.embeddedlog.LightUpDroid.provider.Alarm;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Synchronises the Alarms with the LightUpPi server alarms by providing methods to retrieve, edit,
 * add and delete alarms on the server.
 */
public class LightUpPiSync {
    private static final String LOG_TAG = "LightUpPiSync: ";

    private Context mActivityContext;
    private AlarmClockFragment mAlarmFragment;

    // Used to schedule a permanently running background LightUpPi server check
    private ScheduledExecutorService scheduleServerCheck;

    // Defines the types of tasks that is required to be performed
    public enum TaskType {
        SYNC,
        PUSH_TO_PHONE,
        PUSH_TO_SERVER,
        GET_ALARM,
        ADD_ALARM,
        EDIT_ALARM,
        DELETE_ALARM,
    }

    /**
     * Public constructor. Saves the class context to be able to check the network connectivity
     * and display a progress dialog.
     *
     * @param activityContext Context of the activity (no application context) requesting the sync.
     */
    public LightUpPiSync(Context activityContext, String alarmFragmentTag) {
        this.mActivityContext = activityContext;
        Activity activity = (Activity) this.mActivityContext;
        this.mAlarmFragment = (AlarmClockFragment)
                activity.getFragmentManager().findFragmentByTag(alarmFragmentTag);
    }

    /**
     * Synchronisation procedure to push all alarms from the LightUpPi server onto the phone.
     */
    public void syncPushToPhone() {
        Uri.Builder allAlarmsUri= getServerUriBuilder();
        allAlarmsUri.appendPath("getAlarm")
                .appendQueryParameter("id", "all");
        getJsonHandler(allAlarmsUri, TaskType.PUSH_TO_PHONE, Alarm.INVALID_ID);
    }

    private void syncPushToPhoneCallback(JSONObject jAllAlarms) {
        JSONArray jAlarms;
        List<Alarm> serverAlarms = new LinkedList<Alarm>();
        try {
            jAlarms = jAllAlarms.getJSONArray("alarms");
            for (int i=0; i<jAlarms.length(); i++) {
                serverAlarms.add(alarmFromJson(jAlarms.getJSONObject(i)));
                if (Log.LOGV) Log.v("Alarm from server: " + serverAlarms.get(i).toString());
            }
        } catch (Exception  e) {
            if ((e instanceof JSONException) || (e instanceof NullPointerException)) {
                launchToast(R.string.lightuppi_sync_fail);
                Log.w(LOG_TAG + "Exception reading callback from push to phone operation: " + e);
                return;
            } else {
                throw new RuntimeException(e);
            }
        }

        // Get local alarms, passing null as selection argument retrieves all
        ContentResolver cr = mActivityContext.getContentResolver();
        List<Alarm> localAlarms = Alarm.getAlarms(cr, null);

        // Now we have lists of all the alarms, because we are pushing to the phone check the
        // current local alarms and remove them if they are not in the list
        for (Alarm localAlarm: localAlarms) {
            toNextLocalAlarmIteration: {
                for (Alarm serverAlarm : serverAlarms) {
                    if (localAlarm.lightuppiId == serverAlarm.lightuppiId) {
                        // Because we are pushing to the phone update the alarm to whatever is in
                        // the server, including the server timestamp
                        copyAndroidProperties(localAlarm, serverAlarm);
                        mAlarmFragment.asyncUpdateAlarm(serverAlarm, false, true);
                        serverAlarms.remove(serverAlarm);
                        break toNextLocalAlarmIteration;
                    }
                }
                mAlarmFragment.asyncDeleteAlarm(localAlarm, null);
            }
        }
        // The rest of the serverAlarms are new to the phone
        for (Alarm serverAlarm : serverAlarms) {
            mAlarmFragment.asyncAddAlarm(serverAlarm);
        }
    }

    /**
     * Adds an alarm to the LightUpPi server.
     *
     * @param alarm New Alarm to add to LightUpPi server.
     */
    public void addServerAlarm(Alarm alarm) {
        // First check if alarm has no associated LightUpPi server ID
        if (alarm.lightuppiId == Alarm.INVALID_ID) {
            Uri.Builder addAlarmUri= getServerUriBuilder();
            addAlarmUri.appendPath("addAlarm")
                    .appendQueryParameter("hour", Integer.toString(alarm.hour))
                    .appendQueryParameter("minute", Integer.toString(alarm.minutes))
                    .appendQueryParameter("monday",
                            Boolean.toString(alarm.daysOfWeek.isMondayEnabled()))
                    .appendQueryParameter("tuesday",
                            Boolean.toString(alarm.daysOfWeek.isTuesdayEnabled()))
                    .appendQueryParameter("wednesday",
                            Boolean.toString(alarm.daysOfWeek.isWednesdayEnabled()))
                    .appendQueryParameter("thursday",
                            Boolean.toString(alarm.daysOfWeek.isThursdayEnabled()))
                    .appendQueryParameter("friday",
                            Boolean.toString(alarm.daysOfWeek.isFridayEnabled()))
                    .appendQueryParameter("saturday",
                            Boolean.toString(alarm.daysOfWeek.isSaturdayEnabled()))
                    .appendQueryParameter("sunday",
                            Boolean.toString(alarm.daysOfWeek.isSundayEnabled()))
                    .appendQueryParameter("enabled", Boolean.toString(alarm.enabled))
                    .appendQueryParameter("label", alarm.label)
                    .appendQueryParameter("timestamp", Long.toString(alarm.timestamp));
            getJsonHandler(addAlarmUri, TaskType.ADD_ALARM, alarm.id);
        } else {
            launchToast(R.string.lightuppi_add_existing);
        }
    }

    private void addServerAlarmCallback(long alarmID, JSONObject jResult) {
        boolean addSuccess;
        long lightuppiId;
        try {
            addSuccess = jResult.getBoolean("success");
            lightuppiId = jResult.getLong("id");
        } catch (Exception  e) {
            if ((e instanceof JSONException) || (e instanceof NullPointerException)) {
                Log.w(LOG_TAG + "Exception when reading callback from add operation: " + e);
                launchToast(R.string.lightuppi_add_unsuccessful);
                return;
            } else {
                throw new RuntimeException(e);
            }
        }
        if (addSuccess) {
            // We need the alarm back before we can edit the LightUpPi ID
            ContentResolver cr = mActivityContext.getContentResolver();
            Alarm addedAlarm = Alarm.getAlarm(cr, alarmID);
            addedAlarm.lightuppiId = lightuppiId;
            // Last argument causes the bypass of the Alarm.updateAlarm() automatic timestamp
            // and the edit of the alarm in the LightUpPi server
            mAlarmFragment.asyncUpdateAlarm(addedAlarm, false, true);
            launchToast(R.string.lightuppi_add_successful);
        } else {
            launchToast(R.string.lightuppi_add_unsuccessful);
        }
    }

    /**
     * Edits an alarm from the LightUpPi server.
     *
     * @param alarm LightUpPi Alarm to edit.
     */
    public void editServerAlarm(Alarm alarm) {
        // First check if alarm has an associated LightUpPi server ID
        if (alarm.lightuppiId != Alarm.INVALID_ID) {
            Uri.Builder editAlarmUri= getServerUriBuilder();
            editAlarmUri.appendPath("editAlarm")
                    .appendQueryParameter("id", Long.toString(alarm.lightuppiId))
                    .appendQueryParameter("hour", Integer.toString(alarm.hour))
                    .appendQueryParameter("minute", Integer.toString(alarm.minutes))
                    .appendQueryParameter("monday",
                            Boolean.toString(alarm.daysOfWeek.isMondayEnabled()))
                    .appendQueryParameter("tuesday",
                            Boolean.toString(alarm.daysOfWeek.isTuesdayEnabled()))
                    .appendQueryParameter("wednesday",
                            Boolean.toString(alarm.daysOfWeek.isWednesdayEnabled()))
                    .appendQueryParameter("thursday",
                            Boolean.toString(alarm.daysOfWeek.isThursdayEnabled()))
                    .appendQueryParameter("friday",
                            Boolean.toString(alarm.daysOfWeek.isFridayEnabled()))
                    .appendQueryParameter("saturday",
                            Boolean.toString(alarm.daysOfWeek.isSaturdayEnabled()))
                    .appendQueryParameter("sunday",
                            Boolean.toString(alarm.daysOfWeek.isSundayEnabled()))
                    .appendQueryParameter("enabled", Boolean.toString(alarm.enabled))
                    .appendQueryParameter("label", alarm.label);
            getJsonHandler(editAlarmUri, TaskType.EDIT_ALARM, alarm.id);
        } else {
            launchToast(R.string.lightuppi_no_server_ID);
        }
    }

    private void editServerAlarmCallback(JSONObject jResult) {
        boolean editSuccess;
        long lightuppiId;
        long newTimestamp;
        try {
            editSuccess = jResult.getBoolean("success");
            lightuppiId = jResult.getLong("id");
            newTimestamp = jResult.getLong("timestamp");
        } catch (Exception  e) {
            if ((e instanceof JSONException) || (e instanceof NullPointerException)) {
                Log.w(LOG_TAG + "Exception when reading callback from edit operation: " + e);
                launchToast(R.string.lightuppi_edit_unsuccessful);
                return;
            } else {
                throw new RuntimeException(e);
            }
        }
        if (editSuccess) {
            // We need the alarm back before we can edit the timestamp
            ContentResolver cr = mActivityContext.getContentResolver();
            Alarm editedAlarm = Alarm.getAlarmLightuppiId(cr, lightuppiId);
            editedAlarm.timestamp = newTimestamp;
            // Last argument causes the bypass of the Alarm.updateAlarm() automatic timestamp
            // and the edit of the alarm in the LightUpPi server
            mAlarmFragment.asyncUpdateAlarm(editedAlarm, false, true);
            launchToast(R.string.lightuppi_edit_successful);
        } else {
            launchToast(R.string.lightuppi_edit_unsuccessful);
        }
    }

    /**
     * Deletes an alarm from the LightUpPi server.
     *
     * @param alarm LightUpPi Alarm to delete.
     */
    public void deleteServerAlarm(Alarm alarm) {
        // First check if alarm has an associated LightUpPi server ID
        if (alarm.lightuppiId != Alarm.INVALID_ID) {
            Uri.Builder deleteAlarmUri= getServerUriBuilder();
            deleteAlarmUri.appendPath("deleteAlarm")
                    .appendQueryParameter("id", Long.toString(alarm.lightuppiId));
            getJsonHandler(deleteAlarmUri, TaskType.DELETE_ALARM, alarm.id);
        } else {
            launchToast(R.string.lightuppi_no_server_ID);
        }
    }

    private void deleteServerAlarmCallback(JSONObject jResult) {
        boolean deleteSuccess;
        try {
            deleteSuccess = jResult.getBoolean("success");
        } catch (Exception  e) {
            if ((e instanceof JSONException) || (e instanceof NullPointerException)) {
                Log.w(LOG_TAG + "Exception when reading callback from delete operation: " + e);
                launchToast(R.string.lightuppi_delete_unsuccessful);
                return;
            } else {
                throw new RuntimeException(e);
            }
        }
        if (deleteSuccess) {
            launchToast(R.string.lightuppi_delete_successful);
        } else {
            launchToast(R.string.lightuppi_delete_unsuccessful);
        }
    }

    /**
     * Gets the LightUpPi server IP from the settings and returns the server address string.
     *
     * @return Sever address to the LightUpPi app root folder.
     */
    private Uri.Builder getServerUriBuilder() {
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(mActivityContext);
        String serverIP = prefs.getString(SettingsActivity.KEY_LIGHTUPPI_SERVER, "");
        // The LightUpPi server application runs through the LightUpPi directory
        Uri.Builder serverUriBuilder = new Uri.Builder();
        serverUriBuilder.scheme("http")
                .authority(serverIP)
                .appendPath("LightUpPi");
        if (Log.LOGV) Log.v(LOG_TAG + "LightUpPi server " + serverUriBuilder.build().toString());
        return serverUriBuilder;
    }

    /**
     * Every request is handled by this method, which launches an async task to retrieve the data.
     * Before attempting to fetch the URL, makes sure that there is a network connection.
     *
     * @param uriBuilder The URI Builder of the JSON data address to request.
     * @param taskType Indicates which task it is to be performed.
     * @param alarmId If applicable, the Alarm ID (local, not LightUpPi Id) to perform the task.
     */
    private void getJsonHandler(Uri.Builder uriBuilder, TaskType taskType, long alarmId) {
        // First check if there is network connectivity
        ConnectivityManager connMgr = (ConnectivityManager)
                mActivityContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        if (networkInfo != null && networkInfo.isConnected()) {
            String urlString = uriBuilder.build().toString();
            new DownloadJsonTask(taskType, alarmId).execute(urlString);
        } else {
            launchToast(R.string.lightuppi_no_connection);
        }
    }

    /**
     * Uses AsyncTask to create a task away from the main UI thread, where the wrapper class is
     * called from. This task takes a URL string and uses it to create an HttpUrlConnection.
     * Once the connection has been established, the AsyncTask downloads the contents of the
     * web page as an InputStream. Finally, the InputStream is converted into a string, which is
     * displayed in the UI by the AsyncTask's onPostExecute method.
     */
    private class DownloadJsonTask extends AsyncTask<String, Void, JSONObject> {
        private ProgressDialog progress;
        private TaskType mTaskType;
        private long mAlarmId;

        /**
         * Constructor requires a TaskType argument to identify the correct callback.
         *
         * @param taskType The type of task required in order to identify the right callback.
         */
        DownloadJsonTask(TaskType taskType, long alarmId) {
            this.mTaskType = taskType;
            this.mAlarmId = alarmId;
        }

        /**
         * Launches the progress dialog while the data is being retrieved.
         * It is dismissed on onPostExecute.
         */
        @Override
        protected void onPreExecute() {
            ((Activity)mActivityContext).runOnUiThread(new Runnable() {
                public void run() {
                    progress = ProgressDialog.show(
                            mActivityContext, null,
                            mActivityContext.getString(R.string.lightuppi_syncing_message), true);
                }
            });
        }

        /**
         * @param urls String array with the URL to retrieve JSON from, only first array item used.
         * @return JSON from server in JSONObject format.
         */
        @Override
        protected JSONObject doInBackground(String... urls) {
            String jsonStr;
            try {
                // Only expecting 1 url parameter, overwrite requires the array to be maintained
                jsonStr = getJsonFrom(urls[0]);
            } catch (IOException e) {
                Log.w(LOG_TAG + "JSONException: " + e.toString());
                // Error dealt with in the callback from onPostExecute, by passing null object
                return null;
            }
            if (Log.LOGV) Log.v(LOG_TAG + jsonStr);
            JSONObject wrapperJsonObject = null;
            try {
                wrapperJsonObject = new JSONObject(jsonStr);
            } catch (JSONException e) {
                Log.w(LOG_TAG + "JSONException: " + e.toString());
                // Error dealt with in the callback from onPostExecute, by passing null object
            }
            return wrapperJsonObject;
        }

        /** Closes the progress dialog and sends the data to the relevant callback. */
        @Override
        protected void onPostExecute(JSONObject result) {
            // Select callback based on task type
            switch (mTaskType) {
                case SYNC:
                    break;
                case PUSH_TO_SERVER:
                    break;
                case PUSH_TO_PHONE:
                    syncPushToPhoneCallback(result);
                    break;
                case GET_ALARM:
                    break;
                case ADD_ALARM:
                    addServerAlarmCallback(mAlarmId, result);
                    break;
                case EDIT_ALARM:
                    editServerAlarmCallback(result);
                    break;
                case DELETE_ALARM:
                    deleteServerAlarmCallback(result);
                    break;
                default:
                    Log.w(LOG_TAG + "Coding bug, there was no callback defined for the task " +
                            mTaskType.toString());
                    break;
            }
            // Close the progress dialog if applicable, cases need to be the same as onPreExecute
            progress.dismiss();
        }

        /**
         * Given a URL, establishes an HttpUrlConnection and retrieves the content as a
         * InputStream, which it returns as a string.
         *
         * @param urlStr String array containing as the first argument the URL to retrieve data.
         * @return String with the URL data
         * @throws IOException
         */
        private String getJsonFrom(String urlStr) throws IOException {
            InputStream is = null;
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setReadTimeout(10000);    /* milliseconds */
                conn.setConnectTimeout(15000); /* milliseconds */
                conn.setRequestMethod("GET");
                conn.setDoInput(true);

                // Starts the query
                conn.connect();
                int response = conn.getResponseCode();
                if (response == 500) {
                    launchToast(R.string.lightuppi_response_500);
                } else if (response != 200) {
                    launchToast(String.format(
                            mActivityContext.getString(R.string.lightuppi_response_not_200),
                            response));
                }

                // Get and convert the InputStream into a string
                is = conn.getInputStream();
                return stringFromStream(is);
            } finally {
                // Ensure InputStream is closed after the app is finished using it.
                if (is != null) is.close();
            }
        }

        /**
         * Converts the input stream from the web content into an String.
         *
         * @param stream InputStream to be converted into String.
         * @return String with the stream parameter data.
         * @throws IOException
         */
        private String stringFromStream(InputStream stream) throws IOException {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            StringBuilder out = new StringBuilder();
            String newLine = System.getProperty("line.separator");
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
                out.append(newLine);
            }
            return out.toString();
        }
    }

    private Alarm alarmFromJson(JSONObject aJson) {
        // If mSelectedAlarm is null then we're creating a new alarm.
        Alarm alarm = new Alarm();
        alarm.alert = RingtoneManager.getActualDefaultRingtoneUri(
                mActivityContext, RingtoneManager.TYPE_ALARM);
        if (alarm.alert == null) {
            alarm.alert = Uri.parse("content://settings/system/alarm_alert");
        }
        // Setting the vibrate option to always true, as there is no attribute in LightUpPi
        alarm.vibrate = true;
        // Setting the 'delete after use' option to always false, as there is no such feature in
        // the LightUpPi alarm system and all alarms are repeatable
        alarm.deleteAfterUse = false;

        // Parsing the JSON data
        try {
            alarm.hour = aJson.getInt("hour");
            alarm.minutes = aJson.getInt("minute");
            alarm.enabled = aJson.getBoolean("enabled");
            alarm.daysOfWeek.setDaysOfWeek(aJson.getBoolean("monday"), Calendar.MONDAY);
            alarm.daysOfWeek.setDaysOfWeek(aJson.getBoolean("tuesday"), Calendar.TUESDAY);
            alarm.daysOfWeek.setDaysOfWeek(aJson.getBoolean("wednesday"), Calendar.WEDNESDAY);
            alarm.daysOfWeek.setDaysOfWeek(aJson.getBoolean("thursday"), Calendar.THURSDAY);
            alarm.daysOfWeek.setDaysOfWeek(aJson.getBoolean("friday"), Calendar.FRIDAY);
            alarm.daysOfWeek.setDaysOfWeek(aJson.getBoolean("saturday"), Calendar.SATURDAY);
            alarm.daysOfWeek.setDaysOfWeek(aJson.getBoolean("sunday"), Calendar.SUNDAY);
            alarm.label = aJson.getString("label");
            alarm.lightuppiId = aJson.getLong("id");
            alarm.timestamp = aJson.getLong("timestamp");
        } catch (JSONException e) {
            Log.w(LOG_TAG + " JSONException: " + e.toString());
            alarm = null;
        }
        return alarm;
    }

    /**
     * Because the LightUpDrop Alarms have more data than the LightUpPi Alarms this method is used
     * to copy the properties over from one alarm to the other.
     *
     * @param droid Alarm local to the phone.
     * @param pi Alarm coming from the LightUpPi server.
     */
    private void copyAndroidProperties(Alarm droid, Alarm pi) {
        pi.id = droid.id;
        pi.alert = droid.alert;
        pi.vibrate = droid.vibrate;
        pi.deleteAfterUse = droid.deleteAfterUse;
    }

    /**
     * Initiates a background thread to check if the LightUpPi server is reachable.
     *
     * @param guiHandler Handler for the activity GUI, for which to send one of the two runnables.
     * @param online Runnable to execute in the Handler if the server is online.
     * @param offline Runnable to execute in the Handler if the server is offline.
     */
    public void startBackgroundServerCheck(
            final Handler guiHandler, final Runnable online, final Runnable offline) {
        // Check for network connectivity
        ConnectivityManager connMgr = (ConnectivityManager)
                mActivityContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        if ((networkInfo != null) && networkInfo.isConnected() &&
                ((scheduleServerCheck == null) || scheduleServerCheck.isShutdown())) {
            // Get the ping address
            final Uri.Builder pingUri = getServerUriBuilder();
            pingUri.appendPath("ping");
            // Schedule the background server check
            scheduleServerCheck = Executors.newScheduledThreadPool(1);
            scheduleServerCheck.scheduleWithFixedDelay(new Runnable() {
                public void run() {
                    int response = 0;
                    try {
                        URL url = new URL(pingUri.build().toString());
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setReadTimeout(10000);    /* milliseconds */
                        conn.setConnectTimeout(15000); /* milliseconds */
                        conn.setRequestMethod("GET");
                        conn.setDoInput(true);
                        conn.connect();
                        response = conn.getResponseCode();
                    } catch (Exception e) {
                        // Safely ignored as a response!=200 will trigger the offline title
                    }
                    if (response == 200) {
                        if (Log.LOGV) Log.i(LOG_TAG + "Server response 200");
                        guiHandler.post(online);
                    } else {
                        if (Log.LOGV) Log.i(LOG_TAG + "Server response NOT 200");
                        guiHandler.post(offline);
                    }
                }
            }, 0, 30, TimeUnit.SECONDS);
            if (Log.LOGV) Log.v(LOG_TAG + "BackgroundServerCheck started");
        } else {
            if (Log.LOGV) Log.d(LOG_TAG + "Server response NOT 200");
            guiHandler.post(offline);
        }
    }

    /** Stops the background server check */
    public void stopBackgroundServerCheck() {
        try{
            scheduleServerCheck.shutdown();
            if (Log.LOGV) Log.v(LOG_TAG + "BackgroundServerCheck stopped");
        } catch (NullPointerException e) {
            // This will be triggered due to the network being unavailable, safe to ignore
        }
    }

    /** Launches a Toast in the main gui thread */
    private void launchToast(final int resourceId) {
        ((Activity)mActivityContext).runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(mActivityContext, resourceId, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void launchToast(final String toastText) {
        ((Activity)mActivityContext).runOnUiThread(new Runnable() {
            public void run() {
                Toast.makeText(mActivityContext, toastText, Toast.LENGTH_LONG).show();
            }
        });
    }
}
