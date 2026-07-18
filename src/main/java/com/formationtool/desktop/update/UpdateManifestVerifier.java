package com.formationtool.desktop.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

final class UpdateManifestVerifier {
    private static final int MAX_MANIFEST_CHARS = 1024 * 1024;
    private static final String PUBLIC_KEY_BASE64 =
        "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAnpKGcWMkQ44a0RSo3XNxndBK01GVuozeMfcBSKN5KraMhdwvyp9FumMP2PdkOpovuepFM+HszfFBvxav5tHIBlYcUY5r/GWcgQ+gz7oCvqcRYI/SsuAgE2bxVX7MYv3WQVBRLqYdzsxqeYuDajeSSx+MIF6j0+khkfb3Xu7WzMauzfHG/A/nWqh+bnlWnWLRTe/GMO3M6jzPtDzYFpsCK04IeGeqJKeNb/Y3tbJ3o+H5s8ruWDNBD1LUOwv0REgGqei9fwrO1VB3qVRNjTlp88Q/Jlef9dTyPupnuFSsmO/F4JNgJ468VPrGpof37+aAXM7M1nXZNfY/kyloL3xOw9DndzziuRI09B5282D9V/HVgX4DvB27OkwMTkwHK7xJXirtMI4ohAbMgrvQF/2riqF94GbLWl+Zf2JbwreksZ+Wox6HvVKYXWwiMlUd9lPO7IZENQgpunydjlzwylsBENqvHI2TGcuSTyGSoc8VkrVQVR+6W0SRg0fCqCIfWXudAgMBAAE=";

    private UpdateManifestVerifier() {}

    static JsonObject verifyAndParse(String manifestJson) throws Exception {
        if (manifestJson == null || manifestJson.length() > MAX_MANIFEST_CHARS) {
            throw new SecurityException("更新清单无效或过大");
        }

        JsonObject envelope = JsonParser.parseString(manifestJson).getAsJsonObject();
        if (envelope.get("schemaVersion").getAsInt() != 2
            || !"SHA256withRSA".equals(envelope.get("signatureAlgorithm").getAsString())) {
            throw new SecurityException("不支持的更新清单格式");
        }

        byte[] payload = Base64.getDecoder().decode(envelope.get("payload").getAsString());
        byte[] signatureBytes = Base64.getDecoder().decode(envelope.get("signature").getAsString());
        if (payload.length > MAX_MANIFEST_CHARS) {
            throw new SecurityException("更新清单载荷过大");
        }

        var keySpec = new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_BASE64));
        var publicKey = KeyFactory.getInstance("RSA").generatePublic(keySpec);
        var verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update(payload);
        if (!verifier.verify(signatureBytes)) {
            throw new SecurityException("更新清单签名验证失败");
        }

        return JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
