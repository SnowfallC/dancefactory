package com.formationtool.app.update

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

internal object UpdateManifestVerifier {
    private const val MAX_MANIFEST_CHARS = 1024 * 1024
    private const val CURRENT_PUBLIC_KEY_BASE64 =
        "MIIBojANBgkqhkiG9w0BAQEFAAOCAY8AMIIBigKCAYEAnpKGcWMkQ44a0RSo3XNxndBK01GVuozeMfcBSKN5KraMhdwvyp9FumMP2PdkOpovuepFM+HszfFBvxav5tHIBlYcUY5r/GWcgQ+gz7oCvqcRYI/SsuAgE2bxVX7MYv3WQVBRLqYdzsxqeYuDajeSSx+MIF6j0+khkfb3Xu7WzMauzfHG/A/nWqh+bnlWnWLRTe/GMO3M6jzPtDzYFpsCK04IeGeqJKeNb/Y3tbJ3o+H5s8ruWDNBD1LUOwv0REgGqei9fwrO1VB3qVRNjTlp88Q/Jlef9dTyPupnuFSsmO/F4JNgJ468VPrGpof37+aAXM7M1nXZNfY/kyloL3xOw9DndzziuRI09B5282D9V/HVgX4DvB27OkwMTkwHK7xJXirtMI4ohAbMgrvQF/2riqF94GbLWl+Zf2JbwreksZ+Wox6HvVKYXWwiMlUd9lPO7IZENQgpunydjlzwylsBENqvHI2TGcuSTyGSoc8VkrVQVR+6W0SRg0fCqCIfWXudAgMBAAE="
    // 保留旧公钥以验证历史清单；1.1.6 起使用当前公钥发布更新。
    private const val PUBLIC_KEY_BASE64 =
        "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAwsPk1aFhteoQbPAGUPDz/4U2baGptmhnSTGwbInZpWx6lsn/40EGSsjOcoYNNMGoVDVFelTyhUnwOKlqnoqMSCWbj50uc5rU5GnK8ZdugzdmdgtGSAsyUedehg0EyVcFBCAwPEAAKxw+STS1rQbl8pG3xiN4Fkkn1k2H4kDP4LblO7zej9JbPbHPqUFtik3HS72w3Q/fo/db9AIraibqjpNWljU+MMJ8Mu0QrJhVK8cB7CjUR8H0LRTSBY4YUB8nCWobIXI8kUnbVKvHClaGUshD9KM7jbHir3n965YHlMhRdpscdisLKnKzl/eXepAwV7y9+V1YAjYSQ3LE/JMquncKXOzSX+RvMZY82+gTxs9TI9dj/8STylGno6UFJ7LlOnKDutOeGrWwNmLakYueOlf0lHePkQwWP55GKUU0YwH5cnth90kgXQizkR7BfTEVvNs7cB7Ev+CG6nxRcFxvVvGK8Z9AGLAb6cLwdAqdd6/ND23qvzq9vz4kx0fjkp/MasDbX24JJvr4w+4VeQ19rRTCfeZ4imdJCdsfUzSctVTFz10/0iEKNWBewmjxwtkQqaOVGt/zbujfve4qmQ4asY9bGOPukXrMddrdpgiKhs6x9DFXOFknOQHtlgHFlIr/EHvHtHd42NNMoF7MeYsKlRwyu8yiUIJJ7UfNvRdQawECAwEAAQ=="

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

        val verified = listOf(CURRENT_PUBLIC_KEY_BASE64, PUBLIC_KEY_BASE64).any { trustedKey ->
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                X509EncodedKeySpec(Base64.decode(trustedKey, Base64.DEFAULT))
            ) as java.security.interfaces.RSAPublicKey
            // 密钥轮换时签名长度可能不同，跳过不适用于该密钥的签名。
            if (signatureBytes.size != (publicKey.modulus.bitLength() + 7) / 8) {
                false
            } else {
                val verifier = Signature.getInstance("SHA256withRSA")
                verifier.initVerify(publicKey)
                verifier.update(payload)
                verifier.verify(signatureBytes)
            }
        }
        require(verified) { "更新清单签名验证失败" }

        return JsonParser.parseString(payload.toString(Charsets.UTF_8)).asJsonObject
    }
}
