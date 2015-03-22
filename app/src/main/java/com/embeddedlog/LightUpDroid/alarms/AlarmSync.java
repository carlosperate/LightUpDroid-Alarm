/*
 * Copyright (C) 2015 carlosperate
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

import android.app.ProgressDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;

import com.embeddedlog.LightUpDroid.R;
import com.embeddedlog.LightUpDroid.SettingsActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Synchronises the Alarms with the LightUpPi server alarms by providing methods to retrieve, edit
 * add and delete alarms on the server.
 * TODO: This class is still under work, for now it retrieves the JSON data and prints it to the
 *       log. Committed before changes to the Alarm class are performed to integrate it with
 *       AlarmSync.
 */
public class AlarmSync {
    private static final String DEBUG_TAG = "AlarmSync";
    private Context mContext;

    // This progress dialog is display in the input context during sycn
    private ProgressDialog progress;

    /**
     * Public constructor. Saves the class context to be able to check the network connectivity
     * and display a progress dialog.
     * @param mContext Context of the activity requesting the sync.
     */
    public AlarmSync(Context mContext){
        this.mContext = mContext;
    }

    /**
     * Gets the LightUpPi server IP from the settings and returns the server address string.
     * @return Sever address to the LightUpPi app root folder.
     */
    private String getServerAddress() {
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(mContext);
        String serverIP = prefs.getString(SettingsActivity.KEY_LIGHTUPPI_SERVER, "");
        // The LightUpPi server application runs through the LightUpPi directory
        String serverAddress = "http://" + serverIP + "/LightUpPi/";
        Log.d(DEBUG_TAG, "LightUpPi app address: " + serverAddress);
        return serverAddress;
    }

    /**
     * Gets an alarm from the LightUpPi server.
     * @param alarmId LightUpPi Alarm ID of the alarm to retrieve.
     */
    public void getAlarm(int alarmId) {
        String url = getServerAddress() + "alarm?action=get&id=" + alarmId;
        getJsonHandler(url);
    }

    /**
     * Deletes an alarm from the LightUpPi server.
     * @param alarmId LightUpPi Alarm ID of the alarm to retrieve.
     */
    public void deleteAlarm(int alarmId) {
        String url = getServerAddress() + "alarm?action=delete&id=" + alarmId;
        getJsonHandler(url);
    }

    /**
     * Gets all the Alarms from the LightUpPi sever.
     */
    public void getAllAlarms() {
        String url = getServerAddress() + "alarms";
        getJsonHandler(url);
    }

    /**
     * Every request is handled by this method, which launches an async task to retrieve the data.
     * Before attempting to fetch the URL, makes sure that there is a network connection.
     * @param urlString The URL of the JSON data to retrieve.
     * @return String with the JSON data, or null if there is no network connectivity
     */
    private void getJsonHandler(String urlString) {
        // We need the context manager and
        ConnectivityManager connMgr = (ConnectivityManager)
                mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        if (networkInfo != null && networkInfo.isConnected()) {
            new DownloadJsonTask().execute(urlString);
        } else {
            // TODO: probably do the callback with a 'no network connection' error message.
        }
    }


    /**
     * Uses AsyncTask to create a task away from the main UI thread, where the wrapper class is
     * called from. This task takes a URL string and uses it to create an HttpUrlConnection.
     * Once the connection has been established, the AsyncTask downloads the contents of the
     * webpage as an InputStream. Finally, the InputStream is converted into a string, which is
     * displayed in the UI by the AsyncTask's onPostExecute method.
     */
    private class DownloadJsonTask extends AsyncTask<String, Void, String> {
        /**
         * Launches the progress dialog while the data is being retrieved. Is is dismissed on
         * onPostExecute.
         */
        @Override
        protected void onPreExecute() {
            progress = ProgressDialog.show(
                    mContext,
                    mContext.getString(R.string.lightuppi_syncing_title),
                    mContext.getString(R.string.lightuppi_syncing_body),
                    true);
        }

        /**
         * @param urls String array with the URL to retrive JSON from, only first array item used.
         * @return JSON in string format.
         */
        @Override
        protected String doInBackground(String... urls) {
            String jsonStr = null;
            try {
                // Only expecting 1 url parameter, overwrite requires the array to be maintained
                jsonStr = getJsonFrom(urls[0]);
            } catch (IOException e) {
                // We'll deal with the error in onPostExecute, pass through as null string
            }
            return jsonStr;
        }

        /**
         * onPostExecute closes the progress dialog and converts the data to JSONArray
         * @param result
         */
        @Override
        protected void onPostExecute(String result) {
            // Close the progress dialog, and for debugging print data in log
            progress.dismiss();
            Log.d(DEBUG_TAG, "json: " + result);

            if (result == null) {
                // Deal with the error
                ;
            } else {
                //parse JSON data
                try {

                    JSONObject wrapperJsonObject = new JSONObject(result);
                    JSONArray alarmsJsonArray = wrapperJsonObject.getJSONArray("alarms");
                    //alarmsJsonArray.get(i);
                } catch (JSONException e) {
                    Log.e("JSONException", "Error: " + e.toString());
                }
            }
        }

        /**
         * Given a URL, establishes an HttpUrlConnection and retrieves the content as a
         * InputStream, which it returns as a string.
         * @param myurl String array containing as the first argument the URL to retrieve data from.
         * @return String with the URL data
         * @throws IOException
         */
        private String getJsonFrom(String myurl) throws IOException {
            InputStream is = null;
            try {
                URL url = new URL(myurl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setReadTimeout(10000);    /* milliseconds */
                conn.setConnectTimeout(15000); /* milliseconds */
                conn.setRequestMethod("GET");
                conn.setDoInput(true);

                // Starts the query
                conn.connect();
                int response = conn.getResponseCode();
                Log.d(DEBUG_TAG, "The response is: " + response);
                // TODO: Check the response, if not 200 we want to inform the user the server was
                //       reached but the data was not as expected, probably not running LightUpPi

                // Get and convert the InputStream into a string
                is = conn.getInputStream();
                String contentAsString = readStream(is);
                return contentAsString;
            } finally {
                // Ensure InputStream is closed after the app is finished using it.
                if (is != null) {
                    is.close();
                }
            }
        }

        /**
         * Converts the input stream from the web content into an String.
         * @param stream InputStream to be converted into String.
         * @return String with the stream parameter data.
         * @throws IOException
         */
        private String readStream(InputStream stream) throws IOException {
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
}
