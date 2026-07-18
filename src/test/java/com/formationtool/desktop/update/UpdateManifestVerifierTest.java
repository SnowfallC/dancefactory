package com.formationtool.desktop.update;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpdateManifestVerifierTest {
    @Test
    void verifiesSignedFixture() throws Exception {
        String json = readFixture();
        var payload = UpdateManifestVerifier.verifyAndParse(json);

        assertEquals("dancefactory", payload.get("appId").getAsString());
        assertEquals("1.1.5", payload.get("version").getAsString());
    }

    @Test
    void rejectsTamperedManifest() throws Exception {
        String json = readFixture();
        String tampered = json.replace("SHA256withRSA", "SHA256withRSB");

        assertThrows(Exception.class, () -> UpdateManifestVerifier.verifyAndParse(tampered));
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
