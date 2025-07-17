package de.danielh.hondae_insight;

import static de.danielh.hondae_insight.IternioApiKeyStore.ITERNIO_API_KEY;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
//import android.util.Log;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

public class CommunicateActivity extends AppCompatActivity implements LocationListener {

    public static final int CAN_BUS_SCAN_INTERVALL = 1000;
    public static final int WAIT_FOR_NEW_MESSAGE_TIMEOUT = 250;
    public static final int WAIT_TIME_BETWEEN_COMMAND_SENDS_MS = 50;
    public static final String VIN_ID = "1862F190";
    public static final String AMBIENT_ID = "39627028";
    public static final String SOH_ID = "F6622021";
    public static final String SOC_ID = "F6622029";
    public static final String BATTEMP_ID = "F662202A";
    public static final String ODO_ID = "39627022";
    public static final int RANGE_ESTIMATE_WINDOW_5KM = 5;
    private static final String USER_TOKEN_PREFS = "abrp_user_token";
    private static final String ITERNIO_SEND_TO_API_SWITCH = "iternioSendToAPISwitch";
    private static final String AUTO_RECONNECT_SWITCH = "autoReconnectSwitch";
    private static final int MAX_RETRY = 5;

    private static final String NOTIFICATION_CHANNEL_ID = "SoC";
    private static final int NOTIFICATION_ID = 23;

    private final ArrayList<String> _connectionCommands = new ArrayList<>(Arrays.asList(
            "ATWS",
            "ATE0",
            "ATSP7",
            "ATAT1",
            "ATH1",
            "ATL0",
            "ATS0",
            "ATRV",
            "ATAL",
            "ATCAF1",
            "ATSHDA01F1",
            "ATFCSH18DA01F1",
            "ATFCSD300000",
            "ATFCSM1",
            "ATCFC1",
            "ATCP18",
            "ATSHDA07F1",
            "ATFCSH18DA07F1",
            "ATCRA18DAF107",
            "22F190" //VIN
    ));

    private final ArrayList<String> _loopCommands = new ArrayList<>(Arrays.asList(
            "ATSHDA60F1",
            "ATFCSH18DA60F1",
            "ATCRA18DAF160",
            "227028", //AMBIENT
            "2270229", //ODO
            "ATSHDA15F1",
            "ATFCSH18DA15F1",
            "ATCRA18DAF115",
            "222021", //SOH VOLT AMP
            "222029", //SOC
            "ATSHDA01F1",
            "ATFCSH18DA01F1",
            "ATCRA18DAF101",
            "22202A", // BATTTEMP
            "ATRV" // AUX BAT
    ));

    private final String LOG_FILE_HEADER = "sysTimeMs,ODO,SoC (dash),SoC (min),SoC (max),SoH,Battemp,Ambienttemp,kW,Amp,Volt,AuxBat,Connection,Charging,Speed,Lat,Lon";
    private TextView _connectionText, _vinText, _messageText, _socMinText, _socMaxText, _socDeltaText,
            _socDashText, _batTempText, _batTempDeltaText, _ambientTempText, _sohText, _kwText, _ampText, _voltText, _auxBatText, _odoText,
            _rangeText, _chargingText, _speedText, _latText, _lonText, _apiStatusText;
    private EditText _abrpUserTokenText;
    private Switch _iternioSendToAPISwitch;
    private CheckBox _isChargingCheckBox;

    private double _soc, _socMin, _socMax, _socDelta, _soh, _speed, _power, _batTemp, _amp, _volt, _auxBat;

    private byte _ambientTemp;
    private final double[] _socHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _socMinHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _socMaxHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _batTempHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private int _socHistoryPosition = 0;
    private int _lastOdo = Integer.MIN_VALUE, _odo;
    private String _vin, _lat, _lon;
    private double _elevation;
    private ChargingConnection _chargingConnection;
    private boolean _isCharging;
    private PrintWriter _logFileWriter;
    private SharedPreferences _preferences;
    private long _sysTimeMs;
    private long _epoch, _lastEpoch, _lastEpochNotification, _lastEpochSuccessfulApiSend;
    private Button _connectButton;
    private CommunicateViewModel _viewModel;
    private volatile boolean _loopRunning = false;
    private volatile boolean _sendDataToIternioRunning = false;
    private volatile int _retries = 0;
    private boolean _carConnected = false;
    private byte _newMessage;

    NotificationCompat.Builder _notificationBuilder;
    NotificationManagerCompat _notificationManagerCompat;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Setup our activity
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_communicate);
        // Enable the back button in the action bar if possible
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        // Setup our ViewModel
        _viewModel = ViewModelProviders.of(this).get(CommunicateViewModel.class);
        // This method return false if there is an error, so if it does, we should close.
        if (!_viewModel.setupViewModel(getIntent().getStringExtra("device_name"), getIntent().getStringExtra("device_mac"))) {
            _loopRunning = false;
            finish();
            return;
        }

        _preferences = getPreferences(MODE_PRIVATE);

        _notificationBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.e_logo)
                .setContentTitle("e Insight")
                .setContentText("Start")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT);

        createNotificationChannel();
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return;
        }
        _notificationManagerCompat = NotificationManagerCompat.from(this);
        //_notificationManagerCompat.notify(NOTIFICATION_ID, _notificationBuilder.build());

        // Setup our Views
        _connectionText = findViewById(R.id.communicate_connection_text);
        _messageText = findViewById(R.id.communicate_message);
        _vinText = findViewById(R.id.communicate_vin);
        _speedText = findViewById(R.id.communicate_speed);
        _latText = findViewById(R.id.communicate_lat);
        _lonText = findViewById(R.id.communicate_lon);
        _socMinText = findViewById(R.id.communicate_soc_min);
        _socMaxText = findViewById(R.id.communicate_soc_max);
        _socDashText = findViewById(R.id.communicate_soc_dash);
        _socDeltaText = findViewById(R.id.communicate_soc_delta);
        _chargingText = findViewById(R.id.communicate_charging_connection);
        _isChargingCheckBox = findViewById(R.id.communicate_is_charging);
        _batTempText = findViewById(R.id.communicate_battemp);
        _batTempDeltaText = findViewById(R.id.communicate_battemp_delta);
        _ambientTempText = findViewById(R.id.communicate_ambient_temp);
        _sohText = findViewById(R.id.communicate_soh);
        _kwText = findViewById(R.id.communicate_kw);
        _ampText = findViewById(R.id.communicate_amp);
        _voltText = findViewById(R.id.communicate_volt);
        _auxBatText = findViewById(R.id.communicate_aux_bat);
        _odoText = findViewById(R.id.communicate_odo);
        _rangeText = findViewById(R.id.communicate_range);
        _apiStatusText = findViewById(R.id.communicate_api_status);

        _abrpUserTokenText = findViewById(R.id.communicate_abrp_user_token);
        _iternioSendToAPISwitch = findViewById(R.id.communicate_iternio_send_to_api);

        _iternioSendToAPISwitch.setOnCheckedChangeListener((buttonView, isChecked) -> handleIternioSendToAPISwitch(isChecked));
        _iternioSendToAPISwitch.setChecked(_preferences.getBoolean(ITERNIO_SEND_TO_API_SWITCH, false));
        _abrpUserTokenText.setText(_preferences.getString(USER_TOKEN_PREFS, "User-Token"));

        _connectButton = findViewById(R.id.communicate_connect);

        // Start observing the data sent to us by the ViewModel
        _viewModel.getConnectionStatus().observe(this, this::onConnectionStatus);
        _viewModel.getDeviceName().observe(this, name -> setTitle(getString(R.string.device_name_format, name)));


        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //check for location permissions
        try {
            if (ContextCompat.checkSelfPermission(getApplicationContext(), android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        //get location manager
        LocationManager lm = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);

        checkExternalMedia();
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is not in the Support Library.

        CharSequence name = getString(R.string.channel_name);
        String description = getString(R.string.channel_description);
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance);
        channel.setDescription(description);
        // Register the channel with the system; you can't change the importance
        // or other notification behaviors after this.
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void connectCAN() { //initiate connection over CAN
        try {
            setText(_apiStatusText, "⚪");
            for (String command : _connectionCommands) {
                synchronized (_viewModel.getNewMessageParsed()) {
                    _viewModel.sendMessage(command + "\n\r");
                    _viewModel.getNewMessageParsed().wait(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                    if (_viewModel.isNewMessage()) {
                        final String message = _viewModel.getMessage();
                        if (_viewModel.isNewMessage() && _viewModel.getMessageID().equals(VIN_ID)) {
                            parseVIN(message);
                            setText(_vinText, _vin);
                            _carConnected = true;
                            _viewModel.setNewMessageProcessed();
                        } else if (_viewModel.isNewMessage()) {
                            setText(_messageText, message);
                            _viewModel.setNewMessageProcessed();
                        }
                        if (message.matches("\\d+\\.\\dV")) { //Aux Bat Voltage
                            _auxBat = Double.parseDouble(message.substring(0, message.length() - 1));
                            setText(_auxBatText, message);
                        }
                    }
                }
                if (command.length() <= 6) {
                    Thread.sleep(WAIT_TIME_BETWEEN_COMMAND_SENDS_MS);
                }
            }

            if (_carConnected) {
                Thread.sleep(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                openNewFileForWriting();
                loop();
            } else {
                setText(_messageText, "CAN not responding...");
                _viewModel.disconnect();
            }
        } catch (InterruptedException e) {
            //Log.d("STATE", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void loop() { //CAN messages loop
        _loopRunning = true;
        try {
            while (_loopRunning) {
                _sysTimeMs = System.currentTimeMillis();
                loopMessagesToVariables();
                _epoch = _sysTimeMs / 1000;
                setText(_ambientTempText, _ambientTemp + ".0°C");
                setText(_sohText, String.format(Locale.ENGLISH, "%1$05.2f%%", _soh));
                setText(_ampText, String.format(Locale.ENGLISH, "%1$06.2fA", _amp));
                setText(_voltText, String.format(Locale.ENGLISH, "%1$.1f/%2$.2fV", _volt, _volt / 96));
                setText(_kwText, String.format(Locale.ENGLISH, "%1$05.1fkW", _power));
                ;
                setText(_socMinText, String.format(Locale.ENGLISH, "%1$05.2f%%", _socMin));
                setText(_socMaxText, String.format(Locale.ENGLISH, "%1$05.2f%%", _socMax));
                setText(_socDeltaText, String.format(Locale.ENGLISH, "%1$4.2f%%", _socDelta));
                setText(_socDashText, String.format(Locale.ENGLISH, "%1$05.2f%%", _soc));
                setText(_chargingText, _chargingConnection.getName());
                setChecked(_isChargingCheckBox, _isCharging);
                setText(_batTempText, _batTemp + "°C");
                setText(_odoText, _odo + "km");

                setText(_speedText, _speed + "km/h");
                setText(_latText, _lat);
                setText(_lonText, _lon);

                if (_newMessage > 4) {
                    setText(_messageText, String.valueOf(_epoch));
                    if (_lastEpochNotification + 10 < _epoch) {
                        _notificationBuilder.setContentText("SoC " + String.valueOf(_soc) + "%");
                        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            // TODO: Consider calling
                            //    ActivityCompat#requestPermissions
                            // here to request the missing permissions, and then overriding
                            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                            //                                          int[] grantResults)
                            // to handle the case where the user grants the permission. See the documentation
                            // for ActivityCompat#requestPermissions for more details.
                            return;
                        }
                        _notificationManagerCompat.notify(NOTIFICATION_ID, _notificationBuilder.build());
                        _lastEpochNotification = _epoch;
                    }
                    writeLineToLogFile();
                    if (_sendDataToIternioRunning && _lastEpoch + 1 < _epoch) {
                        final String requestString = "https://api.iternio.com/1/tlm/send?api_key=" + ITERNIO_API_KEY +
                                "&token=" + _preferences.getString(USER_TOKEN_PREFS, "") +
                                "&tlm=" +
                                "{\"utc\":" + _epoch +
                                ",\"soc\":" + _soc +
                                ",\"soh\": " + _soh +
                                ",\"speed\":" + _speed +
                                ",\"lat\":" + _lat +
                                ",\"lon\":" + _lon +
                                ",\"elevation\":" + _elevation +
                                ",\"is_charging\":" + _isCharging +
                                ",\"is_dcfc\":" + _chargingConnection.getDcfc() +
                                ",\"power\":" + _power +
                                ",\"ext_temp\":" + _ambientTemp +
                                ",\"batt_temp\":" + _batTemp +
                                ",\"car_model\":" + "\"honda:e:20:36\"" +
                                "}";
                        _lastEpoch = _epoch;
                        new Thread(this::sendDataToIternoAPI, requestString).start();
                    }
                } else {
                    setText(_messageText, "No new Message from CAN... wait" + String.valueOf(CAN_BUS_SCAN_INTERVALL) + "ms");
                    Thread.sleep(CAN_BUS_SCAN_INTERVALL);
                }
                _newMessage = 0;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            if (e.getMessage() != null) {
                setText(_messageText, e.getMessage());
            } else {
                setText(_messageText, "unexpected Exception");
            }
            try {
                Thread.sleep(CAN_BUS_SCAN_INTERVALL);
            } catch (InterruptedException e2) {
                throw new RuntimeException(e2);
            }
        }
        _carConnected = false;
    }

    private void loopMessagesToVariables() throws InterruptedException {
        for (String command : _loopCommands) {
            synchronized (_viewModel.getNewMessageParsed()) {
                _viewModel.sendMessage(command + "\n\r");
                _viewModel.getNewMessageParsed().wait(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                if (_viewModel.isNewMessage()) {
                    final String message = _viewModel.getMessage();
                    final String messageID = _viewModel.getMessageID();
                    if (messageID.equals(AMBIENT_ID)) {
                        _ambientTemp = Integer.valueOf(message.substring(42, 44), 16).byteValue();
                        _newMessage++;
                    } else if (messageID.equals(SOH_ID)) {
                        _soh = Integer.parseInt(message.substring(198, 202), 16) / 100.0;
                        _amp = Math.round((Integer.valueOf(message.substring(280, 284), 16).shortValue() / 32.0) * 100.0) / 100.0;
                        _volt = Integer.parseInt(message.substring(76, 80), 16) / 10.0;
                        _power = Math.round(_amp * _volt / 1000.0 * 10.0) / 10.0;
                        _newMessage++;
                    } else if (messageID.equals(SOC_ID)) {
                        _socMin = Integer.parseInt(message.substring(142, 146), 16) / 100.0;
                        _socMax = Integer.parseInt(message.substring(138, 142), 16) / 100.0;
                        _socDelta = Math.round((_socMax - _socMin) * 100.0) / 100.0;
                        _soc = Integer.parseInt(message.substring(156, 160), 16) / 100.0;
                        _isCharging = message.charAt(161) == '1';
                        switch (message.substring(277, 278)) {
                            case "2":
                                _chargingConnection = ChargingConnection.AC;
                                break;
                            case "3":
                                _chargingConnection = ChargingConnection.DC;
                                break;
                            default:
                                _chargingConnection = ChargingConnection.NC;
                        }
                        _newMessage++;
                    } else if (messageID.equals(BATTEMP_ID)) {
                        _batTemp = Integer.valueOf(message.substring(410, 414), 16).shortValue() / 10.0;
                        _newMessage++;
                    } else if (messageID.equals(ODO_ID)) {
                        _odo = Integer.parseInt(message.substring(18, 26), 16);
                        if (_lastOdo < _odo) {
                            _lastOdo = _odo;
                            _socHistory[_socHistoryPosition] = _soc;
                            _socMinHistory[_socHistoryPosition] = _socMin;
                            _socMaxHistory[_socHistoryPosition] = _socMax;
                            _batTempHistory[_socHistoryPosition] = _batTemp;
                            _socHistoryPosition = (_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1);
                            //Should be _socHistoryPosition - RANGE_ESTIMATE_WINDOW
                            //but Java keeps the negative sign
                            double socDelta = _socHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _soc;
                            double socMinDelta = _socMinHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _socMin;
                            double socMaxDelta = _socMaxHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _socMax;
                            double batTempDelta = _batTemp - _batTempHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)];
                            long socRange = Math.round((_soc / socDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                            long socMinRange = Math.round((_socMin / socMinDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                            long socMaxRange = Math.round((_socMax / socMaxDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                            double batTempChange = batTempDelta / RANGE_ESTIMATE_WINDOW_5KM;
                            if (socRange >= 0 || socMinRange >= 0 || socMaxRange >= 0) {
                                setText(_rangeText, String.format(Locale.ENGLISH, "%1$03dkm / %2$03dkm / %3$03dkm", socRange, socMinRange, socMaxRange));
                                setText(_batTempDeltaText, String.format(Locale.ENGLISH, "%1$.2fK/km", batTempChange));
                                //setText(_rangeText, socRange + "km / " + socMinRange + "km / " + socMaxRange + "km");
                            } else {
                                setText(_rangeText, "---km / ---km / ---km");
                            }
                        }
                        _newMessage++;
                    } else if (message.matches("\\d+\\.\\dV")) { //Aux Bat Voltage
                        _auxBat = Double.parseDouble(message.substring(0, message.length() - 1));
                        setText(_auxBatText, message);
                    } else {
                        //setText(_messageText, message);
                    }
                    _viewModel.setNewMessageProcessed();
                }
            }
            if (command.length() <= 7) {
                Thread.sleep(WAIT_TIME_BETWEEN_COMMAND_SENDS_MS);
            }
        }
    }

    private void sendDataToIternoAPI() { //Send data to Iterno API
        try {
            final String requestString = Thread.currentThread().getName();
            final URL url = new URL(requestString);
            HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.getOutputStream().write(new byte[0]);
            final BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
            final StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            if (response.toString().contains("ok")) {
                _lastEpochSuccessfulApiSend = _epoch;
                setText(_apiStatusText, "\uD83D\uDFE2");
            }
            //setText(_messageText, _messageText.getText() + " " + response.toString());
        } catch (IOException e) {
            if (_epoch - _lastEpochSuccessfulApiSend > 9) {
                setText(_apiStatusText, "\uD83D\uDD34");
            } else if (_epoch - _lastEpochSuccessfulApiSend > 1) {
                setText(_apiStatusText, "\uD83D\uDFE1");
            }

            if (e.getMessage() != null) {
                setText(_messageText, e.getMessage());
            } else {
                setText(_messageText, "unexpected Exception at Iternio API");
            }
        }
    }

    private void checkExternalMedia() {
        boolean externalStorageAvailable;
        boolean externalStorageWriteable;
        String state = Environment.getExternalStorageState();

        if (Environment.MEDIA_MOUNTED.equals(state)) {
            // Can read and write the media
            externalStorageAvailable = externalStorageWriteable = true;
        } else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
            // Can only read the media
            externalStorageAvailable = true;
            externalStorageWriteable = false;
        } else {
            // Can't read or write
            externalStorageAvailable = externalStorageWriteable = false;
        }
        if (!externalStorageWriteable) {
            setText(_messageText, "\n\nExternal Media: readable="
                    + externalStorageAvailable + " writable=" + false);
        }
    }

    private void setText(final TextView text, final String value) {
        runOnUiThread(() -> text.setText(value));
    }

    private void setChecked(final Checkable checkable, final boolean checked) {
        runOnUiThread(() -> checkable.setChecked(checked));
    }

    private void handleIternioSendToAPISwitch(boolean isChecked) {
        _sendDataToIternioRunning = isChecked;
        SharedPreferences.Editor edit = _preferences.edit();
        edit.putBoolean(ITERNIO_SEND_TO_API_SWITCH, isChecked);
        String abrpuserTokenTextString = _abrpUserTokenText.getText().toString();
        if (!TextUtils.isEmpty(abrpuserTokenTextString)) {
            edit.putString(USER_TOKEN_PREFS, abrpuserTokenTextString);
        }
        edit.apply();
    }

    private void parseVIN(String message) {
        _vin = hexToASCII(message.substring(10, 44));
    }

    private static String hexToASCII(String hexStr) {
        StringBuilder output = new StringBuilder();

        for (int i = 0; i < hexStr.length(); i += 2) {
            String str = hexStr.substring(i, i + 2);
            output.append((char) Integer.parseInt(str, 16));
        }

        return output.toString();
    }

    // Called when the ViewModel updates us of our connectivity status
    private void onConnectionStatus(CommunicateViewModel.ConnectionStatus connectionStatus) {
        switch (connectionStatus) {
            case CONNECTED:
                _connectionText.setText(R.string.status_connected);
                _connectButton.setEnabled(true);
                _connectButton.setText(R.string.disconnect);
                _connectButton.setOnClickListener(v -> _viewModel.disconnect());
                new Thread(this::connectCAN).start();
                break;

            case CONNECTING:
                _connectionText.setText(R.string.status_connecting);
                _connectButton.setEnabled(true);
                _connectButton.setText(R.string.stop);
                _viewModel.setRetry(true);
                _connectButton.setOnClickListener(v -> _viewModel.setRetry(false));
                break;

            case DISCONNECTED:
                _loopRunning = false;
                _connectionText.setText(R.string.status_disconnected);
                _connectButton.setEnabled(true);
                _connectButton.setText(R.string.connect);
                _connectButton.setOnClickListener(v -> _viewModel.connect());
                closeLogFile();
                _retries = 0;
                break;

            case RETRY:
                _retries++;
                if (_viewModel.isRetry() && _retries < MAX_RETRY) {
                    _viewModel.connect();
                } else {
                    _viewModel.disconnect();
                }
        }
    }

    private void openNewFileForWriting() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
            Date now = new Date();

            File logFile = new File(this.getExternalMediaDirs()[1], _vin + "-" + sdf.format(now) + ".csv");

            logFile.createNewFile();
            //Log.d("FILE", logFile + " " + logFile.exists());

            _logFileWriter = new PrintWriter(logFile);
            _logFileWriter.println(LOG_FILE_HEADER);

        } catch (Exception e) {
            //Log.d("EXCEPTION", e.getMessage());
            for (StackTraceElement element : e.getStackTrace()) {
                //    Log.d("EXCEPTION", element.toString());

            }
        }

    }

    private void writeLineToLogFile() {
        //"VIN,epoch,ODO,SoC (dash),SoC (min),SoC (max),SoH,Battemp,Ambienttemp,kW,Amp,Volt,AuxBat,Connection,Charging,Speed,Lat,Lon"
        _logFileWriter.println(_sysTimeMs + "," + _odo + "," + _soc + ","
                + _socMin + "," + _socMax + "," + _soh + "," + _batTemp + ","
                + _ambientTemp + "," + _power + "," + _amp + "," + _volt + ","
                + _auxBat + "," + _chargingConnection.getName() + "," + _isCharging
                + "," + _speed + "," + _lat + "," + _lon);
    }

    private void closeLogFile() {
        if (_logFileWriter != null) {
            _logFileWriter.flush();
            _logFileWriter.close();
        }
    }

    // Called when a button in the action bar is pressed
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // If the back button was pressed, handle it the normal way
                onBackPressed();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // Called when the user presses the back button
    @Override
    public void onBackPressed() {
        // Close the activity
        _loopRunning = false;
        _viewModel.disconnect();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        finish();
    }

    @Override
    public void onLocationChanged(Location location) {
        _speed = Math.round(location.getSpeed() * 36) / 10.0;
        _lat = String.valueOf(location.getLatitude());
        _lon = String.valueOf(location.getLongitude());
        _elevation = Math.round(location.getAltitude() * 10.0) / 10.0;
    }

    enum ChargingConnection {

        NC("NC", 0),

        AC("AC", 0),
        DC("DC", 1);
        private final String _name;
        private final int _dcfc;

        ChargingConnection(String name, int dcfc) {
            _name = name;
            _dcfc = dcfc;
        }

        public String getName() {
            return _name;
        }

        public int getDcfc() {
            return _dcfc;
        }
    }
}
