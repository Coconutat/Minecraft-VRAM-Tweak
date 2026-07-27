package test.vram.tweak.client.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import test.vram.tweak.vram.VRAMGovernor;

/**
 * Caps render distance at the REAL choke point: ClientChunkCache.
 *
 * <p>In Minecraft 26.2, the effective render distance is NOT read from
 * {@code Options.getEffectiveRenderDistance()} (that method is only used in
 * system reports). The actual chunk loading radius flows through:
 * <ul>
 *   <li>Login → {@code ClientChunkCache(ClientLevel, int viewDistance)} constructor</li>
 *   <li>Dynamic → {@code ClientChunkCache.updateViewRadius(int radius)}</li>
 * </ul></p>
 *
 * <p>Both paths call {@code calculateStorageRange(int viewDistance)}, so we
 * intercept there for complete coverage (Bug #2 fix: constructor was previously
 * not intercepted).</p>
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache", remap = false)
public class MixinOptions_RenderDistance {

    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/governor");
    private static boolean traceInit;

    /**
     * Intercept {@code updateViewRadius(int)} — dynamic render distance changes
     * from server packets. Kept for logging visibility into server-triggered changes.
     */
    @ModifyVariable(method = "updateViewRadius(I)V", at = @At("HEAD"), argsOnly = true)
    private int capViewRadius(int radius) {
        return capAndLog(radius, "updateViewRadius");
    }

    /**
     * Intercept {@code calculateStorageRange(int)} — called from BOTH the
     * constructor (initial join) and {@code updateViewRadius} (dynamic changes).
     * This is the single true choke point. (Bug #2 fix)
     * <p>MUST be static: {@code calculateStorageRange} is {@code private static}.</p>
     */
    @ModifyVariable(method = "calculateStorageRange(I)I", at = @At("HEAD"), argsOnly = true)
    private static int capStorageRangeRadius(int viewDistance) {
        return capAndLog(viewDistance, "ctor/updateViewRadius");
    }

    private static int capAndLog(int radius, String source) {
        try {
            int capped = VRAMGovernor.capRenderDistance(radius);
            if (capped != radius) {
                LOG.warn("[Governor] {}: {} → {} (VRAM pressure)", source, radius, capped);
            } else if (!traceInit) {
                traceInit = true;
                LOG.info("[Governor] {}={} (no cap, governor enabled={})",
                        source, radius, VRAMGovernor.isEnabled());
            }
            return capped;
        } catch (Exception e) {
            LOG.error("[Governor] cap failed in {}", source, e);
            return radius;
        }
    }
}
