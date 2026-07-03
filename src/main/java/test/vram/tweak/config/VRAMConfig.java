package test.vram.tweak.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import test.vram.tweak.compression.S3TCConfig;

/**
 * Root config. Serialized to config/vram-tweak.json.
 */
public class VRAMConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILENAME = "vram-tweak.json";
    private static final Logger LOGGER = LoggerFactory.getLogger("vram-tweak/config");

    @SerializedName("version")
    public int version = 1;

    @SerializedName("vram")
    public VRAMSection vram = new VRAMSection();

    @SerializedName("texture")
    public TextureSection texture = new TextureSection();

    @SerializedName("diagnostic")
    public DiagnosticSection diagnostic = new DiagnosticSection();

    @SerializedName("hud")
    public HUDSection hud = new HUDSection();

    @SerializedName("governor")
    public GovernorSection governor = new GovernorSection();

    @SerializedName("particle")
    public ParticleSection particle = new ParticleSection();

    @SerializedName("s3tc")
    public S3TCConfig s3tc = new S3TCConfig();

    @SerializedName("cas")
    public CASSection cas = new CASSection();

    // ---- singleton ----

    private static VRAMConfig instance;
    private static Path configPath;

    public static VRAMConfig getInstance() {
        if (instance == null) instance = new VRAMConfig();
        return instance;
    }

    public static void load(Path configDir) {
        configPath = configDir.resolve(FILENAME);
        if (Files.exists(configPath)) {
            try {
                instance = GSON.fromJson(Files.readString(configPath), VRAMConfig.class);
                if (instance == null) instance = new VRAMConfig();
            } catch (IOException e) {
                LOGGER.warn("Failed to read config, using defaults", e);
                instance = new VRAMConfig();
            }
        } else {
            instance = new VRAMConfig();
            save();
        }
    }

    public static void save() {
        if (configPath == null) return;
        try {
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(getInstance()),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            LOGGER.error("Failed to save config", e);
        }
    }

    // ---- VRAM section ----

    public static class VRAMSection {
        @SerializedName("enabled")
        public boolean enabled = false;

        @SerializedName("shadowMapMaxSize")
        public int shadowMapMaxSize = 1024;

        @SerializedName("formatDownscale")
        public boolean formatDownscale = false;

        @SerializedName("depthDownscale")
        public boolean depthDownscale = false;

        @SerializedName("budgetTracking")
        public boolean budgetTracking = false;

        @SerializedName("budgetWarningPercent")
        public int budgetWarningPercent = 80;
    }

    // ---- Texture section ----

    public static class TextureSection {
        @SerializedName("animationLimit")
        public boolean animationLimit = false;

        @SerializedName("maxAnimationFrames")
        public int maxAnimationFrames = 32;

        @SerializedName("atlasSizeLimit")
        public boolean atlasSizeLimit = false;

        @SerializedName("maxAtlasSize")
        public int maxAtlasSize = 4096;

        @SerializedName("spriteDownsample")
        public boolean spriteDownsample = false;

        @SerializedName("maxSpriteSize")
        public int maxSpriteSize = 64;
    }

    // ---- Diagnostic section ----

    public static class DiagnosticSection {
        @SerializedName("enabled")
        public boolean enabled = true;

        @SerializedName("startupReport")
        public boolean startupReport = true;

        @SerializedName("verificationLog")
        public boolean verificationLog = false;

        @SerializedName("logIntervalSeconds")
        public int logIntervalSeconds = 5;

        @SerializedName("ringBufferSize")
        public int ringBufferSize = 60;

        @SerializedName("logDirectory")
        public String logDirectory = "logs/vram-tweak";
    }

    // ---- HUD section ----

    public static class HUDSection {
        @SerializedName("enabled")
        public boolean enabled = true;

        @SerializedName("anchor")
        public String anchor = "TOP_LEFT";

        @SerializedName("offsetX")
        public int offsetX = 4;

        @SerializedName("offsetY")
        public int offsetY = 4;

        @SerializedName("showFps")
        public boolean showFps = true;

        @SerializedName("showFpsAvg")
        public boolean showFpsAvg = true;

        @SerializedName("showFps1Percent")
        public boolean showFps1Percent = false;

        @SerializedName("showFps01Percent")
        public boolean showFps01Percent = false;

        @SerializedName("showFrameTime")
        public boolean showFrameTime = true;

        @SerializedName("showVram")
        public boolean showVram = true;

        @SerializedName("showAllocations")
        public boolean showAllocations = false;

        @SerializedName("showAtlas")
        public boolean showAtlas = true;

        @SerializedName("showDownscales")
        public boolean showDownscales = true;

        @SerializedName("showBudget")
        public boolean showBudget = true;

        @SerializedName("showDrawCalls")
        public boolean showDrawCalls = false;

        @SerializedName("bgAlpha")
        public float bgAlpha = 0.35f;
    }

    // ---- Governor section ----

    public static class GovernorSection {
        @SerializedName("enabled")
        public boolean enabled = false;

        @SerializedName("hysteresis")
        public int hysteresis = 10;

        @SerializedName("minDistance")
        public int minDistance = 4;

        @SerializedName("cooldownTicks")
        public int cooldownTicks = 100;
    }

    // ---- Particle section ----

    public static class ParticleSection {
        @SerializedName("enabled")
        public boolean enabled = false;

        @SerializedName("maxParticles")
        public int maxParticles = 2000;
    }

    // ---- CAS section ----

    public static class CASSection {
        @SerializedName("enabled")
        public boolean enabled = false;

        @SerializedName("sharpness")
        public float sharpness = 0.8f;
    }
}
