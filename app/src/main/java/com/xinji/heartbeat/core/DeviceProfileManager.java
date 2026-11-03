package com.xinji.heartbeat.core;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DeviceProfileManager {
    private static final String FILE_NAME = "heart_device_profiles.json";
    private static DeviceProfileManager instance;
    private final File file;
    private JSONArray profiles = new JSONArray();

    public static class DeviceProfile {
        public String name;
        public String address;
        public String serviceUuid;
        public String charUuid;
        public String lastConnected;

        public DeviceProfile(String name, String address, String serviceUuid, String charUuid) {
            this.name = name;
            this.address = address;
            this.serviceUuid = serviceUuid;
            this.charUuid = charUuid;
            this.lastConnected = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
        }
    }

    private DeviceProfileManager(Context context) {
        file = new File(context.getExternalFilesDir(null), FILE_NAME);
        load();
    }

    public static synchronized DeviceProfileManager getInstance(Context context) {
        if (instance == null) {
            instance = new DeviceProfileManager(context.getApplicationContext());
        }
        return instance;
    }

    private void load() {
        if (!file.exists()) return;
        try {
            FileReader fr = new FileReader(file);
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int len;
            while ((len = fr.read(buf)) != -1) sb.append(buf, 0, len);
            fr.close();
            profiles = new JSONArray(sb.toString());
        } catch (Exception ignored) {}
    }

    private synchronized void save() {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            File tmp = new File(file.getPath() + ".tmp");
            try (FileWriter fw = new FileWriter(tmp)) {
                fw.write(profiles.toString(2));
                fw.flush();
            }
            if (!tmp.renameTo(file)) {
                try (FileWriter fw = new FileWriter(file)) {
                    fw.write(profiles.toString(2));
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception ignored) {}
    }

    public synchronized void addOrUpdateProfile(String name, String address, String serviceUuid, String charUuid) {
        if (name == null || address == null) return;

        for (int i = 0; i < profiles.length(); i++) {
            try {
                JSONObject obj = profiles.getJSONObject(i);
                if (address.equals(obj.optString("address", ""))) {
                    obj.put("service_uuid", serviceUuid != null ? serviceUuid : "");
                    obj.put("char_uuid", charUuid != null ? charUuid : "");
                    obj.put("last_connected", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                        .format(new Date()));
                    save();
                    return;
                }
            } catch (Exception ignored) {}
        }

        try {
            JSONObject obj = new JSONObject();
            obj.put("name", name);
            obj.put("address", address);
            obj.put("service_uuid", serviceUuid != null ? serviceUuid : "");
            obj.put("char_uuid", charUuid != null ? charUuid : "");
            obj.put("last_connected", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date()));
            profiles.put(obj);
            save();
        } catch (Exception ignored) {}
    }

    public synchronized void recordDevice(String name, String address) {
        addOrUpdateProfile(name, address, "", "");
    }

    public synchronized void removeProfileByAddress(String address) {
        if (address == null) return;
        for (int i = 0; i < profiles.length(); i++) {
            try {
                JSONObject obj = profiles.getJSONObject(i);
                if (address.equals(obj.optString("address", ""))) {
                    profiles.remove(i);
                    save();
                    return;
                }
            } catch (Exception ignored) {}
        }
    }

    public synchronized String getProfilesJson() {
        return profiles.toString();
    }

    public synchronized List<DeviceProfile> getProfiles() {
        List<DeviceProfile> list = new ArrayList<>();
        for (int i = 0; i < profiles.length(); i++) {
            try {
                JSONObject obj = profiles.getJSONObject(i);
                DeviceProfile p = new DeviceProfile(
                    obj.optString("name", ""),
                    obj.optString("address", ""),
                    obj.optString("service_uuid", ""),
                    obj.optString("char_uuid", "")
                );
                p.lastConnected = obj.optString("last_connected", "");
                list.add(p);
            } catch (Exception ignored) {}
        }
        return list;
    }

    public String getFilePath() {
        return file.getAbsolutePath();
    }
}
