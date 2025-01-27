package xyz.lzgzxs.vmqZanZhu;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class JsonStorage {
    private final File storageFile;
    private final Gson gson;

    public JsonStorage(File dataFolder) {
        this.storageFile = new File(dataFolder, "storage.json");
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(UUID.class, new UUIDTypeAdapter())
                .registerTypeAdapter(VMQZanZhu.OrderInfo.class, new OrderInfoTypeAdapter())
                .create();
    }

    public void saveData(Map<UUID, String> playerPendingOrders, Map<String, VMQZanZhu.OrderInfo> orderMap) {
        Map<String, Object> data = new HashMap<>();
        data.put("playerPendingOrders", playerPendingOrders);
        data.put("orderMap", orderMap);

        try (Writer writer = new FileWriter(storageFile)) {
            gson.toJson(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public Map<String, Object> loadData() {
        if (!storageFile.exists()) {
            return new HashMap<>();
        }

        try (Reader reader = new FileReader(storageFile)) {
            Type mapType = new TypeToken<Map<String, Object>>(){}.getType();
            JsonObject jsonObject = gson.fromJson(reader, JsonObject.class);
            Map<String, Object> result = new HashMap<>();

            // 处理 playerPendingOrders
            if (jsonObject.has("playerPendingOrders")) {
                Map<UUID, String> playerPendingOrders = new HashMap<>();
                JsonObject pendingOrders = jsonObject.getAsJsonObject("playerPendingOrders");
                for (Map.Entry<String, JsonElement> entry : pendingOrders.entrySet()) {
                    playerPendingOrders.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
                }
                result.put("playerPendingOrders", playerPendingOrders);
            }

            // 处理 orderMap
            if (jsonObject.has("orderMap")) {
                Map<String, VMQZanZhu.OrderInfo> orderMap = new HashMap<>();
                JsonObject orders = jsonObject.getAsJsonObject("orderMap");
                for (Map.Entry<String, JsonElement> entry : orders.entrySet()) {
                    JsonObject orderObj = entry.getValue().getAsJsonObject();
                    UUID playerUUID = UUID.fromString(orderObj.get("playerUUID").getAsString());
                    String projectId = orderObj.get("projectId").getAsString();
                    orderMap.put(entry.getKey(), new VMQZanZhu.OrderInfo(playerUUID, projectId));
                }
                result.put("orderMap", orderMap);
            }

            return result;
        } catch (IOException e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    private static class UUIDTypeAdapter implements JsonSerializer<UUID>, JsonDeserializer<UUID> {
        @Override
        public JsonElement serialize(UUID src, Type typeOfSrc, JsonSerializationContext context) {
            return new JsonPrimitive(src.toString());
        }

        @Override
        public UUID deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            return UUID.fromString(json.getAsString());
        }
    }

    private static class OrderInfoTypeAdapter implements JsonSerializer<VMQZanZhu.OrderInfo>, JsonDeserializer<VMQZanZhu.OrderInfo> {
        @Override
        public JsonElement serialize(VMQZanZhu.OrderInfo src, Type typeOfSrc, JsonSerializationContext context) {
            JsonObject obj = new JsonObject();
            obj.addProperty("playerUUID", src.getPlayerUUID().toString());
            obj.addProperty("projectId", src.getProjectId());
            return obj;
        }

        @Override
        public VMQZanZhu.OrderInfo deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();
            UUID playerUUID = UUID.fromString(obj.get("playerUUID").getAsString());
            String projectId = obj.get("projectId").getAsString();
            return new VMQZanZhu.OrderInfo(playerUUID, projectId);
        }
    }
}
