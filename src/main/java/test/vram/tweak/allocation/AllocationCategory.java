package test.vram.tweak.allocation;

/**
 * Classification of what a GPU allocation is used for.
 *
 * <p><b>Texture categories</b> — the dominant VRAM consumers:</p>
 * <ul>
 *   <li>{@link #TEXTURE_ATLAS} — block/item/banner atlas (single largest allocation)</li>
 *   <li>{@link #TEXTURE_BLOCK} — individual block textures (before atlas merge)</li>
 *   <li>{@link #TEXTURE_ENTITY} — entity/player skins</li>
 *   <li>{@link #TEXTURE_ITEM} — item icon textures</li>
 *   <li>{@link #TEXTURE_ENVIRONMENT} — skybox, clouds, sun/moon, weather</li>
 *   <li>{@link #TEXTURE_FONT} — glyph/font atlas</li>
 *   <li>{@link #TEXTURE_GUI} — UI elements, widgets</li>
 *   <li>{@link #TEXTURE_PAINTING} — paintings, maps</li>
 *   <li>{@link #TEXTURE_MISC} — other textures not fitting above</li>
 * </ul>
 *
 * <p><b>Render-target categories</b> — off-screen framebuffer attachments
 * (primarily from Iris shaders):</p>
 * <ul>
 *   <li>{@link #RENDER_TARGET_COLOR} — G-buffer color attachments</li>
 *   <li>{@link #RENDER_TARGET_DEPTH} — depth/stencil attachments</li>
 *   <li>{@link #RENDER_TARGET_SHADOW} — shadow map textures</li>
 *   <li>{@link #RENDER_TARGET_MULTISAMPLE} — MSAA resolve targets</li>
 * </ul>
 *
 * <p><b>Buffer categories</b> — geometry/uniform/pixel buffers (smaller impact):</p>
 * <ul>
 *   <li>{@link #BUFFER_GEOMETRY} — VBO/IBO for terrain/entity meshes</li>
 *   <li>{@link #BUFFER_UNIFORM} — UBO for shader parameters</li>
 *   <li>{@link #BUFFER_PIXEL} — PBO for pixel transfer</li>
 *   <li>{@link #BUFFER_STORAGE} — SSBO for compute</li>
 * </ul>
 *
 * <p>{@link #FRAMEBUFFER} and {@link #UNKNOWN} are catch-all categories.</p>
 *
 * @see SourceTag
 */
public enum AllocationCategory {

    // --- Textures (largest VRAM impact) ---
    TEXTURE_ATLAS,
    TEXTURE_BLOCK,
    TEXTURE_ENTITY,
    TEXTURE_ITEM,
    TEXTURE_ENVIRONMENT,
    TEXTURE_FONT,
    TEXTURE_GUI,
    TEXTURE_PAINTING,
    TEXTURE_MISC,

    // --- Render targets (significant with shaders) ---
    RENDER_TARGET_COLOR,
    RENDER_TARGET_DEPTH,
    RENDER_TARGET_SHADOW,
    RENDER_TARGET_MULTISAMPLE,

    // --- Buffers (small impact, tracked for completeness) ---
    BUFFER_GEOMETRY,
    BUFFER_UNIFORM,
    BUFFER_PIXEL,
    BUFFER_STORAGE,

    // --- Other ---
    FRAMEBUFFER,
    UNKNOWN;

    /** Whether this category is a plain-texture type (not a render target or buffer). */
    public boolean isTexture() {
        return this == TEXTURE_ATLAS || this == TEXTURE_BLOCK || this == TEXTURE_ENTITY
            || this == TEXTURE_ITEM || this == TEXTURE_ENVIRONMENT || this == TEXTURE_FONT
            || this == TEXTURE_GUI || this == TEXTURE_PAINTING || this == TEXTURE_MISC;
    }

    /** Whether this category is a render-target attachment (off-screen FBO). */
    public boolean isRenderTarget() {
        return this == RENDER_TARGET_COLOR || this == RENDER_TARGET_DEPTH
            || this == RENDER_TARGET_SHADOW || this == RENDER_TARGET_MULTISAMPLE;
    }

    /** Whether this category is a buffer object (VBO/UBO/PBO/SSBO). */
    public boolean isBuffer() {
        return this == BUFFER_GEOMETRY || this == BUFFER_UNIFORM
            || this == BUFFER_PIXEL || this == BUFFER_STORAGE;
    }
}
