package de.danielh.hondae_insight;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProviders;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

public class MainActivity extends AppCompatActivity {

    private MainActivityViewModel _viewModel;
    private SharedPreferences _preferences;
    private final static String DEVICE_NAME = "device_name";
    private final static String DEVICE_MAC = "device_mac";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Setup our activity
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Setup our ViewModel
        _viewModel = ViewModelProviders.of(this).get(MainActivityViewModel.class);

        // This method return false if there is an error, so if it does, we should close.
        if (!_viewModel.setupViewModel()) {
            finish();
            return;
        }

        _preferences = getPreferences(MODE_PRIVATE);

        // Setup our Views
        RecyclerView deviceList = findViewById(R.id.main_devices);
        SwipeRefreshLayout swipeRefreshLayout = findViewById(R.id.main_swiperefresh);

        // Setup the RecyclerView
        deviceList.setLayoutManager(new LinearLayoutManager(this));
        DeviceAdapter adapter = new DeviceAdapter();
        deviceList.setAdapter(adapter);

        // Setup the SwipeRefreshLayout
        swipeRefreshLayout.setOnRefreshListener(() -> {
            _viewModel.refreshPairedDevices();
            swipeRefreshLayout.setRefreshing(false);
        });

        // Start observing the data sent to us by the ViewModel
        _viewModel.getPairedDeviceList().observe(MainActivity.this, adapter::updateList);

        // Immediately refresh the paired devices list
        _viewModel.refreshPairedDevices();

        new Thread(this::connectToLastConnectedDevice).start();
    }

    public void connectToLastConnectedDevice() {
        try {
            Thread.sleep(1000);
            String deviceName = _preferences.getString(DEVICE_NAME, null);
            String deviceMac = _preferences.getString(DEVICE_MAC, null);

            if (deviceName != null && deviceMac != null) {
                openCommunicationsActivity(deviceName, deviceMac);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    // Called when clicking on a device entry to start the CommunicateActivity
    public void openCommunicationsActivity(String deviceName, String macAddress) {
        final SharedPreferences.Editor editor = _preferences.edit();
        editor.putString(DEVICE_NAME, deviceName);
        editor.putString(DEVICE_MAC, macAddress);
        editor.apply();
        Intent intent = new Intent(this, CommunicateActivity.class);
        intent.putExtra("device_name", deviceName);
        intent.putExtra("device_mac", macAddress);
        startActivity(intent);
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
        finish();
    }

    // A class to hold the data in the RecyclerView
    private class DeviceViewHolder extends RecyclerView.ViewHolder {

        private final RelativeLayout _layout;
        private final TextView _text1;
        private final TextView _text2;

        DeviceViewHolder(View view) {
            super(view);
            _layout = view.findViewById(R.id.list_item);
            _text1 = view.findViewById(R.id.list_item_text1);
            _text2 = view.findViewById(R.id.list_item_text2);
        }

        void setupView(BluetoothDevice device) {
            _text1.setText(device.getName());
            _text2.setText(device.getAddress());
            _layout.setOnClickListener(view -> openCommunicationsActivity(device.getName(), device.getAddress()));
        }
    }

    // A class to adapt our list of devices to the RecyclerView
    class DeviceAdapter extends RecyclerView.Adapter<DeviceViewHolder> {
        private BluetoothDevice[] _deviceList = new BluetoothDevice[0];

        @NotNull
        @Override
        public DeviceViewHolder onCreateViewHolder(@NotNull ViewGroup parent, int viewType) {
            return new DeviceViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NotNull DeviceViewHolder holder, int position) {
            holder.setupView(_deviceList[position]);
        }

        @Override
        public int getItemCount() {
            return _deviceList.length;
        }

        void updateList(Collection<BluetoothDevice> deviceList) {
            this._deviceList = deviceList.toArray(new BluetoothDevice[0]);
            notifyDataSetChanged();
        }
    }
}
