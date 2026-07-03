package test.vram.tweak.compression;

import com.google.gson.annotations.SerializedName;

/**
 * S3TC compression settings — experimental, independent from other features.
 */
public class S3TCConfig {
    @SerializedName("enabled")
    public boolean enabled = false;

    @SerializedName("compressBlockAtlas")
    public boolean compressBlockAtlas = true;

    @SerializedName("compressEntityTextures")
    public boolean compressEntityTextures = false;

    @SerializedName("compressGuiTextures")
    public boolean compressGuiTextures = false;

    @SerializedName("compressOther")
    public boolean compressOther = false;
}
