package origin.jack.blockorigin.internal;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.loader.api.FabricLoader;
import origin.jack.blockorigin.BlockOriginMod;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and exposes the mod's user-editable settings at
 * {@code <minecraft>/config/blockorigin.json}. If the file is missing on
 * startup, defaults are written there so users have something to edit.
 * Forward-compatible: extra keys in the file are ignored, missing keys fall
 * back to defaults, so a config from an older version still loads cleanly.
 */
public final class Config {

    public record Settings(boolean showJadeTooltip, boolean autoPromptOnFirstLoad, int chunksPerTick) {
        public static final Settings DEFAULT = new Settings(true, true, 4);

        public static final Codec<Settings> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("showJadeTooltip", DEFAULT.showJadeTooltip).forGetter(Settings::showJadeTooltip),
                Codec.BOOL.optionalFieldOf("autoPromptOnFirstLoad", DEFAULT.autoPromptOnFirstLoad).forGetter(Settings::autoPromptOnFirstLoad),
                Codec.intRange(1, 64).optionalFieldOf("chunksPerTick", DEFAULT.chunksPerTick).forGetter(Settings::chunksPerTick)
        ).apply(i, Settings::new));
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile Settings current = Settings.DEFAULT;

    private Config() {}

    public static Settings get() {
        return current;
    }

    /** Read {@code config/blockorigin.json}, or write defaults if it doesn't exist. */
    public static void load() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("blockorigin.json");
        try {
            if (!Files.exists(file)) {
                write(Settings.DEFAULT, file);
                BlockOriginMod.LOGGER.info("Config: wrote defaults to {}", file);
                current = Settings.DEFAULT;
                return;
            }
            JsonElement parsed = JsonParser.parseString(Files.readString(file));
            Settings.CODEC.parse(JsonOps.INSTANCE, parsed)
                    .resultOrPartial(err -> BlockOriginMod.LOGGER.warn("Config: parse error, falling back to defaults — {}", err))
                    .ifPresentOrElse(
                            s -> { current = s; BlockOriginMod.LOGGER.info("Config loaded from {}", file); },
                            () -> current = Settings.DEFAULT
                    );
        } catch (IOException e) {
            BlockOriginMod.LOGGER.warn("Config: I/O error, falling back to defaults — {}", e.getMessage());
            current = Settings.DEFAULT;
        }
    }

    /**
     * Write all fields explicitly. The codec's {@code optionalFieldOf(key, default)}
     * omits fields equal to their defaults on encode, which would produce {@code {}}
     * for a freshly-installed mod — useless when the whole point of the file is to
     * show the user what's editable. Manual JSON construction sidesteps that.
     * When adding a new setting: update {@link Settings} record + the codec + this
     * method.
     */
    private static void write(Settings settings, Path file) throws IOException {
        JsonObject obj = new JsonObject();
        obj.addProperty("showJadeTooltip", settings.showJadeTooltip());
        obj.addProperty("autoPromptOnFirstLoad", settings.autoPromptOnFirstLoad());
        obj.addProperty("chunksPerTick", settings.chunksPerTick());
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(obj));
    }
}
