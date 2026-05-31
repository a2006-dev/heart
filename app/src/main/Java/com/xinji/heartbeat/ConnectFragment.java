package com.xinji.heartbeat;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.*;
import androidx.fragment.app.Fragment;
public class ConnectFragment extends Fragment {
    private TextView tvDevice, tvStatus;
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_connect, container, false);
        tvDevice = v.findViewById(R.id.tvDevice);
        tvStatus = v.findViewById(R.id.tvStatus);
        v.findViewById(R.id.btnScan).setOnClickListener(b -> ((MainActivity)getActivity()).startScanWithDialog());
        v.findViewById(R.id.btnDisconnect).setOnClickListener(b -> ((MainActivity)getActivity()).disconnectDevice());
        return v;
    }
    public void updateStatus(String device, String status) {
        if (isAdded()) {
            if (tvDevice != null) tvDevice.setText("⚡ " + device);
            if (tvStatus != null) tvStatus.setText(status);
        }
    }
}
