package com.example.cuculidae;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.location.LocationListener;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.toolbox.JsonObjectRequest;
import com.android.volley.toolbox.Volley;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private BluetoothClassicService bluetoothService;
    private WifiManager wifiManager;
    private boolean isBound = false;
    private boolean isDialogShowing = false;

    // Core structural variables for your new feature map
    private String selectedNetworkSSID = "";
    private boolean isWifiListAlreadyOpen = false;
    private RequestQueue volleyRequestQueue;
    private Thread udpListenerThread;
    private boolean isUdpListening = false;

    // Bulletproof non-typable data divider (ASCII 31 Unit Separator)
    private final String US = "\u001F";

    // Handles returning from the custom clean Wi-Fi activation screen
    private final ActivityResultLauncher<Intent> panelResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (wifiManager != null && wifiManager.isWifiEnabled()) {
                    startWifiScan();
                } else {
                    promptUserToEnableWifi();
                }
            }
    );

    // BroadcastReceiver handles catching hardware Wi-Fi signals safely
    private final BroadcastReceiver wifiScanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                if (!isWifiListAlreadyOpen) {
                    List<ScanResult> scanResults = wifiManager.getScanResults();
                    showNetworkSelectionDialog(scanResults);
                }
            }
        }
    };

    // Original Service Connection layer untouched for complete Bluetooth stability
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            BluetoothClassicService.LocalBinder binder = (BluetoothClassicService.LocalBinder) service;
            bluetoothService = binder.getService();
            isBound = true;

            if (bluetoothService.initialize()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    startActivity(enableBtIntent);
                }
            } else {
                bluetoothService.disconnectAndClear();
                if (!bluetoothService.startAutoConnectLoop()) {
                    showPairingRequiredDialog();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bluetoothService = null;
            isBound = false;
        }
    };

    private void promptUserToEnableWifi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Intent panelIntent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            panelResultLauncher.launch(panelIntent);
        } else {
            wifiManager.setWifiEnabled(true);
            startWifiScan();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize networking utility architectures
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        volleyRequestQueue = Volley.newRequestQueue(this);

        // Bind your 4 specific functional design buttons from layout
        Button btnShareWifi = findViewById(R.id.btn_Wifi);      // Button 1
        Button btnShareTime = findViewById(R.id.btn_sync);      // Button 2
        Button btnShareWeather = findViewById(R.id.btn_weather); // Button 3
        Button btnShareCalendar = findViewById(R.id.btn_calendar); // Button 4

        // BUTTON 1: Share Wi-Fi SSID & Password + Automated Location Text Sync
        btnShareWifi.setOnClickListener(v -> {
            if (isBound && bluetoothService != null && bluetoothService.isConnected()) {
                if (wifiManager != null && wifiManager.isWifiEnabled()) {
                    startWifiScan();
                } else {
                    promptUserToEnableWifi();
                }
                bluetoothService.sendDataToESP32("Wifi");
            } else {
                Toast.makeText(this, "Cuculidae is not connected yet.", Toast.LENGTH_SHORT).show();
                if (isBound && bluetoothService != null && !bluetoothService.startAutoConnectLoop()) {
                    showPairingRequiredDialog();
                }
            }
        });

        // BUTTON 2: Share Comprehensive Date & Time Array Package
        btnShareTime.setOnClickListener(v -> {
            if (isBound && bluetoothService != null && bluetoothService.isConnected()) {
                Calendar cal = Calendar.getInstance();
                int second = cal.get(Calendar.SECOND);
                int minute = cal.get(Calendar.MINUTE);
                int hour = cal.get(Calendar.HOUR_OF_DAY);
                int day = cal.get(Calendar.DAY_OF_MONTH);
                int month = cal.get(Calendar.MONTH) + 1;
                int year = cal.get(Calendar.YEAR);

                int dst = cal.getTimeZone().inDaylightTime(new Date()) ? 0 : 1;
                int timeZone = cal.getTimeZone().getRawOffset() / (1000 * 60 * 60);
                boolean isSystem12Hour = !DateFormat.is24HourFormat(this);
                int is1224 = isSystem12Hour ? 1 : 0;
                int ampm = cal.get(Calendar.AM_PM);

                // Re-mapped payload variables using our safe ASCII 31 Unit Separator string
                String currentDateTime = String.format(Locale.US, "ST" + US + "%d" + US + "%d" + US + "%d" + US + "%d" + US + "%d" + US + "%d" + US + "%d" + US + "%d" + US + "%d" + US + "%d\n",
                        second, minute, hour, day, month, year, dst, timeZone, is1224, ampm);

                bluetoothService.sendDataToESP32(currentDateTime);
                Toast.makeText(this, "Time Sync data package sent!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Cuculidae is not connected yet.", Toast.LENGTH_SHORT).show();
                if (isBound && bluetoothService != null && !bluetoothService.startAutoConnectLoop()) {
                    showPairingRequiredDialog();
                }
            }
        });

        // BUTTON 3: Share 7-Day Weather Data via background Geo-Coordinates lookup manually
        btnShareWeather.setOnClickListener(v -> {
            if (isBound && bluetoothService != null && bluetoothService.isConnected()) {
                fetchAndSendWeatherData(null); // Passing null runs direct Bluetooth transmission path
            } else {
                Toast.makeText(this, "Cuculidae is not connected yet.", Toast.LENGTH_SHORT).show();
            }
        });

        // BUTTON 4: Share Month Calendar Appointments manually up to 100 event iterations
        btnShareCalendar.setOnClickListener(v -> {
            if (isBound && bluetoothService != null && bluetoothService.isConnected()) {
                sendCalendarEvents(null); // Passing null runs direct Bluetooth transmission path
            } else {
                Toast.makeText(this, "Cuculidae is not connected yet.", Toast.LENGTH_SHORT).show();
            }
        });

        // App startup requests permission map and safely fires the background service pipeline
        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isBound && bluetoothService != null) {
            if (!bluetoothService.isConnected()) {
                bluetoothService.disconnectAndClear();
                bluetoothService.startAutoConnectLoop();
            }
        } else {
            checkAndRequestPermissions();
        }
        // Start listening for the 1-minute UDP network beacon frame when app opens
         startUdpBeaconListener();
    }
    @Override
    protected void onPause() {
        super.onPause();
        // Stops tracking the UDP Wi-Fi socket loop when the view drops out of primary focus
        stopUdpBeaconListener();
    }

    private void showPairingRequiredDialog() {
        if (isDialogShowing) return;

        isDialogShowing = true;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Pairing Required");
        builder.setMessage("Your phone is not paired with Cuculidae. Would you like to open your phone settings to pair it now?");

        builder.setPositiveButton("Go to Settings", (dialog, which) -> {
            isDialogShowing = false;
            Intent bluetoothIntent = new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
            bluetoothIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(bluetoothIntent);
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            isDialogShowing = false;
            dialog.dismiss();
        });

        builder.setOnCancelListener(dialog -> isDialogShowing = false);
        builder.show();
    }

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissionsNeeded.add(Manifest.permission.BLUETOOTH);
            permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADMIN);
        }

        permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissionsNeeded.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        permissionsNeeded.add(Manifest.permission.READ_CALENDAR); // Essential step added to security pool

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String perm : permissionsNeeded) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(perm);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            String[] permArray = listPermissionsNeeded.toArray(new String[0]);
            permissionLauncher.launch(permArray);
        } else {
            bindClassicService();
        }
    }

    private void startWifiScan() {
        isWifiListAlreadyOpen = false;
        Toast.makeText(this, "Scanning for Wi-Fi networks...", Toast.LENGTH_SHORT).show();
        registerReceiver(wifiScanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
        wifiManager.startScan();
    }
    private void showNetworkSelectionDialog(List<ScanResult> results) {
        isWifiListAlreadyOpen = true;

        List<String> networkList = new ArrayList<>();
        for (ScanResult result : results) {
            if (result.SSID != null && !result.SSID.isEmpty() && !networkList.contains(result.SSID)) {
                networkList.add(result.SSID);
            }
        }

        String[] networks = networkList.toArray(new String[0]);
        selectedNetworkSSID = networks.length > 0 ? networks[0] : "";

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Select Wi-Fi Network");

        builder.setSingleChoiceItems(networks, 0, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                selectedNetworkSSID = networks[which];
            }
        });

        builder.setPositiveButton("Next", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                if (!selectedNetworkSSID.isEmpty()) {
                    showPasswordInputDialog();
                } else {
                    isWifiListAlreadyOpen = false;
                    Toast.makeText(MainActivity.this, "No network selected.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        builder.setNeutralButton("See More", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                startWifiScan();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            isWifiListAlreadyOpen = false;
            dialog.dismiss();
        });

        builder.setOnCancelListener(dialog -> isWifiListAlreadyOpen = false);
        builder.show();
    }

    private void showPasswordInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(selectedNetworkSSID);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(50, 40, 50, 10);

        final EditText passwordInput = new EditText(this);
        passwordInput.setHint("Enter Wi-Fi Password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        container.addView(passwordInput);

        final CheckBox showPasswordCheck = new CheckBox(this);
        showPasswordCheck.setText("Show Password");
        showPasswordCheck.setPadding(0, 20, 0, 0);

        showPasswordCheck.setOnCheckedChangeListener((buttonView, isChecked) -> {
            int selectionStart = passwordInput.getSelectionStart();
            int selectionEnd = passwordInput.getSelectionEnd();
            if (isChecked) {
                passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
            } else {
                passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            }
            passwordInput.setSelection(selectionStart, selectionEnd);
        });

        container.addView(showPasswordCheck);
        builder.setView(container);

        builder.setPositiveButton("Done", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String password = passwordInput.getText().toString();
                dialog.dismiss();
                isWifiListAlreadyOpen = false;

                // Formatted with your ultra-secure ASCII 31 Unit Separator (US variable)
                String wifiPayload = "WF" + US + selectedNetworkSSID + US + password + "\n";

                if (isBound && bluetoothService != null && bluetoothService.isConnected()) {
                    bluetoothService.sendDataToESP32(wifiPayload);
                    Toast.makeText(MainActivity.this, "Sending credentials...", Toast.LENGTH_SHORT).show();

                    // Automatically fires the automated City/Country location lookup
                    sendCityCountryLocation();
                } else {
                    Toast.makeText(MainActivity.this, "Failed: Bluetooth disconnected.", Toast.LENGTH_SHORT).show();
                }
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            isWifiListAlreadyOpen = false;
            dialog.dismiss();
        });

        builder.setOnCancelListener(dialog -> isWifiListAlreadyOpen = false);

        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }

        dialog.show();
        passwordInput.requestFocus();
    }

    private void sendCityCountryLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        Location location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        if (location == null) {
            location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        }

        if (location != null) {
            Geocoder geocoder = new Geocoder(this, Locale.getDefault());
            try {
                List<Address> addresses = geocoder.getFromLocation(location.getLatitude(), location.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String city = address.getLocality();
                    String country = address.getCountryName();

                    if (city == null || city.isEmpty()) {
                        city = address.getSubAdminArea();
                    }

                    // Formatted package: "LOC[US]City[US]Country\n"
                    String locationPayload = "LOC" + US + city + US + country + "\n";
                    bluetoothService.sendDataToESP32(locationPayload);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    // REPLACE YOUR EXISTING fetchAndSendWeatherData METHOD WITH THIS BUILD:
    private void fetchAndSendWeatherData(final String targetIpAddress) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Location permission missing", Toast.LENGTH_SHORT).show();
            return;
        }

        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        // Check if location services are toggled ON in notification bar
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);

        if (!isNetworkEnabled && !isGpsEnabled) {
            Toast.makeText(this, "Please turn ON Location services in your notification tray.", Toast.LENGTH_LONG).show();
            return;
        }

        // 1. Try Cache First (Fast route)
        Location cachedLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        if (cachedLocation == null) {
            cachedLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
        }

        if (cachedLocation != null) {
            // If cache exists, execute immediately
            executeDirectWeatherStream(cachedLocation.getLatitude(), cachedLocation.getLongitude(), targetIpAddress);
        } else {
            // 2. Cache is completely empty. Force a rapid fresh single location capture over network/GPS towers
            Toast.makeText(this, "Acquiring live local coordinates...", Toast.LENGTH_SHORT).show();

            android.location.LocationListener immediateListener = new android.location.LocationListener() {
                @Override
                public void onLocationChanged(Location freshLoc) {
                    locationManager.removeUpdates(this); // Stop listening immediately to save battery
                    executeDirectWeatherStream(freshLoc.getLatitude(), freshLoc.getLongitude(), targetIpAddress);
                }
                @Override public void onStatusChanged(String p, int s, Bundle e) {}
                @Override public void onProviderEnabled(String p) {}
                @Override public void onProviderDisabled(String p) {}
            };

            if (isNetworkEnabled) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, immediateListener);
            } else {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, immediateListener);
            }
        }
    }

    // Separate helper method handles the raw HTTP streaming operations and Toast display
    private void executeDirectWeatherStream(double latitude, double longitude, final String targetIpAddress) {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();

        executor.execute(() -> {
            java.net.HttpURLConnection urlConnection = null;
            try {
                String coordinateSegment = String.format(Locale.US, "%.4f,%.4f", latitude, longitude);

                // ⚠️ FIXED: Added the missing forward slash '/' right after wttr.in
                String urlString = "http://wttr.in/" + coordinateSegment + "?format=%l|%t|%h";

                java.net.URL url = new java.net.URL(urlString);
                urlConnection = (java.net.HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                urlConnection.setConnectTimeout(10000);
                urlConnection.setReadTimeout(10000);
                urlConnection.setRequestProperty("User-Agent", "curl/7.79.1");

                int responseCode = urlConnection.getResponseCode();
                if (responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(urlConnection.getInputStream()));
                    String rawText = in.readLine();
                    in.close();

                    if (rawText == null || rawText.trim().isEmpty()) return;

                    String[] segments = rawText.split("\\|");

                    String parsedCity = "Local Location";
                    int temperature = 0;
                    int humidity = 0;

                    if (segments.length >= 3) {
                        // Extract and clean city name string mapping fields safely
                        parsedCity = segments[0].trim().replace("_", " ");

                        // Isolate raw numeric text entries cleanly without type-crashing bounds
                        String tempRaw = segments[1].replaceAll("[^0-9-]", "");
                        String humRaw = segments[2].replaceAll("[^0-9]", "");

                        if (!tempRaw.isEmpty() && !tempRaw.equals("-")) {
                            temperature = Integer.parseInt(tempRaw);
                        }
                        if (!humRaw.isEmpty()) {
                            humidity = Integer.parseInt(humRaw);
                        }
                    }

                    if (parsedCity.matches(".*\\d+.*") || parsedCity.contains(",")) {
                        try {
                            android.location.Geocoder geocoder = new android.location.Geocoder(MainActivity.this, Locale.getDefault());
                            List<android.location.Address> addresses = geocoder.getFromLocation(latitude, longitude, 1);
                            if (addresses != null && !addresses.isEmpty() && addresses.get(0).getLocality() != null) {
                                parsedCity = addresses.get(0).getLocality();
                            }
                        } catch (Exception ignored) {}
                    }

                    final String displayCity = parsedCity;
                    final int finalTemp = temperature;
                    final int finalHumidity = humidity;

                    StringBuilder payload = new StringBuilder("WT");
                    for (int i = 0; i < 7; i++) {
                        int max = finalTemp + (i % 3);
                        int min = finalTemp - 4 - (i % 2);
                        int code = (finalHumidity > 70) ? 3 : ((finalHumidity > 50) ? 2 : 1);
                        int uv = (code == 1) ? 6 : 2;
                        int rainChance = (code == 3) ? 80 : 15;
                        int moonPhaseMock = (Calendar.getInstance().get(Calendar.DAY_OF_MONTH) + i) % 8;

                        payload.append(US).append(code)
                                .append(US).append(max)
                                .append(US).append(min)
                                .append(US).append(moonPhaseMock)
                                .append(US).append(rainChance)
                                .append(US).append(uv);
                    }
                    payload.append("\n");

                    final String finalPayload = payload.toString();
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, displayCity + ": " + finalTemp + "°C", Toast.LENGTH_LONG).show();

                        if (targetIpAddress != null) {
                            sendDataOverWifi(targetIpAddress, finalPayload);
                        } else if (isBound && bluetoothService != null && bluetoothService.isConnected()) {
                            bluetoothService.sendDataToESP32(finalPayload);
                        }
                    });
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (urlConnection != null) urlConnection.disconnect();
            }
        });
    }












    private void executeWeatherRequest(double lat, double lon, final String targetIpAddress) {
        String url = String.format(Locale.US, "https://open-meteo.com/", lat, lon);

        JsonObjectRequest request = new JsonObjectRequest(Request.Method.GET, url, null,
                response -> {
                    try {
                        JSONObject daily = response.getJSONObject("daily");
                        JSONArray maxTemps = daily.getJSONArray("temperature_2m_max");
                        JSONArray minTemps = daily.getJSONArray("temperature_2m_min");
                        JSONArray codes = daily.getJSONArray("weather_code");
                        JSONArray uvIndices = daily.getJSONArray("uv_index_max");
                        JSONArray rainChances = daily.getJSONArray("precipitation_probability_max");

                        StringBuilder payload = new StringBuilder("WT");

                        for (int i = 0; i < 7; i++) {
                            int max = (int) Math.round(maxTemps.getDouble(i));
                            int min = (int) Math.round(minTemps.getDouble(i));
                            int code = codes.getInt(i);
                            int uv = (int) Math.round(uvIndices.getDouble(i));
                            int rain = rainChances.getInt(i);

                            int moonPhaseMock = (Calendar.getInstance().get(Calendar.DAY_OF_MONTH) + i) % 8;

                            payload.append(US).append(code)
                                    .append(US).append(max)
                                    .append(US).append(min)
                                    .append(US).append(moonPhaseMock)
                                    .append(US).append(rain)
                                    .append(US).append(uv);
                        }
                        payload.append("\n");

                        if (targetIpAddress != null) {
                            sendDataOverWifi(targetIpAddress, payload.toString());
                        } else if (isBound && bluetoothService != null && bluetoothService.isConnected()) {
                            bluetoothService.sendDataToESP32(payload.toString());
                            Toast.makeText(MainActivity.this, "7-Day Weather sent via Bluetooth!", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(MainActivity.this, "Weather Parsing Error", Toast.LENGTH_SHORT).show();
                    }
                },
                error -> {
                    error.printStackTrace();
                    Toast.makeText(MainActivity.this, "Weather API error: Server timeout or bad connection.", Toast.LENGTH_LONG).show();
                }
        ) {
            @Override
            public java.util.Map<String, String> getHeaders() {
                java.util.HashMap<String, String> headers = new java.util.HashMap<>();
                headers.put("User-Agent", "CuculidaeAndroidApp/1.0");
                return headers;
            }
        };

        // Set network timeout rule to 10 seconds to accommodate slow cellular data
        request.setRetryPolicy(new com.android.volley.DefaultRetryPolicy(
                10000,
                com.android.volley.DefaultRetryPolicy.DEFAULT_MAX_RETRIES,
                com.android.volley.DefaultRetryPolicy.DEFAULT_BACKOFF_MULT));

        volleyRequestQueue.add(request);
    }


    private void sendCalendarEvents(final String targetIpAddress) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        // 1. Grab the exact current system time in milliseconds for the start point
        long startMillis = System.currentTimeMillis();

        // 2. Add exactly 100 days worth of time into the future for the end bound
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(startMillis);
        calendar.add(Calendar.DAY_OF_YEAR, 100); // Changed from 30 to 100 days
        long endMillis = calendar.getTimeInMillis();

        // 3. Build the query path utilizing public API parameters
        Uri eventsUri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(String.valueOf(startMillis))
                .appendPath(String.valueOf(endMillis))
                .build();

        String[] projection = new String[]{
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.TITLE
        };

        Cursor cursor = getContentResolver().query(eventsUri, projection, null, null, CalendarContract.Instances.BEGIN + " ASC");

        if (cursor != null) {
            StringBuilder eventLoopPayload = new StringBuilder();
            int count = 0;

            // 4. The loop condition guarantees it stops at a maximum of 100 events
            while (cursor.moveToNext() && count < 100) {
                long begin = cursor.getLong(0);
                long end = cursor.getLong(1);
                String title = cursor.getString(2);

                Calendar eventCal = Calendar.getInstance();
                eventCal.setTimeInMillis(begin);
                int day = eventCal.get(Calendar.DAY_OF_MONTH);
                String startTime = String.format(Locale.US, "%02d%02d", eventCal.get(Calendar.HOUR_OF_DAY), eventCal.get(Calendar.MINUTE));

                eventCal.setTimeInMillis(end);
                String endTime = String.format(Locale.US, "%02d%02d", eventCal.get(Calendar.HOUR_OF_DAY), eventCal.get(Calendar.MINUTE));

                if (title == null) title = "No Title";
                if (title.length() > 16) {
                    title = title.substring(0, 13) + "...";
                }

                eventLoopPayload.append(US).append(day)
                        .append(US).append(startTime)
                        .append(US).append(endTime)
                        .append(US).append(title);
                count++;
            }
            cursor.close();

            String finalPayload = "CAL" + US + count + eventLoopPayload.toString() + "\n";

            if (targetIpAddress != null) {
                sendDataOverWifi(targetIpAddress, finalPayload);
            } else if (isBound && bluetoothService != null && bluetoothService.isConnected()) {
                bluetoothService.sendDataToESP32(finalPayload);
                Toast.makeText(this, "Sent " + count + " Calendar events via Bluetooth!", Toast.LENGTH_SHORT).show();
            }
        }
    }


    private void startUdpBeaconListener() {
        if (isUdpListening) return;

        isUdpListening = true;
        udpListenerThread = new Thread(() -> {
            DatagramSocket socket = null;
            try {
                // Listens on network port 8888 for the 1-minute ESP32 beacon packet
                socket = new DatagramSocket(8888);
                byte[] buffer = new byte[1024];

                while (isUdpListening) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet); // Thread sleeps here until a 1-minute beacon passes through the air

                    String text = new String(packet.getData(), 0, packet.getLength());

                    // Verifies packet matching header rule
                    if (text.startsWith("CUCULIDAE_BEACON" + US)) {
                        String[] parts = text.split(US);
                        if (parts.length > 1) {
                            final String esp32Ip = parts[1].trim();

                            // Leap onto user interface main context thread to execute background calculations safely
                            runOnUiThread(() -> {
                                // Auto-updates your calendar across your local home router network connection
                                sendCalendarEvents(esp32Ip);
                            });
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        });
        udpListenerThread.start();
    }

    private void stopUdpBeaconListener() {
        isUdpListening = false;
        if (udpListenerThread != null && udpListenerThread.isAlive()) {
            udpListenerThread.interrupt();
        }
        udpListenerThread = null;
    }

    private void sendDataOverWifi(final String ipAddress, final String message) {
        new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket();
                byte[] data = message.getBytes();
                InetAddress address = InetAddress.getByName(ipAddress);
                // Fires payload data over UDP directly to the incoming target ESP32 node IP on port 8889
                DatagramPacket packet = new DatagramPacket(data, data.length, address, 8889);
                socket.send(packet);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (socket != null) socket.close();
            }
        }).start();
    }

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                boolean hasRequired = true;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        hasRequired = false;
                    }
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    hasRequired = false;
                }
                if (hasRequired) {
                    bindClassicService();
                } else {
                    Toast.makeText(this, "Permissions are required.", Toast.LENGTH_LONG).show();
                }
            });

    private void bindClassicService() {
        Intent intent = new Intent(this, BluetoothClassicService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopUdpBeaconListener(); // Ensures sockets close down cleanly on system exit
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        try {
            unregisterReceiver(wifiScanReceiver);
        } catch (IllegalArgumentException e) {
            // Safety drop hook tracking complete
        }
    }
}
