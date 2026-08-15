package test.vram.tweak.gpu;

import java.util.HashMap;
import java.util.Map;

/**
 * Known VRAM sizes for AMD GPU models.
 * Used to provide accurate total VRAM instead of calibration/rounding.
 *
 * Sources: AMD official specs, GPU database.
 * Last updated: 2026-07-22 (includes RDNA 4)
 */
public class AmdVramLookup {
    // Map from model number prefix → VRAM size in MB
    // Key examples: "9070" matches "RX 9070 XT", "RX 9070"
    private static final Map<Integer, long[]> VRAM_MAP = new HashMap<>();
    // Radeon Pro model name → VRAM size in MB
    private static final Map<String, Long> PRO_VRAM = new HashMap<>();

    static {
        // ---- RDNA 4 (Navi 48) ----
        vram(9070, 16384);   // RX 9070 XT (16GB), RX 9070 (16GB)
        vram(9060, 16384, 8192); // RX 9060 XT (16GB or 8GB variant)
        vram(9050, 8192);    // RX 9050 (rumored)

        // ---- RDNA 3 (Navi 31/32/33) ----
        vram(7900, 24576, 20480, 16384); // XTX 24GB, XT 20GB, GRE 16GB
        vram(7800, 16384); // RX 7800 XT (16GB)
        vram(7700, 12288); // RX 7700 XT (12GB)
        vram(7600, 16384, 8192); // RX 7600 XT (16GB or 8GB); RX 7600 (8GB)
        vram(7500, 6144);  // RX 7500 F (6GB)

        // ---- RDNA 2 (Navi 21/22/23/24) ----
        vram(6950, 16384); // RX 6950 XT (16GB)
        vram(6900, 16384); // RX 6900 XT (16GB)
        vram(6800, 16384); // RX 6800 XT (16GB), RX 6800 (16GB)
        vram(6750, 12288); // RX 6750 XT (12GB)
        vram(6700, 12288, 10240); // RX 6700 XT (12GB), RX 6700 (10GB)
        vram(6650, 8192);  // RX 6650 XT (8GB)
        vram(6600, 8192);  // RX 6600 XT (8GB), RX 6600 (8GB)
        vram(6500, 4096);  // RX 6500 XT (4GB)
        vram(6400, 4096);  // RX 6400 (4GB)

        // ---- RDNA 1 (Navi 10/14/21) ----
        vram(5700, 8192);  // RX 5700 XT (8GB), RX 5700 (8GB)
        vram(5600, 6144);  // RX 5600 XT (6GB)
        vram(5500, 8192, 4096); // RX 5500 XT (8GB or 4GB variants)
        vram(5300, 4096);  // RX 5300 (4GB)

        // ---- Radeon Pro W-series ----
        vramPro("W7900", 49152); // 48GB
        vramPro("W7800", 32768); // 32GB
        vramPro("W7700", 16384); // 16GB
        vramPro("W7600", 8192);  // 8GB
        vramPro("W6600", 8192);  // 8GB
        vramPro("W5500", 8192);  // 8GB

        // ---- APUs (shared memory, use system RAM) ----
        // APU VRAM is dynamic (shared with system) — not in lookup
    }

    private static void vram(int modelPrefix, long... sizesMB) {
        VRAM_MAP.put(modelPrefix, sizesMB);
    }

    private static void vramPro(String model, long sizeMB) {
        PRO_VRAM.put(model, sizeMB);
    }

    /**
     * Look up known VRAM size for the detected AMD GPU.
     *
     * @return VRAM size in MB, or 0 if unknown
     */
    public static long lookup(AmdGpuArch arch, String modelName) {
        return lookup(arch, modelName, 0);
    }

    /**
     * Look up known VRAM size with a startup-free-memory hint.
     * When a model has multiple VRAM variants with the same name (e.g. 8GB vs 16GB),
     * the hint helps disambiguate by picking the closest match.
     *
     * @param arch         AMD GPU architecture
     * @param modelName    GPU model name from GL_RENDERER
     * @param hintFreeMB   free VRAM at startup (≈ total), 0 to skip hint
     * @return VRAM size in MB, or 0 if unknown
     */
    public static long lookup(AmdGpuArch arch, String modelName, long hintFreeMB) {
        if (modelName == null || modelName.isEmpty()) return 0;
        if (arch != null && arch.isAPU()) return 0; // APUs use shared memory

        String upper = modelName.toUpperCase();

        // Check Pro series first
        if (upper.contains("PRO") || upper.contains("W")) {
            for (var entry : PRO_VRAM.entrySet()) {
                if (upper.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        // Extract model number from "RX NNNN" pattern
        int num = extractModelNumber(upper);
        if (num <= 0) return 0;

        long[] candidates = VRAM_MAP.get(num);
        if (candidates == null) return 0;

        if (candidates.length == 1) return candidates[0];

        // Multiple variants — try suffix heuristics first (XTX > XT > no suffix)
        long picked = pickVariant(upper, candidates);
        if (picked > 0) return picked;

        // Suffix didn't help — use hint to pick closest to startup free VRAM
        if (hintFreeMB > 0) {
            long best = candidates[0];
            long bestDiff = Math.abs(hintFreeMB - best);
            for (int i = 1; i < candidates.length; i++) {
                long diff = Math.abs(hintFreeMB - candidates[i]);
                if (diff < bestDiff) { bestDiff = diff; best = candidates[i]; }
            }
            return best;
        }

        // No hint — return 0 (let calibration handle it)
        return 0;
    }

    /** Extract the 4-digit model number from a string like "RX 9070 XT". */
    private static int extractModelNumber(String s) {
        // Look for "RX" followed by digits
        int idx = s.indexOf("RX ");
        if (idx < 0) {
            // Try without RX prefix (some renderer strings)
            for (int i = 0; i < s.length() - 3; i++) {
                if (Character.isDigit(s.charAt(i))
                        && Character.isDigit(s.charAt(i + 1))
                        && Character.isDigit(s.charAt(i + 2))
                        && Character.isDigit(s.charAt(i + 3))) {
                    return Integer.parseInt(s.substring(i, i + 4));
                }
            }
            return 0;
        }
        idx += 3; // skip "RX "
        // Read up to 4 digits
        StringBuilder sb = new StringBuilder();
        while (idx < s.length() && Character.isDigit(s.charAt(idx)) && sb.length() < 4) {
            sb.append(s.charAt(idx));
            idx++;
        }
        if (sb.length() == 0) return 0;
        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Pick the most likely VRAM size when a model has multiple variants.
     * "XTX" → largest, "XT" → middle, no suffix → return 0 (use hint instead).
     *
     * @return picked size in MB, or 0 if suffix can't disambiguate
     */
    private static long pickVariant(String upper, long[] candidates) {
        if (candidates.length == 1) return candidates[0];
        // XTX is always the largest variant
        if (upper.contains("XTX")) return candidates[0];
        // GRE is typically in between
        if (upper.contains("GRE")) return candidates.length >= 2 ? candidates[candidates.length - 2] : candidates[0];
        // "XT" alone — if there are exactly 2 variants with different suffixes,
        // XT is the second. But "RX 9060 XT" could be either 8GB or 16GB —
        // can't tell from suffix alone, return 0 for hint-based resolution.
        if (upper.contains("XT")) {
            // Check if the other variants have distinguishable suffixes
            boolean hasDistinct = false;
            for (int i = 0; i < candidates.length; i++) {
                if (i != 1) hasDistinct = true; // at least 2 candidates exist
            }
            // Unless there's a clear suffix pattern, defer to hint
            return 0;
        }
        // No suffix — return 0 to use hint
        return 0;
    }
}
