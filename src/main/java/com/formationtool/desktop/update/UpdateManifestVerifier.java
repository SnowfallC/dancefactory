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
        "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAwsPk1aFhteoQbPAGUPDz/4U2baGptmhnSTGwbInZpWx6lsn/40EGSsjOcoYNNMGoVDVFelTyhUnwOKlqnoqMSCWbj50uc5rU5GnK8ZdugzdmdgtGSAsyUedehg0EyVcFBCAwPEAAKxw+STS1rQbl8pG3xiN4Fkkn1k2H4kDP4LblO7zej9JbPbHPqUFtik3HS72w3Q/fo/db9AIraibqjpNWljU+MMJ8Mu0QrJhVK8cB7CjUR8H0LRTSBY4YUB8nCWobIXI8kUnbVKvHClaGUshD9KM7jbHir3n965YHlMhRdpscdisLKnKzl/eXepAwV7y9+V1YAjYSQ3LE/JMquncKXOzSX+RvMZY82+gTxs9TI9dj/8STylGno6UFJ7LlOnKDutOeGrWwNmLakYueOlf0lHePkQwWP55GKUU0YwH5cnth90kgXQizkR7BfTEVvNs7cB7Ev+CG6nxRcFxvVvGK8Z9AGLAb6cLwdAqdd6/ND23qvzq9vz4kx0fjkp/MasDbX24JJvr4w+4VeQ19rRTCfeZ4imdJCdsfUzSctVTFz10/0iEKNWBewmjxwtkQqaOVGt/zbujfve4qmQ4asY9bGOPukXrMddrdpgiKhs6x9DFXOFknOQHtlgHFlIr/EHvHtHd42NNMoF7MeYsKlRwyu8yiUIJJ7UfNvRdQawECAwEAAQ==";

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
