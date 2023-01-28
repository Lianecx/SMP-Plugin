package me.lianecx.discordlinker.network.adapters;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import express.Express;
import express.http.RequestMethod;
import express.http.request.Request;
import express.http.response.Response;
import express.utils.Status;
import me.lianecx.discordlinker.DiscordLinker;
import me.lianecx.discordlinker.network.Route;
import me.lianecx.discordlinker.network.Router;
import org.bukkit.ChatColor;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.stream.Collectors;

public class HttpAdapter {

    private final Express app;
    private static final String PLUGIN_VERSION = DiscordLinker.getPlugin().getDescription().getVersion();

    //If snapshot version, request test-bot at port 3101 otherwise request main-bot at port 3100
    public static final int BOT_PORT = PLUGIN_VERSION.contains("SNAPSHOT") ? 3101 : 3100;
    public static final URI BOT_URI = URI.create("http://79.205.22.220:" + BOT_PORT);

    public HttpAdapter() {
        this.app = new Express();

        this.app.all((req, res) -> {
            Route route = Route.getRouteByPath(req.getPath());
            if(route == null) {
                res.sendStatus(Status._404);
                return;
            }

            //Check auth and IP
            if((route.doesRequireToken() && !this.checkToken(req)) || (route.isBotOnly() && !this.checkIp(req.getIp()))) {
                res.setStatus(Status._401);
                res.send(Router.INVALID_AUTH.toString());
                return;
            }
            JsonObject data = this.parseRequest(req);

            if(route == Route.PUT_FILE) {
                //Special case: File upload (pass body as input stream to function)
                Router.putFile(data, req.getBody(), routerResponse -> this.respond(routerResponse, res));
            }
            else {
                route.execute(data, routerResponse -> this.respond(routerResponse, res));
            }
        });
    }

    public void connect(int port) {
        app.listen(() -> DiscordLinker.getPlugin().getLogger().info("Listening on port " + port), port);
    }

    public static int send(RequestMethod method, String route, JsonElement body) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BOT_URI + route).openConnection();

            byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
            int length = out.length;

            conn.setRequestMethod(method.getMethod());
            conn.setRequestProperty("Content-type", "application/json");
            conn.setFixedLengthStreamingMode(length);
            conn.setDoOutput(true);

            conn.connect();
            try(OutputStream os = conn.getOutputStream()) {
                os.write(out);
            }

            return conn.getResponseCode();
        }
        catch(IOException ignored) {}

        return 0;
    }

    private boolean checkToken(Request req) {
        try {
            if(DiscordLinker.getConnJson().get("hash") == null) return false;

            String token = req.getAuthorization().get(0).getData();
            String correctHash = DiscordLinker.getConnJson().get("hash").getAsString();
            return correctHash.equals(Router.createHash(token));
        }
        catch(NoSuchAlgorithmException err) {
            return false;
        }
    }

    public static void sendVerificationResponse(String code, UUID uuid) {
        JsonObject verifyJson = new JsonObject();
        verifyJson.addProperty("code", code);
        verifyJson.addProperty("uuid", uuid.toString());

        send(RequestMethod.POST, "/verify/response", verifyJson);
    }

    private JsonObject parseRequest(Request req) {
        JsonObject data = new JsonObject();

        //GET requests can't have bodies
        //PUT request bodies are file streams
        if(!req.getMethod().equals("GET") && !req.getMethod().equals("PUT"))
            data = new JsonParser().parse(new InputStreamReader(req.getBody())).getAsJsonObject();
        //Parse query
        req.getQuerys().forEach(data::addProperty);
        //Parse params
        req.getParams().forEach(data::addProperty);

        return data;
    }

    public static void checkVersion() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BOT_URI + "/version").openConnection();
            InputStream inputStream = conn.getInputStream();
            String latestVersion = new BufferedReader(new InputStreamReader(inputStream)).lines().collect(Collectors.joining("\n"));
            if(!latestVersion.equals(PLUGIN_VERSION))
                DiscordLinker.getPlugin().getLogger().info(ChatColor.AQUA + "Please update to the latest Discord-Linker version (" + latestVersion + ") for a bug-free and feature-rich experience.");

        }
        catch(IOException ignored) {}
    }

    public void disconnect() {
        app.stop();
    }

    private boolean checkIp(String ip) {
        try {
            String correctIp = InetAddress.getByName(BOT_URI.getHost()).getHostAddress();
            return ip.equals(correctIp);
        }
        catch(UnknownHostException e) {
            return false;
        }
    }

    private void respond(Router.RouterResponse response, Response res) {
        res.setStatus(response.getStatus());

        if(response.isAttachment()) res.sendAttachment(Paths.get(response.getMessage()));
        else res.send(response.getMessage());
    }
}
