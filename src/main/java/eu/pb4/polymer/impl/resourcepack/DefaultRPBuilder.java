package eu.pb4.polymer.impl.resourcepack;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.api.resourcepack.PolymerModelData;
import eu.pb4.polymer.impl.PolymerMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.SharedConstants;
import net.minecraft.item.Item;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import org.apache.commons.io.IOUtils;
import org.jetbrains.annotations.ApiStatus;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

@ApiStatus.Internal
public class DefaultRPBuilder implements InternalRPBuilder {
    static final JsonParser JSON_PARSER = new JsonParser();

    private final static String CLIENT_URL = "https://launcher.mojang.com/v1/objects/1cf89c77ed5e72401b869f66410934804f3d6f52/client.jar";

    private final Map<Item, JsonArray> models = new HashMap<>();
    private final Map<String, byte[]> fileMap = new HashMap<>();
    private ZipFile clientJar = null;
    private final Path outputPath;

    public DefaultRPBuilder(Path outputPath) throws Exception {
        outputPath.getParent().toFile().mkdirs();
        this.outputPath = outputPath;

        if (outputPath.toFile().exists()) {
            Files.deleteIfExists(outputPath);
        }
    }


    @Override
    public boolean addData(String path, byte[] data) {
        try {
            this.fileMap.put(path, data);
            return true;
        } catch (Exception e) {
            PolymerMod.LOGGER.error("Something went wrong while adding raw data to path: " + path);
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean copyFromPath(Path basePath) {
        try {
            Files.walkFileTree(basePath, new FileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    var relative = basePath.relativize(file);

                    var bytes = Files.readAllBytes(file);

                    fileMap.put(relative.toString(), bytes);

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    return FileVisitResult.CONTINUE;
                }
            });
            return true;
        } catch (Exception e) {
            PolymerMod.LOGGER.error("Something went wrong while copying data from: " + basePath);
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public boolean copyModAssets(String modId) {
        Optional<ModContainer> mod = FabricLoader.getInstance().getModContainer(modId);
        if (mod.isPresent()) {
            ModContainer container = mod.get();
            try {
                Path assets = container.getPath("assets");
                Files.walkFileTree(assets, new FileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        var relative = assets.relativize(file);

                        var bytes = Files.readAllBytes(file);

                        fileMap.put("assets/" + relative, bytes);

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                        return FileVisitResult.CONTINUE;
                    }
                });

                return true;
            } catch (Exception e) {
                PolymerMod.LOGGER.error("Something went wrong while copying assets of mod: " + modId);
                e.printStackTrace();
                return false;
            }
        }
        PolymerMod.LOGGER.warn("Tried to copy assets from non existing mod " + modId);
        return false;
    }

    @Override
    public boolean addCustomModelData(PolymerModelData cmdInfo) {
        try {
            JsonArray jsonArray;

            if (this.models.containsKey(cmdInfo.item())) {
                jsonArray = this.models.get(cmdInfo.item());
            } else {
                jsonArray = new JsonArray();
                this.models.put(cmdInfo.item(), jsonArray);
            }

            {
                JsonObject jsonObject = new JsonObject();
                jsonObject.addProperty("model", cmdInfo.modelPath().toString());
                JsonObject predicateObject = new JsonObject();
                predicateObject.addProperty("custom_model_data", cmdInfo.value());
                jsonObject.add("predicate", predicateObject);

                jsonArray.add(jsonObject);
            }
            JsonObject modelObject = null;
            var modelPath = "assets/" + cmdInfo.modelPath().getNamespace() + "/models/" + cmdInfo.modelPath().getPath() + ".json";

            if (modelObject == null && this.fileMap.containsKey(modelPath)) {
                modelObject = JSON_PARSER.parse(new String(this.fileMap.get(modelPath), StandardCharsets.UTF_8)).getAsJsonObject();
            }

            if (modelObject != null && modelObject.has("overrides")) {
                JsonArray array = modelObject.getAsJsonArray("overrides");

                for (JsonElement element : array) {
                    JsonObject jsonObject = element.getAsJsonObject();
                    jsonObject.get("predicate").getAsJsonObject().addProperty("custom_model_data", cmdInfo.value());
                    jsonArray.add(jsonObject);
                }
            }

            return true;
        } catch (Exception e) {
            PolymerMod.LOGGER.error(String.format("Something went wrong while adding custom model data (%s) of %s for model %s", cmdInfo.value(), Registry.ITEM.getId(cmdInfo.item()), cmdInfo.modelPath().toString()));
            e.printStackTrace();
            return false;
        }
    }

    @Override
    public CompletableFuture<Boolean> buildResourcePack() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var outputStream = new ZipOutputStream(new FileOutputStream(this.outputPath.toFile()));

                Path clientJarPath;

                if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
                    clientJarPath = FabricLoader.getInstance().getGameDir().resolve("assets_client.jar");
                } else {
                    var clientFile = MinecraftServer.class.getProtectionDomain().getCodeSource().getLocation().getPath();
                    clientJarPath = Path.of(clientFile);
                }

                if (!clientJarPath.toFile().exists()) {
                    PolymerMod.LOGGER.info("Downloading vanilla client jar...");
                    URL url = new URL(CLIENT_URL);
                    URLConnection connection = url.openConnection();
                    InputStream is = connection.getInputStream();
                    Files.copy(is, clientJarPath);
                }

                this.clientJar = new ZipFile(clientJarPath.toFile());

                boolean bool = true;

                for (Map.Entry<Item, JsonArray> entry : this.models.entrySet()) {
                    Identifier id = Registry.ITEM.getId(entry.getKey());
                    try {
                        String basePath = "assets/" + id.getNamespace() + "/models/item/";
                        JsonObject modelObject;

                        String baseModelPath;
                        {
                            Identifier itemId = Registry.ITEM.getId(entry.getKey());
                            baseModelPath = "assets/" + itemId.getNamespace() + "/models/item/" + itemId.getPath() + ".json";
                        }

                        if (this.fileMap.containsKey(baseModelPath)) {
                            modelObject = JSON_PARSER.parse(new String(this.fileMap.get(baseModelPath), StandardCharsets.UTF_8)).getAsJsonObject();
                        } else {
                            InputStream stream = this.clientJar.getInputStream(this.clientJar.getEntry(baseModelPath));
                            modelObject = JSON_PARSER.parse(IOUtils.toString(stream, StandardCharsets.UTF_8.name())).getAsJsonObject();
                        }

                        JsonArray jsonArray = new JsonArray();

                        if (modelObject.has("overrides")) {
                            jsonArray.addAll(modelObject.getAsJsonArray("overrides"));
                        }
                        jsonArray.addAll(entry.getValue());

                        modelObject.add("overrides", jsonArray);

                        outputStream.putNextEntry(new ZipEntry(basePath + id.getPath() + ".json"));
                        var bytes = modelObject.toString().getBytes(StandardCharsets.UTF_8);

                        outputStream.write(bytes, 0, bytes.length);
                        outputStream.closeEntry();

                    } catch (Exception e) {
                        PolymerMod.LOGGER.error("Something went wrong while saving model of " + id);
                        e.printStackTrace();
                        bool = false;
                    }
                }

                    {
                        if (!this.fileMap.containsKey("pack.mcmeta")) {
                            this.fileMap.put("pack.mcmeta", ("" +
                                    "{\n" +
                                    "   \"pack\":{\n" +
                                    "      \"pack_format\":" + SharedConstants.field_29738 + ",\n" +
                                    "      \"description\":\"Server resource pack\"\n" +
                                    "   }\n" +
                                    "}\n").getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    {
                        if (!this.fileMap.containsKey("pack.png")) {
                            var filePath = FabricLoader.getInstance().getGameDir().resolve("server-icon.png");

                            if (filePath.toFile().exists()) {
                                this.fileMap.put("pack.png", Files.readAllBytes(filePath));
                            } else {
                                this.fileMap.put("pack.png", Files.readAllBytes(FabricLoader.getInstance().getModContainer("polymer").get().getPath("assets/icon.png")));
                            }
                        }
                    }

                    for (var entry : fileMap.entrySet()) {
                        outputStream.putNextEntry(new ZipEntry(entry.getKey()));
                        outputStream.write(entry.getValue());
                        outputStream.closeEntry();
                    }

                outputStream.close();
                return bool;
            } catch (Exception e) {
                PolymerMod.LOGGER.error("Something went wrong while creating resource pack!");
                e.printStackTrace();
                return false;
            }

        });
    }
}