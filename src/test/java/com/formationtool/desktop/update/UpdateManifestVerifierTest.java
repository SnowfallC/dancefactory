package com.formationtool.desktop.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateManifestVerifierTest {
    @Test
    void verifiesHistoricalSignedFixture() throws Exception {
        String json = readFixture();
        var payload = UpdateManifestVerifier.verifyAndParse(json);

        assertEquals("dancefactory", payload.get("appId").getAsString());
        // 已发布的签名样本不可随应用版本改写，否则会使历史验签失效。
        assertEquals(5, payload.get("versionCode").getAsInt());
        assertEquals("1.1.5", payload.get("version").getAsString());
    }

    @Test
    void rejectsTamperedManifest() throws Exception {
        String json = readFixture();
        String tampered = json.replace("SHA256withRSA", "SHA256withRSB");

        assertThrows(Exception.class, () -> UpdateManifestVerifier.verifyAndParse(tampered));
    }

    @Test
    void verifiesCurrentSigningKeyAfterRotation() throws Exception {
        var payload = UpdateManifestVerifier.verifyAndParse(readCurrentFixture());
        assertEquals("dancefactory", payload.get("appId").getAsString());
        assertEquals(6, payload.get("versionCode").getAsInt());
    }

    @Test
    void rejectsModifiedPayloadWithOriginalSignature() throws Exception {
        var envelope = JsonParser.parseString(readCurrentFixture()).getAsJsonObject();
        String payload = new String(Base64.getDecoder().decode(envelope.get("payload").getAsString()),
                StandardCharsets.UTF_8);
        envelope.addProperty("payload", Base64.getEncoder().encodeToString(
                payload.replace("dancefactory", "other-app").getBytes(StandardCharsets.UTF_8)));
        assertThrows(SecurityException.class, () -> UpdateManifestVerifier.verifyAndParse(envelope.toString()));
    }

    @Test
    void rejectsInvalidSignatureLength() throws Exception {
        var envelope = JsonParser.parseString(readCurrentFixture()).getAsJsonObject();
        envelope.addProperty("signature", Base64.getEncoder().encodeToString(new byte[17]));
        assertThrows(SecurityException.class, () -> UpdateManifestVerifier.verifyAndParse(envelope.toString()));
    }

    private static String readCurrentFixture() throws IOException {
        try (var stream = UpdateManifestVerifierTest.class.getResourceAsStream("/update-manifest-current.json")) {
            if (stream == null) throw new IOException("当前签名测试样本缺失");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String readFixture() throws IOException {
        try (var stream = UpdateManifestVerifierTest.class.getResourceAsStream("/update-manifest.json")) {
            if (stream == null) {
                throw new IOException("Missing signed update manifest test fixture");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
