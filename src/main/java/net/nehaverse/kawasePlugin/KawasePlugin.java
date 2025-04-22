package net.nehaverse.kawasePlugin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class KawasePlugin extends JavaPlugin implements TabExecutor {

    // VaultのEconomyインスタンス
    private static Economy econ = null;

    // SQLiteファイル名
    private static final String DATABASE_NAME = "kawase.db";
    // DB接続オブジェクト
    private Connection connection;

    // 為替レート取得先
    private static final String API_URL = "https://exchange-rate-api.krnk.org/api/rate";

    // 取り扱う通貨 (外貨)
    private static final Set<String> VALID_CURRENCIES = new LinkedHashSet<>(Arrays.asList(
            "USD", "EUR", "GBP", "AUD", "NZD", "CAD", "CHF", "TRY", "ZAR", "MXN"
    ));

    @Override
    public void onEnable() {
        // Vault経済プラグインのセットアップをし、エラーならプラグイン無効化
        if (!setupEconomy()) {
            getLogger().severe("VaultのEconomyが見つからない、あるいは他に対応する経済プラグインがありません。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // SQLite接続
        try {
            openConnection();
            createTables();
        } catch (SQLException e) {
            e.printStackTrace();
            getLogger().severe("データベース接続に問題があるため、プラグインを無効化します。");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // コマンド登録
        getCommand("rpay").setExecutor(this);
        getCommand("rpay").setTabCompleter(this);
        getCommand("rsell").setExecutor(this);
        getCommand("rsell").setTabCompleter(this);
        getCommand("kawase").setExecutor(this);

        getLogger().info("KawasePlugin(Vault)が有効化されました。");
    }

    @Override
    public void onDisable() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        getLogger().info("KawasePlugin(Vault)が無効化されました。");
    }

    /**
     * VaultのEconomyをセットアップ
     */
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        econ = rsp.getProvider();
        return (econ != null);
    }

    /**
     * SQLite接続
     */
    private void openConnection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        String url = "jdbc:sqlite:" + getDataFolder().getAbsolutePath() + "/" + DATABASE_NAME;
        connection = DriverManager.getConnection(url);
    }

    /**
     * SQLite テーブル作成 (外貨保有量のみ)
     */
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            // user_currencies: プレイヤーごとの外貨保有量
            stmt.execute("CREATE TABLE IF NOT EXISTS user_currencies (" +
                    "player_uuid TEXT NOT NULL," +
                    "currency TEXT NOT NULL," +
                    "amount REAL NOT NULL," +
                    "PRIMARY KEY(player_uuid, currency)" +
                    ")");
        }
    }

    /**
     * 現在の為替レートを取得
     * 返り値: 通貨略号 -> 現在の1外貨あたりのJPYレート
     */
    private Map<String, Double> fetchCurrentRates() {
        Map<String, Double> result = new HashMap<>();
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root.isJsonObject()) {
                    JsonObject obj = root.getAsJsonObject();
                    // 例: "USD_JPY" : 150.123, ...
                    for (String key : obj.keySet()) {
                        if (key.endsWith("_JPY")) {
                            double rate = obj.get(key).getAsDouble();
                            String currency = key.replace("_JPY", "").toUpperCase();
                            result.put(currency, rate);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /**
     * 指定プレイヤーが保有している外貨数量を取得
     */
    private double getUserCurrencyAmount(Player player, String currency) throws SQLException {
        String uuid = player.getUniqueId().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT amount FROM user_currencies WHERE player_uuid = ? AND currency = ?"
        )) {
            ps.setString(1, uuid);
            ps.setString(2, currency.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble("amount");
                }
            }
        }
        return 0.0;
    }

    /**
     * ユーザの外貨残高を更新
     */
    private void setUserCurrencyAmount(Player player, String currency, double amount) throws SQLException {
        String uuid = player.getUniqueId().toString();
        // まず現在の量を調べる
        double currentAmt = getUserCurrencyAmount(player, currency);

        // 新しい量がゼロの場合、レコードを削除
        if (amount == 0.0) {
            try (PreparedStatement delete = connection.prepareStatement(
                    "DELETE FROM user_currencies WHERE player_uuid = ? AND currency = ?"
            )) {
                delete.setString(1, uuid);
                delete.setString(2, currency.toUpperCase());
                delete.executeUpdate();
            }
        } else if (currentAmt == 0.0) {
            // 現在量がゼロで、かつ新しい量がある場合、INSERT
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO user_currencies(player_uuid, currency, amount) VALUES(?, ?, ?)"
            )) {
                insert.setString(1, uuid);
                insert.setString(2, currency.toUpperCase());
                insert.setDouble(3, amount);
                insert.executeUpdate();
            }
        } else {
            // 現在量が既にあり、新しい量がゼロでない場合、UPDATE
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE user_currencies SET amount = ? WHERE player_uuid = ? AND currency = ?"
            )) {
                update.setDouble(1, amount);
                update.setString(2, uuid);
                update.setString(3, currency.toUpperCase());
                update.executeUpdate();
            }
        }
    }

    /**
     * onTabComplete実装: /rpay, /rsell の第2引数で通貨補完を出す
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player)) return Collections.emptyList();
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        // /rpay <金額> <通貨> か /rsell <金額> <通貨>
        if ((cmd.equals("rpay") || cmd.equals("rsell")) && args.length == 2) {
            return VALID_CURRENCIES.stream()
                    .filter(cur -> cur.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    /**
     * onCommand実装
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("プレイヤーのみ実行可能なコマンドです。");
            return true;
        }
        Player player = (Player) sender;
        String cmd = command.getName().toLowerCase(Locale.ROOT);

        try {
            switch (cmd) {
                case "rpay":
                    return handleRPay(player, args);
                case "rsell":
                    return handleRSell(player, args);
                case "kawase":
                    return handleKawase(player);
                default:
                    return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
            player.sendMessage("エラーが発生しました。");
            return true;
        }
    }

    /**
     * /rpayコマンドの処理
     */
    private boolean handleRPay(Player player, String[] args) throws SQLException {
        // /rpay show で外貨保有状況を表示
        if (args.length == 1 && args[0].equalsIgnoreCase("show")) {
            showUserCurrencies(player);
            return true;
        }
        // /rpay <金額> <通貨> -> 外貨を買う ( 日本円[vault] -> 支払い )
        if (args.length == 2) {
            double amount;
            try {
                amount = Double.parseDouble(args[0]);
            } catch (NumberFormatException e) {
                player.sendMessage("金額の指定が不正です: " + args[0]);
                return true;
            }
            String currency = args[1].toUpperCase();
            if (!VALID_CURRENCIES.contains(currency)) {
                player.sendMessage("無効な通貨です。利用可能通貨: " + VALID_CURRENCIES);
                return true;
            }

            // レート取得
            Map<String, Double> rates = fetchCurrentRates();
            if (!rates.containsKey(currency)) {
                player.sendMessage("レートが取得できませんでした: " + currency);
                return true;
            }
            double rate = rates.get(currency);

            // 必要な日本円
            double costInYen = amount * rate;
            double playerYen = econ.getBalance(player);
            if (playerYen < costInYen) {
                player.sendMessage("所持金が足りません。必要: " + costInYen + "円, 所持: " + playerYen + "円");
                return true;
            }

            // Vault 経済から日本円を引く
            econ.withdrawPlayer(player, costInYen);

            // 外貨を増やす(データベース)
            double currentAmt = getUserCurrencyAmount(player, currency);
            setUserCurrencyAmount(player, currency, currentAmt + amount);

            player.sendMessage("購入完了: " + currency + " " + amount
                    + " を " + costInYen + "円 で購入しました。");
            player.sendMessage("現在の所持金: " + econ.getBalance(player) + "円");
            return true;
        }

        player.sendMessage("使い方: /rpay <金額> <通貨> または /rpay show");
        return true;
    }

    /**
     * /rsellコマンドの処理
     */
    private boolean handleRSell(Player player, String[] args) throws SQLException {
        if (args.length != 2) {
            player.sendMessage("使い方: /rsell <金額> <通貨>");
            return true;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage("金額の形式が不正です: " + args[0]);
            return true;
        }
        String currency = args[1].toUpperCase();
        if (!VALID_CURRENCIES.contains(currency)) {
            player.sendMessage("無効な通貨です。利用可能通貨: " + VALID_CURRENCIES);
            return true;
        }

        // レート取得
        Map<String, Double> rates = fetchCurrentRates();
        if (!rates.containsKey(currency)) {
            player.sendMessage("レートが取得できませんでした: " + currency);
            return true;
        }
        double rate = rates.get(currency);

        // プレイヤーが保有している通貨量チェック
        double currentAmt = getUserCurrencyAmount(player, currency);
        if (currentAmt < amount) {
            player.sendMessage("売却する " + currency + " " + amount + " は、現在の所持量を超えています。(所持: " + currentAmt + ")");
            return true;
        }

        // 円換算
        double yenToGain = amount * rate;

        // Vaultに入金
        econ.depositPlayer(player, yenToGain);

        // 外貨を減らす
        setUserCurrencyAmount(player, currency, currentAmt - amount);

        player.sendMessage("売却完了: " + currency + " " + amount
                + " を " + yenToGain + "円 で売却しました。");
        player.sendMessage("現在の所持金: " + econ.getBalance(player) + "円");
        return true;
    }

    /**
     * /kawaseコマンド: 通貨レート一覧を表示
     */
    private boolean handleKawase(Player player) {
        Map<String, Double> rates = fetchCurrentRates();
        if (rates.isEmpty()) {
            player.sendMessage("為替レートを取得できませんでした。");
            return true;
        }
        player.sendMessage("─── 現在の為替レート(1外貨あたりのJPY) ───");
        for (String cur : VALID_CURRENCIES) {
            Double r = rates.get(cur);
            if (r != null) {
                player.sendMessage(cur + ": " + r + " 円");
            } else {
                player.sendMessage(cur + ": 取得不可");
            }
        }
        return true;
    }

    /**
     * /rpay show 用の外貨一覧表示
     */
    private void showUserCurrencies(Player player) throws SQLException {
        player.sendMessage("あなたの現在の所持金(Vault): " + econ.getBalance(player) + "円");

        // user_currenciesから所持外貨を全件表示
        String uuid = player.getUniqueId().toString();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT currency, amount FROM user_currencies WHERE player_uuid = ?"
        )) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                player.sendMessage("─── あなたの外貨保有一覧 ───");
                boolean foundAny = false;
                while (rs.next()) {
                    foundAny = true;
                    String currency = rs.getString("currency");
                    double amt = rs.getDouble("amount");
                    player.sendMessage(currency + ": " + amt);
                }
                if (!foundAny) {
                    player.sendMessage("外貨はありません。");
                }
            }
        }
    }
}
