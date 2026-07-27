package test.vram.tweak.client.mixin.accessor;

import com.mojang.blaze3d.opengl.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor for {@code GlBuffer.handle} field.
 * In 1.21.11 this is a {@code protected final int} field, not a getter method.
 */
@Mixin(GlBuffer.class)
public interface GlBufferAccessor {
    @Accessor("handle")
    int vramtweak$getHandle();
}
