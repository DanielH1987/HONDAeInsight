package de.danielh.hondae_insight;

import android.app.Application;
import android.bluetooth.BluetoothDevice;
import android.widget.Toast;

import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.harrysoft.androidbluetoothserial.BluetoothManager;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public class MainActivityViewModel extends AndroidViewModel {

    // Our BluetoothManager!
    private BluetoothManager _bluetoothManager;

    // The paired devices list tha the activity sees
    private final MutableLiveData<Collection<BluetoothDevice>> _pairedDeviceList = new MutableLiveData<>();

    // A variable to help us not setup twice
    private boolean _viewModelSetup = false;

    // Called by the system, this is just a constructor that matches AndroidViewModel.
    public MainActivityViewModel(@NotNull Application application) {
        super(application);
    }

    // Called in the activity's onCreate(). Checks if it has been called before, and if not, sets up the data.
    // Returns true if everything went okay, or false if there was an error and therefore the activity should finish.
    public boolean setupViewModel() {
        // Check we haven't already been called
        if (!_viewModelSetup) {
            _viewModelSetup = true;

            // Setup our BluetoothManager
            _bluetoothManager = BluetoothManager.getInstance();
            if (_bluetoothManager == null) {
                // Bluetooth unavailable on this device :( tell the user
                Toast.makeText(getApplication(), R.string.no_bluetooth, Toast.LENGTH_LONG).show();
                // Tell the activity there was an error and to close
                return false;
            }
        }
        // If we got this far, nothing went wrong, so return true
        return true;
    }

    // Called by the activity to request that we refresh the list of paired devices
    public void refreshPairedDevices() {
        _pairedDeviceList.postValue(_bluetoothManager.getPairedDevicesList());
    }

    // Called when the activity finishes - clear up after ourselves.
    @Override
    protected void onCleared() {
        if (_bluetoothManager != null)
          _bluetoothManager.close();
    }

    // Getter method for the activity to use.
    public LiveData<Collection<BluetoothDevice>> getPairedDeviceList() { return _pairedDeviceList; }
}
