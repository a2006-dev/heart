package com.xinji.heartbeat;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.*;
public class GameModeFragment extends Fragment {
    private RecyclerView recyclerView;
    private GameAdapter adapter;
    private List<GameApp> gameList = new ArrayList<>();
    private SharedPreferences gamePrefs;
    private MainActivity activity;
    private ImageView btnAddGame;
    private TextView tvEmpty, tvWindowModeHint;
    private Switch swShowSystem, swContinueInWindow;
    private boolean showSystemApps = false;
    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_game_mode, container, false);
        activity = (MainActivity) getActivity();
        gamePrefs = activity.getSharedPreferences("game_mode", Context.MODE_PRIVATE);
        recyclerView = v.findViewById(R.id.recyclerView);
        btnAddGame = v.findViewById(R.id.btnAddGame);
        tvEmpty = v.findViewById(R.id.tvEmpty);
        swShowSystem = v.findViewById(R.id.swShowSystem);
        swContinueInWindow = v.findViewById(R.id.swContinueInWindow);
        tvWindowModeHint = v.findViewById(R.id.tvWindowModeHint);
        showSystemApps = activity.prefs.getBoolean("game_show_system", false);
        swShowSystem.setChecked(showSystemApps);
        boolean continueInWindow = activity.prefs.getBoolean("game_continue_in_window", true);
        swContinueInWindow.setChecked(continueInWindow);
        updateWindowModeHint(continueInWindow);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new GameAdapter();
        recyclerView.setAdapter(adapter);
        btnAddGame.setOnClickListener(view -> showAppPicker());
        swShowSystem.setOnCheckedChangeListener((b, c) -> {
            showSystemApps = c;
            activity.prefs.edit().putBoolean("game_show_system", c).apply();
            loadGameList();
        });
        swContinueInWindow.setOnCheckedChangeListener((b, c) -> {
            activity.prefs.edit().putBoolean("game_continue_in_window", c).apply();
            updateWindowModeHint(c);
        });
        loadGameList();
        return v;
    }
    private void updateWindowModeHint(boolean enabled) {
        if (tvWindowModeHint == null) return;
        if (enabled) {
            tvWindowModeHint.setText(
                "💡 开启后，游戏切到后台/小窗/分屏（如刷视频、回消息）时\n" +
                "   心率记录不会中断。各厂商系统差异说明：\n" +
                "   • iQOO/OriginOS：小窗刷视频时进程仍在，会继续记录\n" +
                "   • 小米澎湃OS(HyperOS)/MIUI：自由小窗时进程存活，继续记录\n" +
                "   • ColorOS（OPPO/一加）：闪回窗场景下进程存活\n" +
                "   • EMUI/HarmonyOS（华为）：智慧多窗同样支持\n" +
                "   • 其他系统：只要游戏进程不被杀，记录就不停\n" +
                "   若想手动停止，可回本页关闭此开关或下拉通知栏点停止"
            );
        } else {
            tvWindowModeHint.setText(
                "💡 关闭后，游戏一切到后台就自动停止记录"
            );
        }
    }
    private Set<String> getSelectedPackages() {
        String raw = gamePrefs.getString("selected_games_str", "");
        if (!raw.isEmpty()) {
            String[] parts = raw.split(",");
            Set<String> result = new HashSet<>();
            for (String p : parts) {
                if (!p.isEmpty()) result.add(p);
            }
            return result;
        }
        return new HashSet<>(gamePrefs.getStringSet("selected_games", new HashSet<>()));
    }
    private void saveSelectedPackages(Set<String> packages) {
        StringBuilder sb = new StringBuilder();
        for (String pkg : packages) {
            if (sb.length() > 0) sb.append(",");
            sb.append(pkg);
        }
        gamePrefs.edit()
            .putString("selected_games_str", sb.toString())
            .putStringSet("selected_games", packages)
            .apply();
    }
    private void loadGameList() {
        gameList.clear();
        Set<String> saved = getSelectedPackages();
        PackageManager pm = requireContext().getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo app : apps) {
            if (!showSystemApps && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            String packageName = app.packageName;
            if (saved.contains(packageName)) {
                GameApp game = new GameApp();
                game.packageName = packageName;
                game.appName = app.loadLabel(pm).toString();
                try {
                    game.icon = app.loadIcon(pm);
                } catch (Exception e) {
                    game.icon = null;
                }
                gameList.add(game);
            }
        }
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(gameList.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(gameList.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void showAppPicker() {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("添加游戏（点击项切换）");
        LinearLayout dialogLayout = new LinearLayout(requireContext());
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12));
        final EditText searchInput = new EditText(requireContext());
        searchInput.setHint("输入应用名或包名搜索过滤...");
        searchInput.setTextColor(0xFFE2C2CF);
        searchInput.setHintTextColor(0xFF675C62);
        searchInput.setBackgroundResource(android.R.drawable.editbox_background);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp2px(44));
        searchLp.bottomMargin = dp2px(8);
        dialogLayout.addView(searchInput, searchLp);
        final List<String> allDisplay = new ArrayList<>();
        final List<String> allPkgs = new ArrayList<>();
        PackageManager pm = requireContext().getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        Set<String> selected = getSelectedPackages();
        for (ApplicationInfo app : apps) {
            String pkg = app.packageName;
            if (pkg == null || pkg.isEmpty()) continue;
            if (!showSystemApps && (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            String name = app.loadLabel(pm).toString();
            allDisplay.add(name);
            allPkgs.add(pkg);
        }
        final ListView listView = new ListView(requireContext());
        final ArrayAdapter<String> listAdapter = new ArrayAdapter<String>(requireContext(),
            android.R.layout.simple_list_item_1, new ArrayList<>()) {
            @NonNull @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (v instanceof TextView) {
                    ((TextView) v).setTextColor(0xFFE2C2CF);
                    ((TextView) v).setTextSize(14);
                }
                return v;
            }
        };
        listView.setAdapter(listAdapter);
        dialogLayout.addView(listView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp2px(400)));
        if (!showSystemApps) {
            TextView hint = new TextView(requireContext());
            hint.setText("💡 提示：开启「显示系统应用」可看到全部应用");
            hint.setTextColor(0xFF675C62);
            hint.setTextSize(12);
            hint.setPadding(dp2px(4), dp2px(4), dp2px(4), dp2px(8));
            dialogLayout.addView(hint, 0);
        }
        builder.setView(dialogLayout);
        builder.setNegativeButton("关闭", null);
        final AlertDialog dialog = builder.create();
        Runnable updateList = () -> {
            String query = searchInput.getText().toString().trim().toLowerCase();
            Set<String> curSelected = getSelectedPackages();
            List<String> filteredDisplay = new ArrayList<>();
            List<String> filteredPkgs = new ArrayList<>();
            for (int i = 0; i < allPkgs.size(); i++) {
                String pkg = allPkgs.get(i);
                String name = allDisplay.get(i);
                if (!query.isEmpty() && !name.toLowerCase().contains(query) && !pkg.toLowerCase().contains(query)) {
                    continue;
                }
                String display = name + " (" + pkg + ")";
                if (curSelected.contains(pkg)) {
                    display = "✓ " + display;
                }
                filteredDisplay.add(display);
                filteredPkgs.add(pkg);
            }
            listAdapter.clear();
            listAdapter.addAll(filteredDisplay);
            listAdapter.notifyDataSetChanged();
            listView.setTag(filteredPkgs);
        };
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateList.run();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            @SuppressWarnings("unchecked")
            List<String> pkgs = (List<String>) listView.getTag();
            if (pkgs == null || position >= pkgs.size()) return;
            String clickedPkg = pkgs.get(position);
            Set<String> currentSelected = getSelectedPackages();
            if (currentSelected.contains(clickedPkg)) {
                currentSelected.remove(clickedPkg);
                int idx = allPkgs.indexOf(clickedPkg);
                String displayName = idx >= 0 ? allDisplay.get(idx) : clickedPkg;
                Toast.makeText(requireContext(), "已移除 " + displayName, Toast.LENGTH_SHORT).show();
            } else {
                currentSelected.add(clickedPkg);
                int idx = allPkgs.indexOf(clickedPkg);
                String displayName = idx >= 0 ? allDisplay.get(idx) : clickedPkg;
                Toast.makeText(requireContext(), "已添加 " + displayName, Toast.LENGTH_SHORT).show();
            }
            saveSelectedPackages(currentSelected);
            loadGameList();
            updateList.run();
        });
        dialog.show();
        updateList.run();
    }
    private int dp2px(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
    public void removeGame(String packageName) {
        Set<String> saved = getSelectedPackages();
        saved.remove(packageName);
        saveSelectedPackages(saved);
        loadGameList();
    }
    class GameAdapter extends RecyclerView.Adapter<GameAdapter.ViewHolder> {
        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView icon;
            TextView name;
            ImageButton btnRemove;
            View card;
            ViewHolder(View v) {
                super(v);
                icon = v.findViewById(R.id.gameIcon);
                name = v.findViewById(R.id.gameName);
                btnRemove = v.findViewById(R.id.btnRemove);
                card = v.findViewById(R.id.gameCard);
            }
        }
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(requireContext()).inflate(R.layout.item_game, parent, false);
            return new ViewHolder(v);
        }
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            GameApp game = gameList.get(position);
            holder.name.setText(game.appName);
            if (game.icon != null) {
                holder.icon.setImageDrawable(game.icon);
            } else {
                holder.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            }
            holder.card.setOnClickListener(v -> showGameRecords(game));
            holder.btnRemove.setOnClickListener(v -> {
                new AlertDialog.Builder(requireContext())
                    .setTitle("移除游戏")
                    .setMessage("确定要从游戏模式中移除 " + game.appName + " 吗？")
                    .setPositiveButton("确定", (d, w) -> removeGame(game.packageName))
                    .setNegativeButton("取消", null)
                    .show();
            });
        }
        @Override
        public int getItemCount() {
            return gameList.size();
        }
    }
    private void showGameRecords(GameApp game) {
        Intent intent = new Intent(activity, GameRecordsActivity.class);
        intent.putExtra("packageName", game.packageName);
        intent.putExtra("appName", game.appName);
        startActivity(intent);
    }
    static class GameApp {
        String packageName;
        String appName;
        android.graphics.drawable.Drawable icon;
    }
}
