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

    @SerializedName("showExperimental")
    public boolean showExperimental = false;

    @SerializedName("experimental")
    public ExperimentalSection experimental = new ExperimentalSection();

    // ---- singleton ----

    private static VRAMConfig instance;
    private static Path configPath;
    private static boolean initialVramEnabled = false;

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
        initialVramEnabled = instance.vram.enabled;
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

    /**
     * True when the in-memory "enabled" toggle differs from what was read at
     * launch time. The config screen uses this to decide whether to show the
     * red "restart required" banner under its title.
     */
    public static boolean isVramRestartRequired() {
        if (instance == null) return false;
        return instance.vram.enabled != initialVramEnabled;
    }

    public static boolean getInitialVramEnabled() { return initialVramEnabled; }

    // ---- VRAM section ----

    public static class VRAMSection {
        @SerializedName("enabled")
        public boolean enabled = false;

        @SerializedName("shadowCapEnabled")
        public boolean shadowCapEnabled = true;

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

        @SerializedName("rgb5a1Conversion")
        public boolean rgb5a1Conversion = false;
    }

    // ---- Diagnostic section ----

    public static class DiagnosticSection {
        @SerializedName("enabled")
        public boolean enabled = true;

        @SerializedName("startupReport")
        public boolean startupReport = true;

        @SerializedName("verificationLog")
        public boolean verificationLog = false;

        @SerializedName("logDirectory")
        public String logDirectory = "logs/vram-tweak";

        // ---- AllocTracker sub-section ----

        @SerializedName("allocTracker")
        public boolean allocTracker = false;

        @SerializedName("allocSnapshotInterval")
        public int allocSnapshotInterval = 30;
    }

    // ---- HUD section ----

    public static class HUDSection {
        @SerializedName("enabled")
        public boolean enabled = true;

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

        @SerializedName("showGpu")
        public boolean showGpu = true;

        @SerializedName("showGpuClocks")
        public boolean showGpuClocks = true;

        @SerializedName("showGovernor")
        public boolean showGovernor = true;

        @SerializedName("showAllocBreakdown")
        public boolean showAllocBreakdown = false;

        @SerializedName("showEviction")
        public boolean showEviction = false;

        @SerializedName("showCompression")
        public boolean showCompression = false;
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

    // ---- Experimental section (AMD-specific features) ----

    public static class ExperimentalSection {
        @SerializedName("pinnedMemory")
        public boolean pinnedMemory = false;

        @SerializedName("pinnedMemoryMinSize")
        public int pinnedMemoryMinSize = 1024; // minimum texture size (px) to use pinned PBO

        // ---- Texture Eviction (VRAM→RAM swap) ----

        @SerializedName("textureEviction")
        public boolean textureEviction = false;

        @SerializedName("evictionThresholdPercent")
        public int evictionThresholdPercent = 80;

        @SerializedName("evictionTargetPercent")
        public int evictionTargetPercent = 60;

    }

}