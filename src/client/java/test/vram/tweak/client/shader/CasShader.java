package test.vram.tweak.client.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL32C;

import test.vram.tweak.VRAMTweak;
import test.vram.tweak.config.VRAMConfig;
import test.vram.tweak.diagnostic.VerificationLogger;

/**
 * FidelityFX Contrast Adaptive Sharpening (CAS) 1.2 — single-pass fullscreen sharpen.
 *
 * Based on AMD FidelityFX SDK 1.1.4 ffx_cas.h (MIT licensed).
 * casFilterNoScaling + BETTER_DIAGONALS + green-channel weight path.
 * Embedded GLSL 150 core, no resource files.
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

    private static final float[] QUAD_VERTICES = {
            -1f, -1f, 0f, 0f,  1f, -1f, 1f, 0f,  1f,  1f, 1f, 1f,
            -1f, -1f, 0f, 0f,  1f,  1f, 1f, 1f, -1f,  1f, 0f, 1f,
    };

    // CAS 1.2 fragment shader — AMD FidelityFX (MIT), sharpen-only + BETTER_DIAGONALS
    private static final String FRAG_SHADER = """
            #version 150 core
            uniform sampler2D uTexture;
            uniform vec2 uScreenSize;
            uniform float uSharpness;
            in vec2 vTexCoord;
            out vec4 fragColor;

            void main() {
                vec2 px = 1.0 / uScreenSize;
                vec2 tc = vTexCoord;

                // 3x3 neighborhood, e = center
                vec3 a = texture(uTexture, tc + vec2(-px.x, -px.y)).rgb;
                vec3 b = texture(uTexture, tc + vec2( 0.0, -px.y)).rgb;
                vec3 c = texture(uTexture, tc + vec2( px.x, -px.y)).rgb;
                vec3 d = texture(uTexture, tc + vec2(-px.x,  0.0)).rgb;
                vec3 e = texture(uTexture, tc).rgb;
                vec3 f = texture(uTexture, tc + vec2( px.x,  0.0)).rgb;
                vec3 g = texture(uTexture, tc + vec2(-px.x,  px.y)).rgb;
                vec3 h = texture(uTexture, tc + vec2( 0.0,  px.y)).rgb;
                vec3 i = texture(uTexture, tc + vec2( px.x,  px.y)).rgb;

                // Soft min/max from cross samples (b,d,e,f,h) — 2.0x bigger factored
                vec3 mn = min(min(min(d, e), f), min(b, h));
                vec3 mx = max(max(max(d, e), f), max(b, h));

                // BETTER_DIAGONALS: merge diagonals into min/max
                mn = min(mn, min(min(a, c), min(g, i)));
                mx = max(mx, max(max(a, c), max(g, i)));

                // Amplitude from local contrast
                vec3 amp = clamp(min(mn, 1.0 - mx) / (mx + 1e-8), 0.0, 1.0);
                amp = sqrt(amp);

                // Peak: -1/lerp(8, 5, saturate(s))  — AMD official mapping
                float peak = -1.0 / mix(8.0, 5.0, clamp(uSharpness, 0.0, 1.0));
                vec3 w = amp * peak;

                // Filter using green-channel weight only (SDK canonical path)
                float rw = 1.0 / (1.0 + 4.0 * w.g);
                vec3 outC = (b*w.g + d*w.g + f*w.g + h*w.g + e) * rw;

                fragColor = vec4(clamp(outC, 0.0, 1.0), 1.0);
            }
            """;

    private static final String VERT_SHADER = """
            #version 150 core
            in vec2 aPos; in vec2 aTexCoord; out vec2 vTexCoord;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); vTexCoord = aTexCoord; }
            """;

    public static void init() {
        if (initialized) return;
        initialized = true;
        try {
            int vs = compileShader(GL32C.GL_VERTEX_SHADER, VERT_SHADER);
            int fs = compileShader(GL32C.GL_FRAGMENT_SHADER, FRAG_SHADER);
            programId = GL32C.glCreateProgram();
            GL32C.glAttachShader(programId, vs); GL32C.glAttachShader(programId, fs);
            GL32C.glLinkProgram(programId);
            if (GL32C.glGetProgrami(programId, GL32C.GL_LINK_STATUS) == GL32C.GL_FALSE) {
                VRAMTweak.LOGGER.error("[CAS] Shader link failed: {}", GL32C.glGetProgramInfoLog(programId));
                GL32C.glDeleteProgram(programId); programId = 0; return;
            }
            GL32C.glDeleteShader(vs); GL32C.glDeleteShader(fs);
            sharpnessLoc = GL32C.glGetUniformLocation(programId, "uSharpness");
            screenSizeLoc = GL32C.glGetUniformLocation(programId, "uScreenSize");
            textureLoc = GL32C.glGetUniformLocation(programId, "uTexture");

            vaoId = GL32C.glGenVertexArrays(); GL32C.glBindVertexArray(vaoId);
            vboId = GL32C.glGenBuffers(); GL32C.glBindBuffer(GL32C.GL_ARRAY_BUFFER, vboId);
            GL32C.glBufferData(GL32C.GL_ARRAY_BUFFER, QUAD_VERTICES, GL32C.GL_STATIC_DRAW);
            int stride = 4 * Float.BYTES;
            GL32C.glVertexAttribPointer(0, 2, GL32C.GL_FLOAT, false, stride, 0); GL32C.glEnableVertexAttribArray(0);
            GL32C.glVertexAttribPointer(1, 2, GL32C.GL_FLOAT, false, stride, 2*Float.BYTES); GL32C.glEnableVertexAttribArray(1);
            GL32C.glBindVertexArray(0);
            VRAMTweak.LOGGER.info("[CAS] Shader initialized. program={}", programId);
            VerificationLogger.logCasInit(programId);
        } catch (Exception e) {
            VRAMTweak.LOGGER.error("[CAS] Init failed, CAS disabled", e); programId = 0;
        }
    }

    public static void apply(int fbColorTex, int width, int height) {
        if (programId == 0) return;
        var cfg = VRAMConfig.getInstance().cas;
        if (!cfg.enabled || cfg.sharpness <= 0f) return;
        try {
            RenderSystem.assertOnRenderThread();
            GL32C.glBindFramebuffer(GL32C.GL_FRAMEBUFFER, 0);
            GL32C.glUseProgram(programId);
            GL32C.glUniform1f(sharpnessLoc, cfg.sharpness);
            GL32C.glUniform2f(screenSizeLoc, (float)width, (float)height);
            GL32C.glUniform1i(textureLoc, 0);
            GL32C.glActiveTexture(GL32C.GL_TEXTURE0);
            GL32C.glBindTexture(GL32C.GL_TEXTURE_2D, fbColorTex);
            GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MIN_FILTER, GL32C.GL_LINEAR);
            GL32C.glTexParameteri(GL32C.GL_TEXTURE_2D, GL32C.GL_TEXTURE_MAG_FILTER, GL32C.GL_LINEAR);
            GL32C.glBindVertexArray(vaoId); GL32C.glDrawArrays(GL32C.GL_TRIANGLES, 0, 6); GL32C.glBindVertexArray(0);
            GL32C.glUseProgram(0);
        } catch (Exception e) { VRAMTweak.LOGGER.error("[CAS] Apply failed", e); }
    }

    public static void destroy() {
        if (programId != 0) { GL32C.glDeleteProgram(programId); programId = 0; }
        if (vaoId != 0) { GL32C.glDeleteVertexArrays(vaoId); vaoId = 0; }
        if (vboId != 0) { GL32C.glDeleteBuffers(vboId); vboId = 0; }
        initialized = false;
    }

    private static int compileShader(int type, String source) {
        int shader = GL32C.glCreateShader(type);
        GL32C.glShaderSource(shader, source); GL32C.glCompileShader(shader);
        if (GL32C.glGetShaderi(shader, GL32C.GL_COMPILE_STATUS) == GL32C.GL_FALSE) {
            VRAMTweak.LOGGER.error("[CAS] Shader compile failed ({}): {}", type==GL32C.GL_VERTEX_SHADER?"VS":"FS", GL32C.glGetShaderInfoLog(shader));
            GL32C.glDeleteShader(shader); return 0;
        }
        return shader;
    }
}
