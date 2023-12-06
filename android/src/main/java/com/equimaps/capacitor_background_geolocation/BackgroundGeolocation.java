package com.equimaps.capacitor_background_geolocation;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Scanner;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import androidx.annotation.NonNull;
import android.os.Handler;
import android.os.Looper;



import com.getcapacitor.JSObject;
import com.getcapacitor.Logger;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.android.BuildConfig;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import org.json.JSONException;
import org.json.JSONObject;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.GeoPoint;




@NativePlugin(
        permissions={
                // As of API level 31, the coarse permission MUST accompany
                // the fine permission.
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
        },
        // A random integer which is hopefully unique to this plugin.
        permissionRequestCode = 28351
)
public class BackgroundGeolocation extends Plugin {
    private FirebaseFirestore db = FirebaseFirestore.getInstance();
    private PluginCall callPendingPermissions = null;
    private Boolean stoppedWithoutPermissions = false;
    private String sessionId = null;
    private String w3wAPIKey = null;

    private void fetchLastLocation(PluginCall call) {
        try {
            LocationServices.getFusedLocationProviderClient(
                    getContext()
            ).getLastLocation().addOnSuccessListener(
                    getActivity(),
                    new OnSuccessListener<Location>() {
                        @Override
                        public void onSuccess(Location location) {
                            if (location != null) {
                                call.resolve(formatLocation(location));
                            }
                        }
                    }
            );
        } catch (SecurityException ignore) {}
    }

    @PluginMethod(returnType=PluginMethod.RETURN_CALLBACK)
    public void addWatcher(final PluginCall call) {
        if (service == null) {
            call.reject("Service not running.");
            return;
        }
        call.setKeepAlive(true);
        sessionId = call.getString("sessionId");
        w3wAPIKey = call.getString("w3wAPIKey");
        if (!hasRequiredPermissions()) {
            if (call.getBoolean("requestPermissions", true)) {
                callPendingPermissions = call;
                pluginRequestAllPermissions();
            } else {
                call.reject("Permission denied.", "NOT_AUTHORIZED");
            }
        } else if (!isLocationEnabled(getContext())) {
            call.reject("Location services disabled.", "NOT_AUTHORIZED");
        }
        if (call.getBoolean("stale", false)) {
            fetchLastLocation(call);
        }
        Notification backgroundNotification = null;
        String backgroundMessage = call.getString("backgroundMessage");
        if (backgroundMessage != null) {
            Notification.Builder builder = new Notification.Builder(getContext())
                    .setContentTitle(
                            call.getString(
                                "backgroundTitle",
                                "Using your location"
                            )
                    )
                    .setContentText(backgroundMessage)
                    .setOngoing(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setWhen(System.currentTimeMillis());

            try {
                String name = getAppString(
                        "capacitor_background_geolocation_notification_icon",
                        "mipmap/ic_launcher"
                );
                String[] parts = name.split("/");
                // It is actually necessary to set a valid icon for the notification to behave
                // correctly when tapped. If there is no icon specified, tapping it will open the
                // app's settings, rather than bringing the application to the foreground.
                builder.setSmallIcon(
                        getAppResourceIdentifier(parts[1], parts[0])
                );
            } catch (Exception e) {
                Logger.error("Could not set notification icon", e);
            }

            Intent launchIntent = getContext().getPackageManager().getLaunchIntentForPackage(
                    getContext().getPackageName()
            );
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                builder.setContentIntent(
                        PendingIntent.getActivity(
                                getContext(),
                                0,
                                launchIntent,
                                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        )
                );
            }

            // Set the Channel ID for Android O.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setChannelId(BackgroundGeolocationService.class.getPackage().getName());
            }

            backgroundNotification = builder.build();
        }
        service.addWatcher(
                call.getCallbackId(),
                backgroundNotification,
                call.getFloat("distanceFilter", 0f)
        );
    }

    @Override
    protected void handleRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.handleRequestPermissionsResult(requestCode, permissions, grantResults);
        if (callPendingPermissions == null) {
            return;
        }
        PluginCall call = callPendingPermissions;
        callPendingPermissions = null;
        for (int result : grantResults) {
            if (result == PackageManager.PERMISSION_DENIED) {
                call.reject("User denied location permission", "NOT_AUTHORIZED");
                return;
            }
        }
        if (call.getBoolean("stale", false)) {
            fetchLastLocation(call);
        }
        if (service != null) {
            service.onPermissionsGranted();
            // The handleOnResume method will now be called, and we don't need it to call
            // service.onPermissionsGranted again so we reset this flag.
            stoppedWithoutPermissions = false;
        }
    }

    @PluginMethod()
    public void removeWatcher(PluginCall call) {
        String callbackId = call.getString("id");
        if (callbackId == null) {
            call.reject("Missing id.");
            return;
        }
        service.removeWatcher(callbackId);
        PluginCall savedCall = bridge.getSavedCall(callbackId);
        if (savedCall != null) {
            savedCall.release(bridge);
        }
        call.resolve();
    }

    @PluginMethod()
    public void openSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", getContext().getPackageName(), null);
        intent.setData(uri);
        getContext().startActivity(intent);
        call.resolve();
    }

    // Checks if device-wide location services are disabled
    private static Boolean isLocationEnabled(Context context)
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            return lm != null && lm.isLocationEnabled();
        } else {
            return  (
                    Settings.Secure.getInt(
                            context.getContentResolver(),
                            Settings.Secure.LOCATION_MODE,
                            Settings.Secure.LOCATION_MODE_OFF
                    ) != Settings.Secure.LOCATION_MODE_OFF
            );

        }
    }

    private static JSObject formatLocation(Location location) {
        JSObject obj = new JSObject();
        obj.put("latitude", location.getLatitude());
        obj.put("longitude", location.getLongitude());
        // The docs state that all Location objects have an accuracy, but then why is there a
        // hasAccuracy method? Better safe than sorry.
        obj.put("accuracy", location.hasAccuracy() ? location.getAccuracy() : JSONObject.NULL);
        obj.put("altitude", location.hasAltitude() ? location.getAltitude() : JSONObject.NULL);
        if (Build.VERSION.SDK_INT >= 26 && location.hasVerticalAccuracy()) {
            obj.put("altitudeAccuracy", location.getVerticalAccuracyMeters());
        } else {
            obj.put("altitudeAccuracy", JSONObject.NULL);
        }
        // In addition to mocking locations in development, Android allows the
        // installation of apps which have the power to simulate location
        // readings in other apps.
        obj.put("simulated", location.isFromMockProvider());
        obj.put("speed", location.hasSpeed() ? location.getSpeed() : JSONObject.NULL);
        obj.put("bearing", location.hasBearing() ? location.getBearing() : JSONObject.NULL);
        obj.put("time", location.getTime());
        return obj;
    }

    // Sends messages to the service.
    private BackgroundGeolocationService.LocalBinder service = null;

    // Receives messages from the service.
    private class ServiceReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String id = intent.getStringExtra("id");
            PluginCall call = bridge.getSavedCall(id);
            if (call == null) {
                return;
            }
            Location location = intent.getParcelableExtra("location");
            if (location == null) {
                if (BuildConfig.DEBUG) {
                    call.error("No locations received");
                }
                return;
            }
            getW3Words(location, sessionId);
            call.success(formatLocation(location));
        }
    }

    // Gets the identifier of the app's resource by name, returning 0 if not found.
    private int getAppResourceIdentifier(String name, String defType) {
        return getContext().getResources().getIdentifier(
                name,
                defType,
                getContext().getPackageName()
        );
    }

    // Gets a string from the app's strings.xml file, resorting to a fallback if it is not defined.
    private String getAppString(String name, String fallback) {
        int id = getAppResourceIdentifier(name, "string");
        return id == 0 ? fallback : getContext().getString(id);
    }

    private void getW3Words(Location location, final String sessionId) {
        // Build the URL
        String urlString = "https://api.what3words.com/v3/convert-to-3wa?coordinates=" +
                           location.getLatitude() + "," + location.getLongitude() + "&key=" + w3wAPIKey;
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            e.printStackTrace();
            return;
        }

        // Make the request in a new thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection urlConnection = null;
                try {
                    urlConnection = (HttpURLConnection) url.openConnection();
                    urlConnection.setRequestMethod("GET");

                    // Get the InputStream
                    InputStream in = new BufferedInputStream(urlConnection.getInputStream());

                    // Convert InputStream to String
                    String response = convertStreamToString(in);

                    // Parse the JSON response
					try {
						JSONObject jsonResponse = new JSONObject(response);
						String words = jsonResponse.optString("words", null);

						// Call updateLocationsArray on the main thread
						new Handler(Looper.getMainLooper()).post(new Runnable() {
							@Override
							public void run() {
								updateLocationsArray(sessionId, location, words);
							}
						});
					} catch (JSONException e) {
						e.printStackTrace();
						// Handle JSON parsing error
					}

                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    if (urlConnection != null) {
                        urlConnection.disconnect();
                    }
                }
            }
        }).start();
    }

    private String convertStreamToString(InputStream is) {
        Scanner s = new Scanner(is).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private void updateLocationsArray(String sessionId, Location location, String w3w) {
        // Get the reference to the document you want to update
        DocumentReference docRef = db.collection("sessions").document(sessionId);

        // Create a new location map
        Map<String, Object> newLocation = new HashMap<>();
        newLocation.put("type", "tracked");
        newLocation.put("timestamp", System.currentTimeMillis());
        GeoPoint geopoint = new GeoPoint(location.getLatitude(), location.getLongitude());
        newLocation.put("geopoint", geopoint);
        newLocation.put("address", "Not available when tracking");
        newLocation.put("w3w", w3w != null ? w3w : "Unable to ascertain");

        // Add location to logs
        Map<String, Object> newLog = new HashMap<>();
        newLog.put("createdBy", "Guardian");
        newLog.put("timestamp", System.currentTimeMillis());
        newLog.put("text", "Latest location received: " + location.getLatitude() + ":" + location.getLongitude() + ". ///what3words: " + w3w);

        // Create an updates hashmap
        Map<String, Object> updates = new HashMap<>();
        updates.put("locations", FieldValue.arrayUnion(newLocation));
        updates.put("logs", FieldValue.arrayUnion(newLog));

        // Update the document with the new location
        docRef.update(updates)
            .addOnSuccessListener(new OnSuccessListener<Void>() {
                @Override
                public void onSuccess(Void aVoid) {
                    Log.d("Firestore", "DocumentSnapshot successfully updated!");
                }
            })
            .addOnFailureListener(new OnFailureListener() {
                @Override
                public void onFailure(@NonNull Exception e) {
                    Log.w("Firestore", "Error updating document", e);
                }
            });
    }


    @Override
    public void load() {
        super.load();

        // Android O requires a Notification Channel.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getContext().getSystemService(
                    Context.NOTIFICATION_SERVICE
            );
            NotificationChannel channel = new NotificationChannel(
                    BackgroundGeolocationService.class.getPackage().getName(),
                    getAppString(
                            "capacitor_background_geolocation_notification_channel_name",
                            "Background Tracking"
                    ),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        }

        this.getContext().bindService(
                new Intent(this.getContext(), BackgroundGeolocationService.class),
                new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder binder) {
                        BackgroundGeolocation.this.service = (BackgroundGeolocationService.LocalBinder) binder;
                    }

                    @Override
                    public void onServiceDisconnected(ComponentName name) {
                    }
                },
                Context.BIND_AUTO_CREATE
        );

        LocalBroadcastManager.getInstance(this.getContext()).registerReceiver(
                new ServiceReceiver(),
                new IntentFilter(BackgroundGeolocationService.ACTION_BROADCAST)
        );
    }

    @Override
    protected void handleOnResume() {
        if (service != null) {
            service.onActivityStarted();
            if (stoppedWithoutPermissions && hasRequiredPermissions()) {
                service.onPermissionsGranted();
            }
        }
        super.handleOnResume();
    }

    @Override
    protected void handleOnPause() {
        if (service != null) {
            service.onActivityStopped();
        }
        stoppedWithoutPermissions = !hasRequiredPermissions();
        super.handleOnPause();
    }

    @Override
    protected void handleOnDestroy() {
        if (service != null) {
            service.stopService();
        }
        super.handleOnDestroy();
    }
}
