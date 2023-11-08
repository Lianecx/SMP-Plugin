package me.lianecx.discordlinker.network;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import express.utils.Status;
import io.github.bananapuncher714.nbteditor.NBTEditor;
import me.lianecx.discordlinker.DiscordLinker;
import me.lianecx.discordlinker.commands.VerifyCommand;
import me.lianecx.discordlinker.utilities.ConsoleLogger;
import me.lianecx.discordlinker.utilities.LuckPermsUtil;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandException;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Team;

import java.io.*;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.bukkit.Bukkit.getPluginManager;
import static org.bukkit.Bukkit.getServer;

public class Router {

    public static final JsonObject INVALID_AUTH = new JsonObject();
    public static final JsonObject INVALID_PATH = new JsonObject();
    public static final JsonObject INVALID_PARAM = new JsonObject();
    public static final JsonObject INVALID_JSON = new JsonObject();
    public static final JsonObject INVALID_CODE = new JsonObject();
    public static final JsonObject INVALID_PLAYER = new JsonObject();
    public static final JsonObject INVALID_TEAM = new JsonObject();
    public static final JsonObject INVALID_GROUP = new JsonObject();
    public static final JsonObject ALREADY_CONNECTED = new JsonObject();
    public static final JsonObject LUCKPERMS_NOT_LOADED = new JsonObject();
    public static final JsonObject SUCCESS = new JsonObject();
    public static final Gson GSON = new Gson();
    private static final ConsoleLogger cmdLogger = new ConsoleLogger();
    private static final String URL_REGEX = "https?://[-\\w_.]{2,}\\.[a-z]{2,4}/\\S*?";
    private static final String MD_URL_REGEX = "(?i)\\[([^]]+)]\\((" + URL_REGEX + ")\\)";
    private static String verifyCode = null;

    public static void init() throws IOException {
        INVALID_AUTH.addProperty("message", "Invalid Authorization");
        INVALID_PATH.addProperty("message", "Invalid Path");
        INVALID_PARAM.addProperty("message", "Invalid method parameter");
        INVALID_JSON.addProperty("message", "Invalid JSON");
        INVALID_PLAYER.addProperty("message", "Target player does not exist or is not online");
        INVALID_TEAM.addProperty("message", "Target team does not exist");
        INVALID_GROUP.addProperty("message", "Target group does not exist");
        ALREADY_CONNECTED.addProperty("message", "This plugin is already connected with a different guild.");
        INVALID_CODE.addProperty("message", "Invalid verification code");
        LUCKPERMS_NOT_LOADED.addProperty("message", "LuckPerms is not loaded");

        Logger log = (Logger) LogManager.getRootLogger();
        log.addAppender(cmdLogger);
    }

    public static void getFile(JsonObject data, Consumer<RouterResponse> callback) {
        try {
            File file = new File(URLDecoder.decode(data.get("path").getAsString(), "utf-8"));
            if(!file.isFile()) {
                callback.accept(new RouterResponse(Status._404, INVALID_PATH.toString()));
                return;
            }

            callback.accept(new RouterResponse(Status._200, file.toString(), true));
        }
        catch(UnsupportedEncodingException err) {
            JsonObject error = new JsonObject();
            error.addProperty("message", err.toString());
            callback.accept(new RouterResponse(Status._500, error.toString()));
        }
    }

    public static void putFile(JsonObject data, InputStream fileStream, Consumer<RouterResponse> callback) {
        try(FileOutputStream outputStream = new FileOutputStream(URLDecoder.decode(data.get("path").getAsString(), "utf-8"))) {
            //Transfer body (inputStream) to outputStream
            byte[] buf = new byte[8192];
            int length;
            while((length = fileStream.read(buf)) > 0) {
                outputStream.write(buf, 0, length);
            }

            callback.accept(new RouterResponse(Status._200, SUCCESS.toString()));
        }
        catch(IOException err) {
            JsonObject error = new JsonObject();
            error.addProperty("message", err.toString());
            callback.accept(new RouterResponse(Status._500, error.toString()));
        }
    }

    public static void listFile(JsonObject data, Consumer<RouterResponse> callback) {
        try {
            Path folder = Paths.get(URLDecoder.decode(data.get("folder").getAsString(), "utf-8"));

            JsonArray content = new JsonArray();
            Stream<Path> stream = Files.list(folder);
            stream.map(path -> {
                JsonObject object = new JsonObject();
                object.addProperty("name", path.toFile().getName());
                object.addProperty("isDirectory", path.toFile().isDirectory());
                return object;
            }).forEach(content::add);
            stream.close();

            callback.accept(new RouterResponse(Status._200, content.toString()));
        }
        catch(InvalidPathException err) {
            callback.accept(new RouterResponse(Status._404, INVALID_PATH.toString()));
        }
        catch(IOException err) {
            callback.accept(new RouterResponse(Status._200, new JsonArray().toString()));
        }
    }

    public static void verifyGuild(JsonObject data, Consumer<RouterResponse> callback) {
        //If plugin is already connected to a different guild, respond with 409: Conflict
        if(DiscordLinker.getConnJson() != null && !DiscordLinker.getConnJson().get("id").getAsString().equals(data.get("id").getAsString())) {
            callback.accept(new RouterResponse(Status._409, ALREADY_CONNECTED.toString()));
            return;
        }

        verifyCode = RandomStringUtils.randomAlphanumeric(6);
        DiscordLinker.getPlugin().getLogger().info(ChatColor.YELLOW + "Verification Code: " + verifyCode);

        getServer().getScheduler().runTaskLater(DiscordLinker.getPlugin(), () -> verifyCode = null, 3600);
        callback.accept(new RouterResponse(Status._200, SUCCESS.toString()));
    }

    public static void verifyUser(JsonObject data, Consumer<RouterResponse> callback) {
        VerifyCommand.addPlayerToVerificationQueue(UUID.fromString(data.get("uuid").getAsString()), data.get("code").getAsString());
        callback.accept(new RouterResponse(Status._200, SUCCESS.toString()));
    }

    public static void command(JsonObject data, Consumer<RouterResponse> callback) {
        Bukkit.getScheduler().runTask(DiscordLinker.getPlugin(), () -> {
            JsonObject responseJson = new JsonObject();

            try {
                String cmd = URLDecoder.decode(data.get("cmd").getAsString(), "utf-8");
                DiscordLinker.getPlugin().getLogger().info(ChatColor.AQUA + "Command from Discord: /" + cmd);
                cmdLogger.startLogging();
                getServer().dispatchCommand(Bukkit.getConsoleSender(), cmd);
            }
            catch(UnsupportedEncodingException | IllegalArgumentException | CommandException err) {
                responseJson.addProperty("message", err.toString());
                callback.accept(new RouterResponse(Status._500, responseJson.toString()));
                cmdLogger.clearData();
                return;
            }
            finally {
                cmdLogger.stopLogging();
            }

            String commandResponse = String.join("\n", cmdLogger.getData());
            cmdLogger.clearData();

            // Replace all color codes with & to properly display in Discord
            responseJson.addProperty("message", commandResponse.replaceAll("\\x7F", "&"));
            callback.accept(new RouterResponse(Status._200, responseJson.toString()));
        });
    }

    public static void getPlayerNBT(JsonObject data, Consumer<RouterResponse> callback) {
        Player player = getServer().getPlayer(UUID.fromString(data.get("uuid").getAsString()));
        if(player == null) {
            callback.accept(new RouterResponse(Status._422, INVALID_PLAYER.toString()));
            return;
        }

        JsonObject responseJson = new JsonObject();
        responseJson.addProperty("data", NBTEditor.getNBTCompound(player).toString());
        callback.accept(new RouterResponse(Status._200, responseJson.toString()));
    }

    public static void chat(JsonObject data, Consumer<RouterResponse> callback) {
        String msg;
        String username;
        String replyMsg;
        String replyUsername = "";
        boolean privateMsg;
        String targetUsername = "";
        try {
            msg = data.get("msg").getAsString();
            replyMsg = data.get("reply_msg") != null && !data.get("reply_msg").isJsonNull() ? data.get("reply_msg").getAsString() : null;
            username = data.get("username").getAsString();
            privateMsg = data.get("private").getAsBoolean();
            if(replyMsg != null) replyUsername = data.get("reply_username").getAsString();
            if(privateMsg) targetUsername = data.get("target").getAsString();
        }
        catch(ClassCastException err) {
            callback.accept(new RouterResponse(Status._400, INVALID_JSON.toString()));
            return;
        }

        //Get config string and insert message
        String configPath;
        if(privateMsg) configPath = "private_message";
        else if(replyMsg != null) configPath = "reply_message";
        else configPath = "message";
        String chatMessage = DiscordLinker.getPlugin().getConfig().getString(configPath);
        chatMessage = chatMessage.replaceAll("%message%", markdownToColorCodes(msg));

        if(replyMsg != null) {
            chatMessage = chatMessage.replaceAll("%reply_message%", markdownToColorCodes(replyMsg));
            chatMessage = chatMessage.replaceAll("%reply_username%", replyUsername);

            String reducedReplyMsg = replyMsg.length() > 30 ? replyMsg.substring(0, 30) + "..." : replyMsg;
            //if reply message is a url, don't reduce it
            if(replyMsg.matches(URL_REGEX) || replyMsg.matches(MD_URL_REGEX)) reducedReplyMsg = replyMsg;
            chatMessage = chatMessage.replaceAll("%reply_message_reduced%", markdownToColorCodes(reducedReplyMsg));
        }

        //Translate color codes
        chatMessage = ChatColor.translateAlternateColorCodes('&', chatMessage);

        //Insert username
        chatMessage = chatMessage.replaceAll("%username%", username);

        //Make links clickable
        Pattern mdUrlPattern = Pattern.compile(MD_URL_REGEX);

        ComponentBuilder chatBuilder = new ComponentBuilder("");

        StringBuilder tempMessage = new StringBuilder();
        for(String word : chatMessage.split(" ")) {
            if(word.matches(URL_REGEX)) {
                if(tempMessage.length() != 0)
                    chatBuilder.append(tempMessage.toString(), ComponentBuilder.FormatRetention.NONE);
                tempMessage.setLength(0); //Clear tempMessage

                chatBuilder.append(word);
                chatBuilder.event(new ClickEvent(ClickEvent.Action.OPEN_URL, word));
                chatBuilder.underlined(true);
                tempMessage.append(" ");
            }
            else if(word.matches(MD_URL_REGEX)) {
                if(tempMessage.length() != 0)
                    chatBuilder.append(tempMessage.toString(), ComponentBuilder.FormatRetention.NONE);
                tempMessage.setLength(0); //Clear tempMessage

                Matcher matcher = mdUrlPattern.matcher(word);
                //noinspection ResultOfMethodCallIgnored
                matcher.find();

                chatBuilder.append(matcher.group(1));
                chatBuilder.event(new ClickEvent(ClickEvent.Action.OPEN_URL, matcher.group(2)));
                chatBuilder.underlined(true);
                tempMessage.append(" ");
            }
            else {
                tempMessage.append(word).append(" ");
            }
        }
        if(tempMessage.length() != 0) chatBuilder.append(tempMessage.toString(), ComponentBuilder.FormatRetention.NONE);

        if(privateMsg) {
            Player player = getServer().getPlayer(targetUsername);
            if(player == null) {
                callback.accept(new RouterResponse(Status._422, INVALID_PLAYER.toString()));
                return;
            }

            player.spigot().sendMessage(chatBuilder.create());
        }
        else {
            getServer().spigot().broadcast(chatBuilder.create());
        }

        callback.accept(new RouterResponse(Status._200, SUCCESS.toString()));
    }

    public static void disconnect(JsonObject data, Consumer<RouterResponse> callback) {
        boolean deleted = DiscordLinker.getPlugin().deleteConn();

        if(deleted) {
            DiscordLinker.getPlugin().getLogger().info("Disconnected from discord...");
            callback.accept(new RouterResponse(Status._200, SUCCESS.toString()));
        }
        else {
            JsonObject error = new JsonObject();
            error.addProperty("message", "Could not delete connection file");
            callback.accept(new RouterResponse(Status._500, error.toString()));
        }
    }

    public static void connect(JsonObject data, Consumer<RouterResponse> callback) {
        try {
            DiscordLinker.getPlugin().getLogger().info("Connection request...");

            if(!data.get("code").getAsString().equals(verifyCode)) {
                DiscordLinker.getPlugin().getLogger().info("Connection unsuccessful");
                callback.accept(new RouterResponse(Status._401, INVALID_CODE.toString()));
                return;
            }

            //Create random 32-character hex string
            String token = new BigInteger(130, new SecureRandom()).toString(16);

            JsonObject connJson = new JsonObject();
            connJson.add("channels", new JsonArray());
            connJson.add("stats-channels", new JsonArray());
            connJson.add("id", data.get("id"));
            connJson.add("ip", data.get("ip"));
            if(data.has("requiredRoleToJoin") && !data.get("requiredRoleToJoin").isJsonNull())
                connJson.add("requiredRoleToJoin", data.get("requiredRoleToJoin"));
            connJson.addProperty("protocol", "http");
            connJson.addProperty("hash", createHash(token));

            DiscordLinker.getPlugin().updateConn(connJson);

            DiscordLinker.getPlugin().getLogger().info("Successfully connected to Discord!");

            JsonObject connectResponse = getConnectResponse();
            connectResponse.addProperty("token", token);
            callback.accept(new RouterResponse(Status._200, connectResponse.toString()));
        }
        catch(IOException | NoSuchAlgorithmException err) {
            DiscordLinker.getPlugin().getLogger().info("Connection unsuccessful");
            callback.accept(new RouterResponse(Status._500, err.toString()));
        }
        finally {
            verifyCode = null;
        }
    }

    public static void addChatChannel(JsonObject data, Consumer<RouterResponse> callback) {
        callback.accept(handleChangeArray(data, "channels", true));
    }

    public static void removeChatChannel(JsonObject data, Consumer<RouterResponse> callback) {
        callback.accept(handleChangeArray(data, "channels", false));
    }

    public static void addStatsChannel(JsonObject data, Consumer<RouterResponse> callback) {
        callback.accept(handleChangeArray(data, "stats-channels", true));
    }

    public static void removeStatsChannel(JsonObject data, Consumer<RouterResponse> callback) {
        callback.accept(handleChangeArray(data, "stats-channels", false));
    }

    public static void addSyncedRole(JsonObject data, Consumer<RouterResponse> callback) {
        RouterResponse response = updateSyncedRoleMembers(data);

        if(response.getStatus() == Status._200) {
            if(!data.has("players") || data.get("players").isJsonNull()) {
                getPlayers(data.get("name").getAsString(), data.get("isGroup").getAsBoolean(), players -> {
                    JsonArray playersArray = new JsonArray();
                    players.forEach(playersArray::add);
                    data.add("players", playersArray);
                    callback.accept(handleChangeArray(data, "synced-roles", true));
                });
            }
            else callback.accept(handleChangeArray(data, "synced-roles", true));
        }
        else callback.accept(response);
    }

    public static void removeSyncedRole(JsonObject data, Consumer<RouterResponse> callback) {
        callback.accept(handleChangeArray(data, "synced-roles", false));
    }

    public static void updateSyncedRole(JsonObject data, Consumer<RouterResponse> callback) {
        RouterResponse response = updateSyncedRoleMembers(data);

        if(response.getStatus() == Status._200) callback.accept(handleChangeArray(data, "synced-roles", true));
        else callback.accept(response);
    }

    public static void listPlayers(JsonObject data, Consumer<RouterResponse> callback) {
        List<String> onlinePlayers = getServer().getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
        callback.accept(new RouterResponse(Status._200, GSON.toJson(onlinePlayers, new TypeToken<List<String>>() {}.getType())));
    }

    public static void listGroupsAndTeams(JsonObject data, Consumer<RouterResponse> callback) {
        List<String> groups = new ArrayList<>();
        List<String> teams = new ArrayList<>();

        if(getPluginManager().isPluginEnabled("LuckPerms")) {
            groups = LuckPermsUtil.getGroupNames();
        }

        getServer().getScoreboardManager().getMainScoreboard().getTeams().forEach(team -> teams.add(team.getName()));

        JsonObject response = new JsonObject();
        response.add("groups", GSON.toJsonTree(groups, new TypeToken<List<String>>() {}.getType()));
        response.add("teams", GSON.toJsonTree(teams, new TypeToken<List<String>>() {}.getType()));
        callback.accept(new RouterResponse(Status._200, response.toString()));
    }

    private static RouterResponse updateSyncedRoleMembers(JsonObject data) {
        if(data.get("isGroup").getAsBoolean()) {
            if(!getPluginManager().isPluginEnabled("LuckPerms"))
                return new RouterResponse(Status._501, LUCKPERMS_NOT_LOADED.toString());

            List<String> uuids = data.get("players") != null && !data.get("players").isJsonNull() ?
                    GSON.fromJson(data.get("players"), new TypeToken<List<String>>() {}.getType()) :
                    null;
            return LuckPermsUtil.updateGroupMembers(data.get("name").getAsString(), uuids);
        }
        else {
            Team team = getServer().getScoreboardManager().getMainScoreboard().getTeam(data.get("name").getAsString());
            if(team == null) return new RouterResponse(Status._404, INVALID_TEAM.toString());

            if(data.has("players") && !data.get("players").isJsonNull()) {
                List<String> addedEntries = new ArrayList<>();

                // Add players from json to team
                for(JsonElement uuid : data.get("players").getAsJsonArray()) {
                    OfflinePlayer player = getServer().getOfflinePlayer(UUID.fromString(uuid.getAsString()));
                    if(team.getEntries().contains(player.getName())) continue;
                    team.addEntry(player.getName());
                    addedEntries.add(player.getName());
                }
                //Remove players from team that were not added (not in json)
                for(String entry : team.getEntries()) {
                    if(addedEntries.contains(entry)) continue;
                    team.removeEntry(entry);
                }
            }
            return new RouterResponse(Status._200, SUCCESS.toString());
        }
    }

    private static String markdownToColorCodes(String markdown) {
        //Format **bold**
        markdown = markdown.replaceAll("\\*\\*(.+?)\\*\\*", "&l$1&r");
        //Format __underline__
        markdown = markdown.replaceAll("__(.+?)__", "&n$1&r");
        //Format *italic* and _italic_
        markdown = markdown.replaceAll("_(.+?)_|\\*(.+?)\\*", "&o$1$2&r");
        //Format ~~strikethrough~~
        markdown = markdown.replaceAll("~~(.+?)~~", "&m$1&r");
        //Format ??obfuscated??
        markdown = markdown.replaceAll("\\?\\?(.+?)\\?\\?", "&k$1&r");
        //Format inline and multiline `code` blocks
        markdown = markdown.replaceAll("(?s)```[^\\n]*\\n(.+)```|```(.+)```", "&7&n$1$2&r");
        markdown = markdown.replaceAll("`(.+?)`", "&7&n$1&r");
        //Format ||spoilers||
        markdown = markdown.replaceAll("\\|\\|(.+?)\\|\\|", "&8$1&r");
        //Format '> quotes'
        markdown = markdown.replaceAll(">+ (.+)", "&7| $1&r");

        return markdown;
    }

    public static RouterResponse handleChangeArray(JsonObject entry, String jsonFieldName, boolean addEntry) {
        try {
            JsonObject connJson = DiscordLinker.getConnJson();
            JsonArray array;
            if(!connJson.has(jsonFieldName)) array = new JsonArray();
            else array = connJson.get(jsonFieldName).getAsJsonArray();

            //Remove channels with same id as entry
            for(JsonElement jsonEntry : array) {
                if(jsonEntry.getAsJsonObject().get("id").getAsString().equals(entry.get("id").getAsString())) {
                    array.remove(jsonEntry);
                    break;
                }
            }
            if(addEntry) array.add(entry);

            //Update connJson with new channels
            connJson.add(jsonFieldName, array);
            DiscordLinker.getPlugin().updateConn(connJson);

            return new RouterResponse(Status._200, array.toString());
        }
        catch(IOException err) {
            JsonObject error = new JsonObject();
            error.addProperty("message", err.toString());
            return new RouterResponse(Status._500, error.toString());
        }
    }

    public static void getPlayers(String name, boolean isGroup, Consumer<List<String>> callback) {
        if(isGroup) {
            if(!getPluginManager().isPluginEnabled("LuckPerms")) {
                callback.accept(new ArrayList<>());
                return;
            }

            LuckPermsUtil.getGroupMembers(name, callback);
        }
        else {
            Team team = getServer().getScoreboardManager().getMainScoreboard().getTeam(name);
            if(team == null) {
                callback.accept(new ArrayList<>());
                return;
            }

            List<String> players = new ArrayList<>();
            //Add uuids of all players in team to players list
            team.getEntries().forEach(entry -> {
                OfflinePlayer player = getServer().getOfflinePlayer(entry);
                if(player != null) players.add(player.getUniqueId().toString());
            });

            callback.accept(players);
        }
    }

    public static String createHash(String originalString) throws NoSuchAlgorithmException {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        final byte[] hashBytes = digest.digest(originalString.getBytes(StandardCharsets.UTF_8));

        StringBuilder hexString = new StringBuilder(2 * hashBytes.length);
        for(byte hashByte : hashBytes) {
            String hex = Integer.toHexString(0xff & hashByte);
            if(hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }

        return hexString.toString();
    }

    public static JsonObject getConnectResponse() {
        try {
            JsonObject response = new JsonObject();
            response.addProperty("version", getServer().getBukkitVersion().split("-")[0]);
            response.addProperty("online", getServer().getOnlineMode());
            response.addProperty("worldPath", URLEncoder.encode(getWorldPath(), "utf-8"));
            response.addProperty("path", URLEncoder.encode(getServer().getWorldContainer().getCanonicalPath(), "utf-8"));
            response.addProperty("floodgatePrefix", getFloodgatePrefix());

            return response;
        }
        catch(IOException err) {
            return null;
        }
    }

    private static String getWorldPath() throws IOException {
        Properties serverProperties = new Properties();
        serverProperties.load(Files.newInputStream(Paths.get("server.properties")));
        String worldName = serverProperties.getProperty("level-name");

        return Paths.get(getServer().getWorldContainer().getCanonicalPath(), worldName).toString();
    }

    private static String getFloodgatePrefix() {
        //Load yaml file
        File floodgateConfig = new File("plugins/floodgate/config.yml");
        if(!floodgateConfig.exists()) return null;

        Configuration config = YamlConfiguration.loadConfiguration(floodgateConfig);
        return config.getString("username-prefix");
    }

    public static class RouterResponse {
        private final String message;
        private final Status status;
        private final boolean isAttachment;

        public RouterResponse(Status status, String message) {
            this.status = status;
            this.message = message;
            this.isAttachment = false;
        }

        public RouterResponse(Status status, String message, boolean isAttachment) {
            this.status = status;
            this.message = message;
            this.isAttachment = isAttachment;
        }

        public String getMessage() {
            return message;
        }

        public Status getStatus() {
            return status;
        }

        public boolean isAttachment() {
            return isAttachment;
        }
    }
}
