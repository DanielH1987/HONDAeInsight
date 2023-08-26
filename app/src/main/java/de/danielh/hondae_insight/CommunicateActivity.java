package de.danielh.hondae_insight;

import static de.danielh.hondae_insight.IternioApiKeyStore.ITERNIO_API_KEY;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Checkable;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

import javax.net.ssl.HttpsURLConnection;

public class CommunicateActivity extends AppCompatActivity implements LocationListener {

    public static final int CAN_BUS_SCAN_INTERVALL = 5000;
    public static final int WAIT_FOR_NEW_MESSAGE_TIMEOUT = 1000;
    public static final int WAIT_TIME_BETWEEN_COMMAND_SENDS_MS = 50;
    public static String VIN_ID = "1862F190";
    public static String AMBIENT_ID = "39627028";
    public static String SOH_ID = "F6622021";
    public static String SOC_ID = "F6622029";
    public static String BATTEMP_ID = "F662202A";
    public static String ODO_ID = "39627022";
    public static int RANGE_ESTIMATE_WINDOW_5KM = 5;
    private final static String USER_TOKEN_PREFS = "abrp_user_token";
    private final static String ITERNIO_SEND_TO_API_SWITCH = "iternioSendToAPISwitch";

    private final ArrayList<String> _connectionCommands = new ArrayList<>(Arrays.asList(
            "ATWS",
            "ATE0",
            "ATSP7",
            "ATAT1",
            "ATH1",
            "ATL0",
            "ATS0",
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
            "22F190"
    ));

    private final ArrayList<String> _loopCommands = new ArrayList<>(Arrays.asList(
            "ATSHDA60F1",
            "ATFCSH18DA60F1",
            "ATCRA18DAF160",
            "227028", //AMBIENT
            "ATSHDA15F1",
            "ATFCSH18DA15F1",
            "ATCRA18DAF115",
            "222021", //SOH VOLT AMP
            "222029", //SOC
            "ATSHDA01F1",
            "ATFCSH18DA01F1",
            "ATCRA18DAF101",
            "22202A", // BATTTEMP
            "ATSHDA60F1",
            "ATFCSH18DA60F1",
            "ATCRA18DAF160",
            "2270229", //ODO
            "ATRV" // AUX BAT
    ));
    private TextView _connectionText, _vinText, _messageText, _socMinText, _socMaxText,
            _socDashText, _batTempText, _ambientTempText, _sohText, _kwText, _ampText, _voltText, _auxBat, _odoText,
            _rangeText, _chargingText, _speedText, _latText, _lonText;
    private EditText _abrpUserTokenText;
    private Switch _iternioSendToAPISwitch;
    private CheckBox _isChargingCheckBox;

    private double _soc, _socMin, _socMax, _soh, _speed, _power, _batTemp;

    private byte _ambientTemp;
    private final double[] _socHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _socMinHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private final double[] _socMaxHistory = new double[RANGE_ESTIMATE_WINDOW_5KM + 1];
    private int _socHistoryPosition = 0;
    private int _lastOdo = Integer.MIN_VALUE;
    private String _lat, _lon, _elevation;
    private ChargingConnection _chargingConnection;
    private boolean _isCharging;

    private SharedPreferences _preferences;
    private long _epoch;
    private Button _connectButton;
    private CommunicateViewModel _viewModel;
    private volatile boolean _loopRunning = false;
    private volatile boolean _sendDataToIternioRunning = false;
    private boolean _carConnected = false;
    private boolean _newMessage = false;
    private boolean _activity;

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
        _chargingText = findViewById(R.id.communicate_charging_connection);
        _isChargingCheckBox = findViewById(R.id.communicate_is_charging);
        _batTempText = findViewById(R.id.communicate_battemp);
        _ambientTempText = findViewById(R.id.communicate_ambient_temp);
        _sohText = findViewById(R.id.communicate_soh);
        _kwText = findViewById(R.id.communicate_kw);
        _ampText = findViewById(R.id.communicate_amp);
        _voltText = findViewById(R.id.communicate_volt);
        _auxBat = findViewById(R.id.communicate_aux_bat);
        _odoText = findViewById(R.id.communicate_odo);
        _rangeText = findViewById(R.id.communicate_range);

        _abrpUserTokenText = findViewById(R.id.communicate_abrp_user_token);
        _iternioSendToAPISwitch = findViewById(R.id.communicate_iternio_send_to_api);

        _iternioSendToAPISwitch.setOnCheckedChangeListener((buttonView, isChecked) -> handleIternioSendToAPISwtich(isChecked));
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
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 0, this);

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        _loopRunning = false;
        _viewModel.disconnect();
    }

    private void connectCAN() { //initiate connection over CAN
        try {
            for (String command : _connectionCommands) {
                synchronized (_viewModel.getNewMessageParsed()) {
                    _viewModel.sendMessage(command + "\n\r");
                    _viewModel.getNewMessageParsed().wait(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                    if (_viewModel.isNewMessage() && _viewModel.getMessageID().equals(VIN_ID)) {
                        setText(_vinText, parseVIN(_viewModel.getMessage()));
                        _carConnected = true;
                        _viewModel.setNewMessageProcessed();
                    } else if (_viewModel.isNewMessage()) {
                        setText(_messageText, _viewModel.getMessage());
                        _viewModel.setNewMessageProcessed();
                    }
                }
                Thread.sleep(WAIT_TIME_BETWEEN_COMMAND_SENDS_MS);
            }
        } catch (InterruptedException e) {
            //Log.d("STATE", e.getMessage());
            throw new RuntimeException(e);
        }
        if (_carConnected) {
            loop();
        } else {
            setText(_messageText, "CAN not responding...");
            _viewModel.disconnect();
        }

    }

    private void loop() { //CAN messages loop
        _loopRunning = true;
        try {
            while (_loopRunning) {
                long start = System.currentTimeMillis();
                for (String command : _loopCommands) {
                    synchronized (_viewModel.getNewMessageParsed()) {
                        _viewModel.sendMessage(command + "\n\r");
                        _viewModel.getNewMessageParsed().wait(WAIT_FOR_NEW_MESSAGE_TIMEOUT);
                        if (_viewModel.isNewMessage()) {
                            final String message = _viewModel.getMessage();
                            final String messageID = _viewModel.getMessageID();
                            if (messageID.equals(AMBIENT_ID)) {
                                _ambientTemp = Integer.valueOf(message.substring(42, 44), 16).byteValue();
                                setText(_ambientTempText, _ambientTemp + ".0°C");
                                _newMessage = true;
                            } else if (messageID.equals(SOH_ID)) {
                                _soh = Integer.parseInt(message.substring(198, 202), 16) / 100.0;
                                setText(_sohText, _soh + "%");
                                double _amp = Math.round(Integer.valueOf(message.substring(280, 284), 16).shortValue() / 38.0 * 100.0) / 100.0;
                                double _volt = Integer.parseInt(message.substring(76, 80), 16) / 10.0;
                                setText(_ampText, _amp + "A");
                                setText(_voltText, _volt + "/" + Math.round(_volt/0.96) / 100.0 + "V");
                                _power = Math.round(_amp * _volt / 1000.0 * 100.0) / 100.0;
                                setText(_kwText, _power + "kW");
                                _newMessage = true;
                            } else if (messageID.equals(SOC_ID)) {
                                _socMin = Integer.parseInt(message.substring(142, 146), 16) / 100.0;
                                _socMax = Integer.parseInt(message.substring(138, 142), 16) / 100.0;
                                setText(_socMinText, _socMin + "%");
                                setText(_socMaxText, _socMax + "%");
                                _soc = Integer.parseInt(message.substring(156, 160), 16) / 100.0;
                                setText(_socDashText, _soc + "%");
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
                                setText(_chargingText, _chargingConnection.getName());
                                setChecked(_isChargingCheckBox, _isCharging);
                                _newMessage = true;
                            } else if (messageID.equals(BATTEMP_ID)) {
                                _batTemp = Integer.valueOf(message.substring(410, 414), 16).shortValue() / 10.0;
                                setText(_batTempText, _batTemp + "°C");
                                _newMessage = true;
                            } else if (messageID.equals(ODO_ID)) {
                                int _odo = Integer.parseInt(message.substring(18, 26), 16);
                                if (_lastOdo < _odo) {
                                    _lastOdo = _odo;
                                    _socHistory[_socHistoryPosition] = _soc;
                                    _socMinHistory[_socHistoryPosition] = _socMin;
                                    _socMaxHistory[_socHistoryPosition] = _socMax;
                                    _socHistoryPosition = (_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1);
                                    setText(_odoText, _odo + "km");
                                    //Should be _socHistoryPosition - RANGE_ESTIMATE_WINDOW
                                    //but Java keeps the negative sign
                                    double socDelta = _socHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _soc;
                                    double socMinDelta = _socMinHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _socMin;
                                    double socMaxDelta = _socMaxHistory[(_socHistoryPosition + 1) % (RANGE_ESTIMATE_WINDOW_5KM + 1)] - _socMax;
                                    long socRange = Math.round((_soc / socDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                                    long socMinRange = Math.round((_socMin / socMinDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                                    long socMaxRange = Math.round((_socMax / socMaxDelta) * RANGE_ESTIMATE_WINDOW_5KM);
                                    if (socRange >= 0 || socMinRange >= 0 || socMaxRange >= 0) {
                                        setText(_rangeText, socRange + "km / " + socMinRange + "km / " + socMaxRange + "km");
                                    } else {
                                        setText(_rangeText, "---km / ---km / ---km");
                                    }
                                }
                                _newMessage = true;
                            } else if (message.matches("\\d+\\.\\dV")) { //Aux Bat Voltage
                                setText(_auxBat, message);
                            } else {
                                if (_activity) {
                                    setText(_messageText, message);
                                } else {
                                    setText(_messageText, message + " #");
                                }
                                _activity = !_activity;
                            }
                            _viewModel.setNewMessageProcessed();
                        }
                    }
                    Thread.sleep(WAIT_TIME_BETWEEN_COMMAND_SENDS_MS);
                }

                setText(_speedText, _speed + "km/h");
                setText(_latText, _lat);
                setText(_lonText, _lon);
                _epoch = System.currentTimeMillis() / 1000;
                if (_sendDataToIternioRunning && _newMessage) {
                    sendDataToIternoAPI();
                }
                if (!_newMessage) {
                    setText(_messageText, "No new Message from CAN... retry");
                }
                _newMessage = false;
                //wait 5 Seconds since last loop started
                long millis = CAN_BUS_SCAN_INTERVALL - (System.currentTimeMillis() - start);
                if (millis > 0) {
                    Thread.sleep(millis);
                } else { // If wait time is overdue; wait to be able to read the exception message
                    Thread.sleep(CAN_BUS_SCAN_INTERVALL);
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (Exception e) {
            if (e.getMessage() != null) {
                setText(_messageText, e.getMessage());
            } else {
                setText(_messageText, "unexpected Exception");
            }
        }
        _carConnected = false;

    }

    private void sendDataToIternoAPI() { //Send data to Iterno API
        String requestString = "https://api.iternio.com/1/tlm/send?api_key=" + ITERNIO_API_KEY +
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

        try {
            URL url = new URL(requestString);
            HttpsURLConnection con = (HttpsURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.getOutputStream().write(new byte[0]);
            BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder response = new StringBuilder();
            String responseLine;
            while ((responseLine = br.readLine()) != null) {
                response.append(responseLine.trim());
            }
            setText(_messageText, response.toString());
        } catch (IOException e) {
            if (e.getMessage() != null) {
                setText(_messageText, e.getMessage());
            } else {
                setText(_messageText, "unexpected Exception at Iternio API");
            }
        }
    }

    private void setText(final TextView text, final String value) {
        runOnUiThread(() -> text.setText(value));
    }

    private void setChecked(final Checkable checkable, final boolean checked) {
        runOnUiThread(() -> checkable.setChecked(checked));
    }

    private void handleIternioSendToAPISwtich(boolean isChecked) {
        _sendDataToIternioRunning = isChecked;
        SharedPreferences.Editor edit = _preferences.edit();
        edit.putBoolean(ITERNIO_SEND_TO_API_SWITCH, isChecked);
        String abrpuserTokenTextString = _abrpUserTokenText.getText().toString();
        if (!TextUtils.isEmpty(abrpuserTokenTextString)) {
            edit.putString(USER_TOKEN_PREFS, abrpuserTokenTextString);
        }
        edit.apply();
    }

    private String parseVIN(String message) {
        //Log.d("STATE", "message VIN, substring(10,44): " + message.substring(10, 44));
        return hexToASCII(message.substring(10, 44));
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
                break;

            case RETRY:
                if (_viewModel.isRetry()) {
                    _viewModel.connect();
                } else {
                    _viewModel.disconnect();
                }
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
    public void onLocationChanged(@NonNull Location location) {
        _speed = Math.round(location.getSpeed() * 36) / 10.0;
        _lat = String.valueOf(location.getLatitude());
        _lon = String.valueOf(location.getLongitude());
        _elevation = String.valueOf(Math.round(location.getAltitude() * 10.0) / 10.0);
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
