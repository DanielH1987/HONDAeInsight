package de.danielh.hondae_insight;

import android.app.Application;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.harrysoft.androidbluetoothserial.BluetoothManager;
import com.harrysoft.androidbluetoothserial.SimpleBluetoothDeviceInterface;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

public class CommunicateViewModel extends AndroidViewModel {

    // A CompositeDisposable that keeps track of all of our asynchronous tasks
    private final CompositeDisposable _compositeDisposable = new CompositeDisposable();

    // Our BluetoothManager!
    private BluetoothManager _bluetoothManager;

    // Our Bluetooth Device! When disconnected it is null, so make sure we know that we need to deal with it potentially being null
    @Nullable
    private SimpleBluetoothDeviceInterface _deviceInterface;

    // The messages feed that the activity sees
    private final MutableLiveData<ConnectionStatus> _connectionStatusData = new MutableLiveData<>();
    // The device name that the activity sees
    private final MutableLiveData<String> _deviceNameData = new MutableLiveData<>();
    // The message in the message box that the activity sees
    // Our modifiable record of the conversation
    // Our configuration
    private String _mac;

    // A variable to help us not double-connect
    private boolean _connectionAttemptedOrMade = false;
    // A variable to help us not setup twice
    private boolean _viewModelSetup = false;

    private boolean _newMessage = false;

    private final Object _newMessageParsed = new Object();
    private String _message = "";
    //private String _messageHeader = "";

    private String _messageID = "";

    private boolean _retry = true;

    // Called by the system, this is just a constructor that matches AndroidViewModel.
    public CommunicateViewModel(@NotNull Application application) {
        super(application);
    }

    // Called in the activity's onCreate(). Checks if it has been called before, and if not, sets up the data.
    // Returns true if everything went okay, or false if there was an error and therefore the activity should finish.
    public boolean setupViewModel(String deviceName, String mac) {
        // Check we haven't already been called
        if (!_viewModelSetup) {
            _viewModelSetup = true;

            // Setup our BluetoothManager
            _bluetoothManager = BluetoothManager.getInstance();
            if (_bluetoothManager == null) {
                // Bluetooth unavailable on this device :( tell the user
                toast(R.string.bluetooth_unavailable);
                // Tell the activity there was an error and to close
                return false;
            }

            // Remember the configuration
            _mac = mac;

            // Tell the activity the device name so it can set the title
            _deviceNameData.postValue(deviceName);
            // Tell the activity we are disconnected.
            _connectionStatusData.postValue(ConnectionStatus.DISCONNECTED);
        }
        // If we got this far, nothing went wrong, so return true
        return true;
    }

    // Called when the user presses the connect button
    public void connect() {
        // Check we are not already connecting or connected
        if (!_connectionAttemptedOrMade) {
            // Connect asynchronously
            _compositeDisposable.add(_bluetoothManager.openSerialDevice(_mac)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(device -> onConnected(device.toSimpleDeviceInterface()), t -> {
                        toast(R.string.connection_failed);
                        _connectionAttemptedOrMade = false;
                        _connectionStatusData.postValue(ConnectionStatus.RETRY);
                    }));
            // Remember that we made a connection attempt.
            _connectionAttemptedOrMade = true;
            // Tell the activity that we are connecting.
            _connectionStatusData.postValue(ConnectionStatus.CONNECTING);
        }
    }

    // Called when the user presses the disconnect button
    public void disconnect() {
        // Check we were connected
        if (_connectionAttemptedOrMade && _deviceInterface != null) {
            _connectionAttemptedOrMade = false;
            // Use the library to close the connection
            _bluetoothManager.closeDevice(_deviceInterface);
            // Set it to null so no one tries to use it
            _deviceInterface = null;
        }
        // Tell the activity we are disconnected
        _connectionStatusData.postValue(ConnectionStatus.DISCONNECTED);
    }

    // Called once the library connects a bluetooth device
    private void onConnected(SimpleBluetoothDeviceInterface deviceInterface) {
        this._deviceInterface = deviceInterface;
        if (this._deviceInterface != null) {
            // We have a device! Tell the activity we are connected.
            _connectionStatusData.postValue(ConnectionStatus.CONNECTED);
            // Setup the listeners for the interface
            this._deviceInterface.setListeners(this::onMessageReceived, this::onMessageSent, t -> toast(R.string.message_send_error));
            // Tell the user we are connected.
            toast(R.string.connected);
        } else {
            // deviceInterface was null, so the connection failed
            toast(R.string.connection_failed);
            _connectionStatusData.postValue(ConnectionStatus.DISCONNECTED);
        }
    }

    private void onMessageSent(String message) {
    }

    // Adds a received message to the conversation
    private void onMessageReceived(String message) {
        if (!TextUtils.isEmpty(message)) {
            synchronized (_newMessageParsed) {
                if (message.startsWith(">")) {
                    _message = trySubstring(message, 11);
                    //_messageHeader = message.substring(1);
                    _messageID = trySubstring(message, 11, 19);
                } else {
                    _message += trySubstring(message, 10);
                }
                if (message.contains("0000555555") || message.contains("OK") || message.contains("ELM327") || message.matches(">\\d+\\.\\dV")) {
                    _newMessage = true;
                    _newMessageParsed.notify();
                }
            }
        }
    }

    //Tries to substring with beginIndex. If IndexOutOfBoundsException return complete String
    private String trySubstring(String message, int beginIndex) {
        try {
            return message.substring(beginIndex);
        } catch (IndexOutOfBoundsException e) {
            return message.substring(1);
        }
    }

    private String trySubstring(String message, int beginIndex, int endIndex) {
        try {
            return message.substring(beginIndex, endIndex);
        } catch (IndexOutOfBoundsException e) {
            return message;
        }
    }

    // Send a message
    public void sendMessage(String message) {
        // Check we have a connected device and the message is not empty, then send the message
        if (_deviceInterface != null && !TextUtils.isEmpty(message)) {
            _deviceInterface.sendMessage(message);
        }
    }

    // Called when the activity finishes - clear up after ourselves.
    @Override
    protected void onCleared() {
        // Dispose any asynchronous operations that are running
        _compositeDisposable.dispose();
        // Shutdown bluetooth connections
        _bluetoothManager.close();
    }

    // Helper method to create toast messages.
    private void toast(@StringRes int messageResource) {
        Toast.makeText(getApplication(), messageResource, Toast.LENGTH_LONG).show();
        if (messageResource == R.string.message_send_error) {
            disconnect();
            _connectionStatusData.postValue(ConnectionStatus.AUTO_RECONNECT);
        }
    }

    // Getter method for the activity to use.
    // Getter method for the activity to use.
    public LiveData<ConnectionStatus> getConnectionStatus() {
        return _connectionStatusData;
    }

    // Getter method for the activity to use.
    public LiveData<String> getDeviceName() {
        return _deviceNameData;
    }

    // Getter method for the activity to use.
    public String getMessage() {
        return _message;
    }

    //public String getMessageHeader() {return _messageHeader; }
    public String getMessageID() {
        return _messageID;
    }

    public boolean isNewMessage() {
        return _newMessage;
    }

    public Object getNewMessageParsed() {
        return _newMessageParsed;
    }

    public void setNewMessageProcessed() {
        _newMessage = false;
    }

    public boolean isRetry() {
        return _retry;
    }

    public void setRetry(boolean _retry) {
        this._retry = _retry;
    }


    // An enum that is passed to the activity to indicate the current connection status
    enum ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTO_RECONNECT, RETRY
    }
}
