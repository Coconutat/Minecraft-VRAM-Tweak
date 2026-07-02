package test.vram.tweak.client.mixin.accessor;

import net.minecraft.client.renderer.texture.SpriteContents;
import com.mojang.blaze3d.platform.NativeImage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Safe field access. SpriteContents fields are final — read-only.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {
    @Accessor("originalImage")
    NativeImage getImage();
}
