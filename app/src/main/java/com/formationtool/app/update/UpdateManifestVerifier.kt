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
