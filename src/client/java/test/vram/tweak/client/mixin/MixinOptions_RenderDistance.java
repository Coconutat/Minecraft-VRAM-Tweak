package test.vram.tweak.client.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import test.vram.tweak.vram.VRAMGovernor;

/**
 * Caps render distance at the REAL choke point: ClientChunkCache.updateViewRadius().
 *
 * <p>In Minecraft 26.2, the effective render distance is NOT read from
 * {@code Options.getEffectiveRenderDistance()} (that method is only used in
 * system reports). The actual chunk loading radius comes from network packets:
 * <ul>
 *   <li>Login → {@code setServerRenderDistance(chunkRadius)}</li>
 *   <li>Dynamic → {@code ClientChunkCache.updateViewRadius(radius)}</li>
 * </ul></p>
 *
 * <p>{@code updateViewRadius(int)} is the single choke point — all paths
 * that change the chunk loading radius go through it. Verified via javap.</p>
 */
@Mixin(targets = "net.minecraft.client.multiplayer.ClientChunkCache", remap = false)
public class MixinOptions_RenderDistance {

    private static final Logger LOG = LoggerFactory.getLogger("vram-tweak/governor");
    private static boolean traceInit;

    @ModifyVariable(method = "updateViewRadius(I)V", at = @At("HEAD"), argsOnly = true, remap = false)
    private int capViewRadius(int radius) {
        try {
            int capped = VRAMGovernor.capRenderDistance(radius);
            if (capped != radius) {
                LOG.warn("[Governor] updateViewRadius: {} → {} (VRAM pressure)", radius, capped);
            } else if (!traceInit) {
                traceInit = true;
                LOG.info("[Governor] updateViewRadius={} (no cap, governor enabled={})",
                        radius, VRAMGovernor.isEnabled());
            }
            return capped;
        } catch (Exception e) {
            LOG.error("[Governor] capViewRadius failed", e);
            return radius;
        }
    }
}
