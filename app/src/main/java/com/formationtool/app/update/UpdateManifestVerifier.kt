package com.formationtool.app.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

internal object UpdateManifestVerifier {
    private const val MAX_MANIFEST_CHARS = 1024 * 1024
    private const val PUBLIC_KEY_BASE64 =
        "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAnpKGcWMkQ44a0RSo3XNxndBK01GVuozeMfcBSKN5KraMhdwvyp9FumMP2PdkOpovuepFM+HszfFBvxav5tHIBlYcUY5r/GWcgQ+gz7oCvqcRYI/SsuAgE2bxVX7MYv3WQVBRLqYdzsxqeYuDajeSSx+MIF6j0+khkfb3Xu7WzMauzfHG/A/nWqh+bnlWnWLRTe/GMO3M6jzPtDzYFpsCK04IeGeqJKeNb/Y3tbJ3o+H5s8ruWDNBD1LUOwv0REgGqei9fwrO1VB3qVRNjTlp88Q/Jlef9dTyPupnuFSsmO/F4JNgJ468VPrGpof37+aAXM7M1nXZNfY/kyloL3xOw9DndzziuRI09B5282D9V/HVgX4DvB27OkwMTkwHK7xJXirtMI4ohAbMgrvQF/2riqF94GbLWl+Zf2JbwreksZ+Wox6HvVKYXWwiMlUd9lPO7IZENQgpunydjlzwylsBENqvHI2TGcuSTyGSoc8VkrVQVR+6W0SRg0fCqCIfWXudAgMBAAE="

    fun verifyAndParse(manifestJson: String): JsonObject {
        require(manifestJson.length <= MAX_MANIFEST_CHARS) { "更新清单过大" }
        val envelope = JsonParser.parseString(manifestJson).asJsonObject
        require(envelope.get("schemaVersion").asInt == 2) { "不支持的更新清单格式" }
        require(envelope.get("signatureAlgorithm").asString == "SHA256withRSA") {
            "不支持的签名算法"
        }

        val payload = Base64.decode(envelope.get("payload").asString, Base64.DEFAULT)
        val signatureBytes = Base64.decode(envelope.get("signature").asString, Base64.DEFAULT)
        require(payload.size <= MAX_MANIFEST_CHARS) { "更新清单载荷过大" }

        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            X509EncodedKeySpec(Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT))
        )
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update(payload)
        require(verifier.verify(signatureBytes)) { "更新清单签名验证失败" }

        return JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
    }
}
