package test.vram.tweak.client.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL32C;

import test.vram.tweak.VRAMTweak;
import test.vram.tweak.config.VRAMConfig;

/**
 * FSR Contrast Adaptive Sharpening (CAS) — single-pass fullscreen sharpening.
 *
 * Based on AMD FidelityFX CAS algorithm (MIT licensed).
 * Embedded GLSL shader, no resource files needed.
 *
 * ponytail: CAS is the simplest sharpening that works. Only one float param.
 */
public class CasShader {
    private static int programId;
    private static int vaoId;
    private static int vboId;
    private static int sharpnessLoc;
    private static int screenSizeLoc;
    private static int textureLoc;
    private static boolean initialized;

    // ponytail: fullscreen quad vertices
    private static final float[] QUAD_VERTICES = {
            -1f, -1f, 0f, 0f,  // bottom-left
             1f, -1f, 1f, 0f,  // bottom-right
             1f,  1f, 1f, 1f,  // top-right
            -1f, -1f, 0f, 0f,  // bottom-left
             1f,  1f, 1f, 1f,  // top-right
            -1f,  1f, 0f, 1f,  // top-left
    };

    // ponytail: CAS fragment shader (AMD FidelityFX, MIT)
    private static final String FRAG_SHADER = """
            #version 150 core
            uniform sampler2D uTexture;
            uniform vec2 uScreenSize;
            uniform float uSharpness;

            in vec2 vTexCoord;
            out vec4 fragColor;

            void main() {
                // CAS kernel: contrast-adaptive sharpen
                vec2 texelSize = 1.0 / uScreenSize;
                vec2 tc = vTexCoord;

                // Sample 3×3 neighborhood
                vec3 a = texture(uTexture, tc + vec2(-texelSize.x, -texelSize.y)).rgb;
                vec3 b = texture(uTexture, tc + vec2(0.0, -texelSize.y)).rgb;
                vec3 c = texture(uTexture, tc + vec2(texelSize.x, -texelSize.y)).rgb;
                vec3 d = texture(uTexture, tc + vec2(-texelSize.x, 0.0)).rgb;
                vec3 e = texture(uTexture, tc).rgb;
                vec3 f = texture(uTexture, tc + vec2(texelSize.x, 0.0)).rgb;
                vec3 g = texture(uTexture, tc + vec2(-texelSize.x, texelSize.y)).rgb;
                vec3 h = texture(uTexture, tc + vec2(0.0, texelSize.y)).rgb;
                vec3 i = texture(uTexture, tc + vec2(texelSize.x, texelSize.y)).rgb;

                // CAS algorithm
                // sharpness 0.0-4.0: 0.8=subtle, 2.0=visible, 4.0=aggressive
                float sharpness = uSharpness;

                // Min and max of neighborhood
                vec3 minRGB = min(min(min(a, b), min(c, d)), min(min(e, f), min(g, h)));
                minRGB = min(minRGB, i);
                vec3 maxRGB = max(max(max(a, b), max(c, d)), max(max(e, f), max(g, h)));
                maxRGB = max(maxRGB, i);

                // Soft filter (Gaussian-like blur edge)
                vec3 softFilter = (a + c + f + h + d + g + b + i) * 0.125 + e * 0.25;

                // CAS: pull center away from soft filter, scaled by local contrast
                vec3 detail = e - softFilter;
                vec3 contrast = (maxRGB - minRGB) + 0.001;
                vec3 sharpened = e + detail * sharpness * clamp(1.0 - abs(detail) / contrast, 0.0, 1.0);

                // Anti-ringing: clamp to local min/max (softer)
                sharpened = mix(sharpened, clamp(sharpened, minRGB, maxRGB), 0.5 + 0.5 * clamp(sharpness * 0.5, 0.0, 1.0));

                fragColor = vec4(sharpened, 1.0);
            }
            """;

    private static final String VERT_SHADER = """
            #version 150 core
            in vec2 aPos;
            in vec2 aTexCoord;
            out vec2 vTexCoord;

            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vTexCoord = aTexCoord;
            }
            """;

    public static void init() {
        if (initialized) return;
        initialized = true;

        try {
            int vs = compileShader(GL32C.GL_VERTEX_SHADER, VERT_SHADER);
            int fs = compileShader(GL32C.GL_FRAGMENT_SHADER, FRAG_SHADER);
            programId = GL32C.glCreateProgram();
            GL32C.glAttachShader(programId, vs);
            GL32C.glAttachShader(programId, fs);
            GL32C.glLinkProgram(programId);

            if (GL32C.glGetProgrami(programId, GL32C.GL_LINK_STATUS) == GL32C.GL_FALSE) {
                String log = GL32C.glGetProgramInfoLog(programId);
                VRAMTweak.LOGGER.error("[CAS] Shader link failed: {}", log);
                GL32C.glDeleteProgram(programId);
                programId = 0;
                return;
            }

            GL32C.glDeleteShader(vs);
            GL32C.glDeleteShader(fs);

            sharpnessLoc = GL32C.glGetUniformLocation(programId, "uSharpness");
            screenSizeLoc = GL32C.glGetUniformLocation(programId, "uScreenSize");
            textureLoc = GL32C.glGetUniformLocation(programId, "uTexture");

            // Create fullscreen quad VAO
            vaoId = GL32C.glGenVertexArrays();
            GL32C.glBindVertexArray(vaoId);

            vboId = GL32C.glGenBuffers();
            GL32C.glBindBuffer(GL32C.GL_ARRAY_BUFFER, vboId);
            GL32C.glBufferData(GL32C.GL_ARRAY_BUFFER, QUAD_VERTICES, GL32C.GL_STATIC_DRAW);

            // pos (vec2) + texcoord (vec2) = 4 floats, stride 16
            int stride = 4 * Float.BYTES;
            GL32C.glVertexAttribPointer(0, 2, GL32C.GL_FLOAT, false, stride, 0);
            GL32C.glEnableVertexAttribArray(0);
            GL32C.glVertexAttribPointer(1, 2, GL32C.GL_FLOAT, false, stride, 2 * Float.BYTES);
            GL32C.glEnableVertexAttribArray(1);

            GL32C.glBindVertexArray(0);

            VRAMTweak.LOGGER.info("[CAS] Shader initialized. program={}", programId);
            test.vram.tweak.diagnostic.VerificationLogger.logCasInit(programId);
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("[CAS] Init failed, CAS disabled", e);
            programId = 0;
        }
    }

    /**
     * Apply CAS sharpening to the main framebuffer color texture.
     * Renders fullscreen quad to default framebuffer (0).
     *
     * @param fbColorTex GL texture ID of the framebuffer color attachment
     * @param width      viewport width
     * @param height     viewport height
     */
    public static void apply(int fbColorTex, int width, int height) {
        if (programId == 0) return;
        var cfg = VRAMConfig.getInstance().cas;
        if (!cfg.enabled || cfg.sharpness <= 0f) return;

        try {
            RenderSystem.assertOnRenderThread();

            // ponytail: explicitly render to default FB to avoid read-write conflict
            // (main FBO's color attachment != default FB → safe to read from it)
            GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, 0);

            GL32C.glUseProgram(programId);
            GL32C.glUniform1f(sharpnessLoc, cfg.sharpness);
            GL32C.glUniform2f(screenSizeLoc, (float) width, (float) height);
            GL32C.glUniform1i(textureLoc, 0);

            GL32C.glActiveTexture(GL32C.GL_TEXTURE0);
            GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, fbColorTex);
            GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MIN_FILTER, GL32C.GL_LINEAR);
            GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MAG_FILTER, GL32C.GL_LINEAR);

            GL32C.glBindVertexArray(vaoId);
            GL32C.glDrawArrays(GL32C.GL_TRIANGLES, 0, 6);
            GL32C.glBindVertexArray(0);

            GL32C.glUseProgram(0);
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("[CAS] Apply failed", e);
        }
    }

    public static void destroy() {
        if (programId != 0) {
            GL32C.glDeleteProgram(programId);
            programId = 0;
        }
        if (vaoId != 0) {
            GL32C.glDeleteVertexArrays(vaoId);
            vaoId = 0;
        }
        if (vboId != 0) {
            GL32C.glDeleteBuffers(vboId);
            vboId = 0;
        }
        initialized = false;
    }

    private static int compileShader(int type, String source) {
        int shader = GL32C.glCreateShader(type);
        GL32C.glShaderSource(shader, source);
        GL32C.glCompileShader(shader);
        if (GL32C.glGetShaderi(shader, GL32C.GL_COMPILE_STATUS) == GL32C.GL_FALSE) {
            String log = GL32C.glGetShaderInfoLog(shader);
            VRAMTweak.LOGGER.error("[CAS] Shader compile failed ({}): {}",
                    type == GL32C.GL_VERTEX_SHADER ? "VS" : "FS", log);
            GL32C.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
